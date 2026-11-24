#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { createConnection, isIPv4 } from 'node:net';
import { parseArgs } from 'node:util';
import { createMcpHandler, Server } from '@modelcontextprotocol/server';
import { AjvJsonSchemaValidator } from '@modelcontextprotocol/server/validators/ajv';
import { toNodeHandler } from '@modelcontextprotocol/node';

const PHONE_PORT = 38766;
const LOOPBACK = '127.0.0.1';
const MAX_REQUEST = 64 * 1024;
const MAX_RESPONSE = 4 * 1024 * 1024;
const TOKEN = /^[0-9a-f]{64}$/;
const ALIAS = /^[a-zA-Z0-9_.-]{1,128}$/;
const VERSION = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version;
const HELP = `Usage:
  yobg-cli tools [--adb] [--serial SERIAL]
  yobg-cli serve [--adb] [--serial SERIAL] [--host IPV4] [--port PORT]
                 [--tls-cert FILE --tls-key FILE] [--native-tool ALIAS=PACKAGE:NAME]...

Phone pairing uses YOBG_PHONE_TOKEN. The MCP endpoint uses a separate YOBG_MCP_TOKEN.
Use --adb with a USB-connected phone, or omit it when running on the phone itself.
Default MCP endpoint: http://127.0.0.1:8765/mcp. LAN binding requires TLS.`;

function readToken(name) {
  const value = process.env[name] ?? '';
  if (!TOKEN.test(value)) throw new Error(`${name} must contain 64 lowercase hex characters`);
  return value;
}

function adbForward(serial) {
  const selection = serial ? ['-s', serial] : [];
  function adb(args) {
    return execFileSync('adb', args, {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 10_000,
    }).trim();
  }

  let device;
  let port;
  try {
    device = adb([...selection, 'get-serialno']);
    if (!device || device === 'unknown') throw new Error('No Android device');
    port = Number(adb(['-s', device, 'forward', 'tcp:0', `tcp:${PHONE_PORT}`]));
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid ADB port');
  } catch {
    throw new Error('ADB forwarding failed. Connect an authorized device or specify --serial');
  }
  return {
    port,
    close() {
      try {
        adb(['-s', device, 'forward', '--remove', `tcp:${port}`]);
      } catch {
        console.error('ADB forwarding could not be removed');
      }
    },
  };
}

async function phoneRequest(port, token, request, signal) {
  const body = Buffer.from(JSON.stringify(request));
  if (body.length > MAX_REQUEST) throw new Error('Tool request exceeds the size limit');
  signal?.throwIfAborted();
  const socket = createConnection({ host: LOOPBACK, port, signal });
  socket.setTimeout(5000, () => socket.destroy(new Error('Phone connection timed out')));
  const chunks = socket[Symbol.asyncIterator]();
  let buffered = Buffer.alloc(0);

  async function read(size) {
    const output = Buffer.allocUnsafe(size);
    let offset = 0;
    while (offset < size) {
      if (buffered.length === 0) {
        const next = await chunks.next();
        if (next.done) throw new Error('Phone closed the connection');
        buffered = next.value;
      }
      const copied = buffered.copy(output, offset, 0, size - offset);
      offset += copied;
      buffered = buffered.subarray(copied);
    }
    return output;
  }

  try {
    const serverNonce = await read(32);
    const clientNonce = randomBytes(32);
    const sign = (role) => createHmac('sha256', token)
      .update(`yobg/1:${role}`).update(serverNonce).update(clientNonce).digest();
    socket.write(Buffer.concat([clientNonce, sign('client')]));
    if (!timingSafeEqual(await read(32), sign('server'))) throw new Error('Invalid phone proof');

    socket.setTimeout(65_000);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(body.length);
    socket.write(Buffer.concat([length, body]));
    const responseLength = (await read(4)).readUInt32BE();
    if (responseLength === 0 || responseLength > MAX_RESPONSE) throw new Error('Invalid phone response length');
    return JSON.parse((await read(responseLength)).toString('utf8'));
  } finally {
    socket.destroy();
  }
}

function selectTools(inventory, selections) {
  const validator = new AjvJsonSchemaValidator();
  const tools = new Map();
  function add(name, item, target, builtIn) {
    if (!ALIAS.test(name) || tools.has(name)) throw new Error(`Invalid or duplicate MCP tool name: ${name}`);
    const inputSchema = builtIn ? { ...item.inputSchema, additionalProperties: false } : item.inputSchema;
    tools.set(name, {
      definition: { name, description: item.description, inputSchema },
      target,
      validate: validator.getValidator(inputSchema),
    });
  }

  for (const item of inventory.phoneUse) add(item.name, item, { name: item.name }, true);
  for (const selection of selections) {
    const match = /^([^=]+)=([^:]*):(.+)$/.exec(selection);
    if (!match) throw new Error('Use --native-tool ALIAS=PACKAGE:NAME');
    const [, alias, appPkg, name] = match;
    const entries = inventory.catalog.filter((entry) => entry.appPkg === appPkg && entry.name === name);
    if (entries.length !== 1) throw new Error(`Expected one catalog entry for ${appPkg}:${name}`);
    add(alias, entries[0], { name, appPkg }, false);
  }
  return tools;
}

function toolResult(text, isError) {
  return { content: [{ type: 'text', text }], isError };
}

function makeMcpHandler(tools, phone, signal) {
  return createMcpHandler(() => {
    const server = new Server({ name: 'yobg-cli', version: VERSION }, { capabilities: { tools: {} } });
    server.setRequestHandler('tools/list', () => ({
      tools: [...tools.values()].map(({ definition }) => definition),
    }));
    server.setRequestHandler('tools/call', async ({ params }, context) => {
      const tool = tools.get(params.name);
      if (!tool) return toolResult('Unknown tool', true);

      const args = params.arguments ?? {};
      const validated = tool.validate(args);
      if (!validated.valid) return toolResult(validated.errorMessage, true);

      try {
        const requestSignal = AbortSignal.any([context.mcpReq.signal, signal]);
        const result = await phone({ op: 'call', ...tool.target, arguments: args }, requestSignal);
        const isError = !(result.runStatus >= 200 && result.runStatus < 300);
        const text = typeof result.result === 'string' ? result.result : JSON.stringify(result.result ?? '');
        return toolResult(text, isError);
      } catch {
        return toolResult('Phone response unavailable. Check YOYO and query the task ID if one was returned', true);
      }
    });
    return server;
  }, { maxRequestBodySize: MAX_REQUEST });
}

function privateLanAddress(host) {
  if (!isIPv4(host)) return false;
  const [first, second] = host.split('.').map(Number);
  return first === 10 || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 168);
}

function listenOptions(values) {
  const host = values.host;
  const port = Number(values.port);
  if (host !== LOOPBACK && !privateLanAddress(host)) {
    throw new Error('--host must be 127.0.0.1 or a specific private LAN IPv4 address');
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid --port');
  if (Boolean(values['tls-cert']) !== Boolean(values['tls-key'])) {
    throw new Error('--tls-cert and --tls-key must be supplied together');
  }
  if (host !== LOOPBACK && !values['tls-cert']) throw new Error('LAN binding requires a TLS certificate and key');
  return { host, port, certificate: values['tls-cert'], key: values['tls-key'] };
}

async function serve(options, token, handler, signal) {
  const { host, port, certificate, key } = options;
  const allowedHosts = new Set([`${host}:${port}`]);
  if (host === LOOPBACK) allowedHosts.add(`localhost:${port}`);
  if (port === (certificate ? 443 : 80)) {
    allowedHosts.add(host);
    if (host === LOOPBACK) allowedHosts.add('localhost');
  }

  const bearer = Buffer.from(token, 'ascii');
  const handleMcp = toNodeHandler(handler, { maxRequestBodySize: MAX_REQUEST });
  function reject(response, status, headers = {}) {
    response.writeHead(status, { 'Cache-Control': 'no-store', Connection: 'close', ...headers });
    response.end();
  }

  const onRequest = (request, response) => {
    if (request.url !== '/mcp') return reject(response, 404);
    if (request.headers.origin !== undefined || !allowedHosts.has(request.headers.host?.toLowerCase())) {
      return reject(response, 403);
    }
    const match = /^Bearer ([0-9a-f]{64})$/i.exec(request.headers.authorization ?? '');
    if (!match || !timingSafeEqual(Buffer.from(match[1], 'ascii'), bearer)) {
      return reject(response, 401, { 'WWW-Authenticate': 'Bearer realm="yobg-cli"' });
    }
    response.setHeader('Cache-Control', 'no-store');
    void handleMcp(request, response).catch(() => response.destroy());
  };

  const serverOptions = { requestTimeout: 10_000, headersTimeout: 5000, connectionsCheckingInterval: 1000 };
  const server = certificate
    ? createHttpsServer({ ...serverOptions, cert: readFileSync(certificate), key: readFileSync(key) }, onRequest)
    : createHttpServer(serverOptions, onRequest);
  server.maxConnections = 32;
  const listening = once(server, 'listening', { signal });
  server.listen({ host, port, signal });
  await listening;
  console.error(`YOYO MCP listening on ${certificate ? 'https' : 'http'}://${host}:${port}/mcp`);
  const onAbort = () => server.closeAllConnections();
  signal.addEventListener('abort', onAbort, { once: true });
  if (signal.aborted) onAbort();
  try {
    await once(server, 'close');
  } finally {
    signal.removeEventListener('abort', onAbort);
  }
}

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      help: { type: 'boolean', short: 'h' },
      adb: { type: 'boolean' },
      serial: { type: 'string' },
      host: { type: 'string', default: LOOPBACK },
      port: { type: 'string', default: '8765' },
      'tls-cert': { type: 'string' },
      'tls-key': { type: 'string' },
      'native-tool': { type: 'string', multiple: true, default: [] },
    },
  });
  if (values.help) {
    console.log(HELP);
    return;
  }
  const [command] = positionals;
  if (positionals.length !== 1 || !['serve', 'tools'].includes(command)) throw new Error(HELP);
  const phoneToken = readToken('YOBG_PHONE_TOKEN');
  const mcpToken = command === 'serve' ? readToken('YOBG_MCP_TOKEN') : null;
  if (phoneToken === mcpToken) throw new Error('Use different phone and MCP tokens');
  const options = command === 'serve' ? listenOptions(values) : null;
  const controller = new AbortController();
  const stop = () => controller.abort();
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);

  let forward;
  try {
    if (values.adb || values.serial) forward = adbForward(values.serial);
    const phone = (request, signal) => phoneRequest(forward?.port ?? PHONE_PORT, phoneToken, request, signal);
    let inventory;
    try {
      inventory = await phone({ op: 'list' }, controller.signal);
      if (!Array.isArray(inventory.phoneUse) || !Array.isArray(inventory.catalog)) throw new Error('Invalid catalog');
    } catch {
      if (controller.signal.aborted) return;
      throw new Error('Could not pair with YOYO. Enable Phone Use, open YOYO and check YOBG_PHONE_TOKEN');
    }

    if (command === 'tools') {
      console.log(JSON.stringify(inventory, null, 2));
      return;
    }

    const tools = selectTools(inventory, values['native-tool']);
    if (controller.signal.aborted) return;
    const handler = makeMcpHandler(tools, phone, controller.signal);
    try {
      await serve(options, mcpToken, handler, controller.signal);
    } catch (error) {
      if (!controller.signal.aborted) throw error;
    } finally {
      await handler.close();
    }
  } finally {
    forward?.close();
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
