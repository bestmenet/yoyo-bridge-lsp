"""Exercise the actual Java backend against a loopback OpenAI-compatible fixture."""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import stat
import struct
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
JAVA = os.environ.get("BRIDGE_JAVA", "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java")
JSON_JAR = Path(os.environ.get("BRIDGE_JSON_JAR", str(ROOT.parents[0] / "tooling/json.jar")))
SECRET = "test-fixture-secret-$('never-execute')"


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.receipts.append((self.path, self.headers.get("Authorization"), payload))
        if self.path == "/redirect":
            self.send_response(307)
            self.send_header("Location", "/leaked")
            self.end_headers()
            return
        if self.path == "/denied":
            self.send_response(401)
            self.end_headers()
            self.wfile.write(SECRET.encode())
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream" if payload["stream"] else "application/json")
        self.end_headers()
        if self.path == "/invalid":
            self.wfile.write(b'{"choices":[]}')
        elif not payload["stream"]:
            if self.path == "/native-flow":
                message = self.native_message(payload["messages"])
            elif self.path == "/meta-flow":
                message = self.meta_message(payload["messages"])
            elif self.path == "/local-session":
                message = self.local_session_message(payload["messages"])
            else:
                message = {"role": "assistant", "content": "OK"}
            self.wfile.write(json.dumps({"choices": [{"message": message}]}).encode())
        elif self.path == "/no-text":
            self.wfile.write(b'data: [DONE]\n\n')
        elif self.path == "/stream-error":
            self.wfile.write(b'data: {"error":{"message":"secret"}}\n\n')
        elif self.path == "/partial":
            self.wfile.write(b'data: {"choices":[{"delta":{"content":"OK"}}]}\n\n')
        else:
            self.wfile.write(b'data: {"choices":[{"delta":{"content":"OK"},"finish_reason":null}]}\n\ndata: [DONE]\n\n')

    @staticmethod
    def native_message(messages):
        last = messages[-1]
        def call(id, name, args):
            return {"role":"assistant", "content":"", "tool_calls":[{
                "id":id, "type":"function", "function":{
                    "name":name, "arguments":json.dumps(args)}}]}
        if last.get("role") == "user":
            return call("native-schema", "yoyo_get_tool_schema", {"name":"gui.create"})
        if last.get("tool_call_id") == "native-schema":
            return call("native-create", "yoyo_call_tool", {"name":"gui.create", "appPkg":"com.hihonor.magicvoice", "arguments":{"app_name":"微信", "task_query":"给最新一条朋友圈点赞"}})
        if last.get("tool_call_id") == "native-create":
            return call("native-query", "yoyo_call_tool", {"name":"task-result-query", "arguments":{"task_id":"fixture-task"}})
        return {"role":"assistant", "content":"DONE"}

    @staticmethod
    def meta_message(messages):
        last = messages[-1]
        if last.get("role") == "user":
            return {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "id": "schema-call",
                    "type": "function",
                    "function": {
                        "name": "yoyo_get_tool_schema",
                        "arguments": json.dumps({
                            "name": "toggleEyeTrackTurnPage",
                            "appPkg": "com.android.settings",
                        }),
                    },
                }],
            }
        if '"inputSchema"' in last.get("content", ""):
            return {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "id": "phone-call",
                    "type": "function",
                    "function": {
                        "name": "yoyo_call_tool",
                        "arguments": json.dumps({
                            "name": "toggleEyeTrackTurnPage",
                            "appPkg": "com.android.settings",
                            "arguments": {"action": "open"},
                        }),
                    },
                }],
            }
        return {"role": "assistant", "content": "DONE"}

    @staticmethod
    def local_session_message(messages):
        if messages[-1].get("role") == "tool" and messages[-1].get("tool_call_id") == "second-call":
            return {"role": "assistant", "content": "native complete"}
        last_user = next((m.get("content", "") for m in reversed(messages)
                          if m.get("role") == "user" and isinstance(m.get("content"), str)), "")
        if last_user == "large-one":
            return {"role": "assistant", "content": "x" * 600000}
        if last_user in ("cause tool interruption", "native-on-second"):
            return {"role": "assistant", "content": "", "tool_calls": [{
                "id": "interrupted-call" if last_user == "cause tool interruption" else "second-call",
                "type": "function",
                "function": {"name": "yoyo_call_tool", "arguments": json.dumps({
                    "name": "gui.create", "appPkg": "com.hihonor.magicvoice",
                    "arguments": {"app_name": "微信", "task_query": "fixture"}})},
            }]}
        return {"role": "assistant", "content": "OK"}


class WireSocket:
    def __init__(self, sock):
        self.sock = sock

    def send(self, data):
        self.sock.sendall(data)

    def recv_exact(self, size):
        data = b""
        while len(data) < size:
            part = self.sock.recv(size - len(data))
            if not part:
                raise EOFError("socket closed")
            data += part
        return data

    def send_frame(self, payload, opcode=1, masked=True, fin=True):
        if isinstance(payload, str):
            payload = payload.encode()
        head = bytes([(0x80 if fin else 0) | opcode])
        mask_bit = 0x80 if masked else 0
        if len(payload) < 126:
            head += bytes([mask_bit | len(payload)])
        elif len(payload) <= 65535:
            head += bytes([mask_bit | 126]) + struct.pack("!H", len(payload))
        else:
            head += bytes([mask_bit | 127]) + struct.pack("!Q", len(payload))
        if masked:
            key = os.urandom(4)
            body = bytes(value ^ key[index % 4] for index, value in enumerate(payload))
            self.sock.sendall(head + key + body)
        else:
            self.sock.sendall(head + payload)

    def recv_frame(self):
        first, second = self.recv_exact(2)
        opcode = first & 0x0f
        length = second & 0x7f
        if length == 126:
            length = struct.unpack("!H", self.recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self.recv_exact(8))[0]
        mask = self.recv_exact(4) if second & 0x80 else None
        payload = self.recv_exact(length)
        if mask:
            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        return opcode, payload

    def recv_json(self):
        while True:
            opcode, payload = self.recv_frame()
            if opcode == 1:
                return json.loads(payload)
            if opcode == 8:
                raise EOFError("websocket closed")

    def close(self):
        try:
            self.send_frame(b"", opcode=8)
        except OSError:
            pass
        self.sock.close()


def ws_client(port, path="/aicloud/yoyo-claw-service/v2/yoyoclaw/connect", timeout=8):
    sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    sock.settimeout(timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: 127.0.0.1:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    ).encode()
    sock.sendall(request)
    response = b""
    while b"\r\n\r\n" not in response:
        response += sock.recv(4096)
    if not response.startswith(b"HTTP/1.1 101"):
        raise AssertionError(response.decode(errors="replace"))
    expected = base64.b64encode(hashlib.sha1(
        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
    if expected not in response:
        raise AssertionError("bad websocket accept")
    return WireSocket(sock)


class UpstreamWebSocket:
    def __init__(self):
        self.server = socket.socket()
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(1)
        self.port = self.server.getsockname()[1]
        self.received = []
        self.error = None
        self.thread = threading.Thread(target=self.run, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def run(self):
        try:
            conn, _ = self.server.accept()
            conn.settimeout(8)
            request = b""
            while b"\r\n\r\n" not in request:
                request += conn.recv(4096)
            headers = {}
            for line in request.decode().split("\r\n")[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    headers[key.lower()] = value.strip()
            accept = base64.b64encode(hashlib.sha1(
                (headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
            ).digest())
            conn.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\nConnection: Upgrade\r\n"
                b"Sec-WebSocket-Accept: " + accept + b"\r\n\r\n"
            )
            wire = WireSocket(conn)
            _, payload = wire.recv_frame()
            self.received.append(payload.decode())
            wire.send_frame('{"upstream":"ok"}', masked=False)
            conn.close()
        except Exception as error:
            self.error = error

    def close(self):
        self.server.close()
        self.thread.join(timeout=10)


class BackendTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
        cls.server.receipts = []
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name) / "state"
        self.addCleanup(self.temporary.cleanup)

    def call(self, command, data=None, properties=None):
        raw = base64.b64encode(json.dumps(data).encode()) if data is not None else b""
        arguments = [JAVA]
        for key, value in (properties or {}).items():
            arguments.append(f"-D{key}={value}")
        result = subprocess.run(arguments + ["-cp", f"{ROOT}/build/lsp/classes:{JSON_JAR}",
                                 "dev.yoyobridge.Bridge", command, str(self.directory)],
                                input=raw, capture_output=True, timeout=35)
        self.assertEqual(result.stderr, b"")
        return json.loads(result.stdout)

    def config(self, path="/chat", **overrides):
        data = dict(endpoint=f"http://127.0.0.1:{self.server.server_port}{path}",
                    apiKey=SECRET, keyAction="replace", model="模型-test", allowHttp=True, stream=False)
        return dict(data, **overrides)

    def test_status_reports_adapter_ready(self):
        result = self.call("status")
        self.assertFalse(result["active"])
        self.assertEqual(result["adapterState"], "ready")
        self.assertEqual(result["config"]["scope"], "custom-provider-only")
        self.assertEqual(result["config"]["schemaVersion"], 2)

    def test_save_roundtrip_hides_key(self):
        saved = self.call("save", self.config())
        self.assertTrue(saved["ok"])
        self.assertNotIn(SECRET, json.dumps(saved))
        status = self.call("status")["config"]
        self.assertTrue(status["hasApiKey"])
        self.assertNotIn("apiKey", status)
        self.assertEqual(status["model"], "模型-test")
        self.assertEqual(json.loads((self.directory / "config.json").read_text())["apiKey"], SECRET)

    def test_files_are_private(self):
        self.call("save", self.config())
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        for name in ["config.json", "config.lock"]:
            self.assertEqual(stat.S_IMODE((self.directory / name).stat().st_mode), 0o600)

    def test_keep_and_clear_key(self):
        self.call("save", self.config())
        self.assertTrue(self.call("save", self.config(apiKey="", keyAction="keep"))["config"]["hasApiKey"])
        self.assertFalse(self.call("save", self.config(apiKey="", keyAction="clear"))["config"]["hasApiKey"])

    def test_enable_is_saved(self):
        result = self.call("save", self.config(enabled=True))
        self.assertTrue(result["ok"])
        self.assertTrue(result["config"]["enabled"])

    def test_builtin_scope_rejected(self):
        self.assertEqual(self.call("save", self.config(scope="all-models"))["code"], "SCOPE")

    def test_unverified_slot_rejected(self):
        self.assertEqual(self.call("save", self.config(providerSlot="guess"))["code"], "SCOPE")

    def test_http_requires_opt_in(self):
        self.assertEqual(self.call("save", self.config(allowHttp=False))["code"], "HTTP_DISABLED")

    def test_invalid_urls_rejected(self):
        for url in ["file:///etc/passwd", "https://u:p@example.com/v1", "https://example.com/#x", "not a url"]:
            self.assertEqual(self.call("save", self.config(endpoint=url))["code"], "URL")

    def test_header_injection_rejected(self):
        self.assertEqual(self.call("save", self.config(apiKey="x\r\nInjected: yes"))["code"], "CONTROL")

    def test_type_and_length_validation(self):
        self.assertEqual(self.call("save", self.config(stream="true"))["code"], "TYPE")
        self.assertEqual(self.call("save", self.config(model="x" * 257))["code"], "LENGTH")

    def test_text_completion_and_auth(self):
        out = self.call("test", self.config())
        self.assertTrue(out["ok"])
        self.assertEqual(out["httpStatus"], 200)
        path, auth, payload = self.server.receipts[-1]
        self.assertEqual(auth, "Bearer " + SECRET)
        self.assertEqual(payload["model"], "模型-test")
        self.assertFalse(payload["stream"])
        self.assertNotIn(SECRET, json.dumps(out))

    def test_saved_key_used_for_test(self):
        self.call("save", self.config())
        self.assertTrue(self.call("test", self.config(apiKey=""))["ok"])
        self.assertEqual(self.server.receipts[-1][1], "Bearer " + SECRET)

    def test_successful_sse(self):
        self.assertTrue(self.call("test", self.config(stream=True))["ok"])

    def test_partial_sse_rejected(self):
        self.assertEqual(self.call("test", self.config("/partial", stream=True))["code"], "SSE")

    def test_empty_sse_rejected(self):
        self.assertEqual(self.call("test", self.config("/no-text", stream=True))["code"], "SSE")

    def test_sse_error_rejected(self):
        self.assertEqual(self.call("test", self.config("/stream-error", stream=True))["code"], "SSE")

    def test_noncompletion_json_rejected(self):
        self.assertEqual(self.call("test", self.config("/invalid"))["code"], "RESPONSE")

    def test_redirect_not_followed(self):
        before = len(self.server.receipts)
        self.assertEqual(self.call("test", self.config("/redirect"))["httpStatus"], 307)
        self.assertEqual(len(self.server.receipts), before + 1)

    def test_error_body_not_exposed(self):
        out = self.call("test", self.config("/denied"))
        self.assertEqual(out["httpStatus"], 401)
        self.assertNotIn(SECRET, json.dumps(out))

    def test_failure_does_not_overwrite_config(self):
        self.call("save", self.config())
        before = (self.directory / "config.json").read_bytes()
        self.call("save", self.config(apiKey="new", providerSlot="invalid"))
        self.assertEqual((self.directory / "config.json").read_bytes(), before)
        self.assertEqual(list(self.directory.glob("*.tmp")), [])

    def test_unknown_command_rejected(self):
        self.assertEqual(self.call("anything")["code"], "COMMAND")

    def test_activation_restores_previous_route_and_keeps_builtin_models(self):
        builtin = {
            "providerCode": "deepseek",
            "providerName": "DeepSeek",
            "modelId": "deepseek-v3",
            "modelName": "DeepSeek V3",
        }
        prefs = self.write_prefs(provider="deepseek", model="deepseek-v3",
                                 model_name="DeepSeek V3", models=[builtin])
        properties = {"yoyo.prefs": prefs, "yoyo.am": "skip"}
        self.call("save", self.config(enabled=True))

        activated = self.call("activate", properties=properties)
        self.assertTrue(activated["route"]["customSelected"])
        self.assertEqual(activated["route"]["modelId"], "yoyo-bridge-custom")
        self.assertTrue((self.directory / "route.backup.json").is_file())

        restored = self.call("deactivate", properties=properties)
        self.assertFalse(restored["route"]["customSelected"])
        self.assertEqual(restored["route"]["providerCode"], "deepseek")
        self.assertEqual(restored["route"]["modelId"], "deepseek-v3")
        self.assertFalse((self.directory / "route.managed").exists())
        xml = prefs.read_text()
        self.assertIn("deepseek-v3", xml)
        self.assertNotIn("yoyo_bridge", xml)

    def test_prepare_and_restore_preserve_existing_url_config(self):
        target = self.directory.parent / "urlConfig.txt"
        original = b'{"OTHER":"keep","NUMBER":7}\n'
        target.write_bytes(original)
        properties = {"yoyo.urlConfig": target}

        prepared = self.call("prepare", properties=properties)
        self.assertTrue(prepared["backup"])
        self.assertEqual(json.loads(target.read_text())["CLAW_WS_URL"], "ws://127.0.0.1:38765")
        self.assertEqual(json.loads(target.read_text())["OTHER"], "keep")

        self.call("prepare", properties=properties)
        restored = self.call("restore", properties=properties)
        self.assertTrue(restored["restored"])
        self.assertEqual(target.read_bytes(), original)
        self.assertFalse((self.directory / "urlConfig.backup").exists())

    def test_prepare_and_restore_remove_created_url_config(self):
        target = self.directory.parent / "urlConfig.txt"
        properties = {"yoyo.urlConfig": target}
        self.assertEqual(self.call("prepare", properties=properties)["backup"], False)
        self.assertTrue(target.is_file())
        self.assertEqual(self.call("restore", properties=properties)["restored"], False)
        self.assertFalse(target.exists())

    def free_port(self):
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        sock.close()
        return port

    def write_prefs(self, provider="", model="honor_ah", model_name="YOYO 智能体", models=None):
        prefs = self.directory.parent / "com.hihonor.magicvoice.xml"
        configured = json.dumps(models or [], ensure_ascii=False).replace("&", "&amp;").replace(
            "<", "&lt;").replace(">", "&gt;")
        prefs.write_text(
            '<map>\n'
            '  <string name="claw_agent_mode">pro</string>\n'
            f'  <string name="claw_pro_provider_code">{provider}</string>\n'
            f'  <string name="claw_pro_model">{model}</string>\n'
            f'  <string name="claw_pro_model_name">{model_name}</string>\n'
            f'  <string name="key_configured_enabled_models">{configured}</string>\n'
            '</map>\n'
        )
        return prefs

    def write_tools(self):
        tools = self.directory.parent / "mcp_tools.json"
        tools.write_text(json.dumps({"tools": [{
            "name": "toggleEyeTrackTurnPage",
            "appPkg": "com.android.settings",
            "description": "眼动翻页设置",
            "inputSchema": {
                "type": "object",
                "properties": {"action": {"type": "string", "enum": ["open", "close", "query"]}},
                "required": ["action"],
                "additionalProperties": False,
            },
        }]}, ensure_ascii=False))
        return tools

    def start_gateway(self, config, provider="", tool_catalog=None):
        self.directory.mkdir(parents=True, exist_ok=True)
        prefs = self.write_prefs(provider)
        if tool_catalog is None:
            tool_catalog = self.write_tools()
        self.call("save", config)
        port = config["port"]
        process = subprocess.Popen([
            JAVA,
            f"-Dyoyo.prefs={prefs}",
            f"-Dyoyo.tools={tool_catalog}",
            "-cp", f"{ROOT}/build/lsp/classes:{JSON_JAR}",
            "dev.yoyobridge.Bridge", "serve", str(self.directory),
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.gateway_process = process
        self.addCleanup(self.stop_gateway, process)
        for _ in range(80):
            if process.poll() is not None:
                out, err = process.communicate(timeout=2)
                self.fail(f"gateway exited: {out!r} {err!r}")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    return process
            except OSError:
                threading.Event().wait(0.05)
        self.fail("gateway did not start")

    @staticmethod
    def stop_gateway(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for stream in (process.stdout, process.stderr):
            if stream:
                stream.close()

    @staticmethod
    def envelope(msg_type, data, trace=None, text=None):
        if text is not None:
            data = {"userMessage": {"input": [{"content": {"type": "text", "text": text}}]}}
        return {
            "from": {"role": "node", "deviceId": "phone-test", "port": 0},
            "to": {"role": "yoyoclaw", "deviceId": "cloud-test", "port": 123},
            "payload": {
                "msgType": msg_type,
                "data": data,
                "traceInfo": trace or {
                    "traceId": "trace-test",
                    "turnId": "turn-test",
                    "sessionId": "session-test",
                    "timestamp": 123456789,
                },
            },
        }

    @staticmethod
    def reply(event):
        return event["wsOutputEvent"]["payload"]

    @staticmethod
    def agent(event):
        return BackendTests.reply(event).get("data", {}).get("agentMessage", {})

    @staticmethod
    def period(event):
        return BackendTests.agent(event).get("period")

    def recv_until(self, client, predicate):
        events = []
        for _ in range(20):
            try:
                event = client.recv_json()
            except socket.timeout:
                log_file = self.directory / "bridge.log"
                log = log_file.read_text(errors="replace") if log_file.exists() else ""
                stderr = b""
                process = getattr(self, "gateway_process", None)
                if process and process.poll() is not None and process.stderr:
                    stderr = process.stderr.read()
                self.fail(f"timed out waiting for event; events={events!r}; log={log!r}; stderr={stderr!r}")
            events.append(event)
            if predicate(event):
                return event, events
        self.fail(f"condition not reached: {events}")

    def test_local_gateway_chats_with_custom_provider(self):
        port = self.free_port()
        self.start_gateway(self.config(enabled=True, port=port), provider="yoyo_bridge")
        client = ws_client(port)
        self.addCleanup(client.close)
        client.send_frame(json.dumps(self.envelope("userMessage", {}, text="你好")))
        completed, events = self.recv_until(client, lambda event: self.period(event) == "completed")
        text = "".join(
            content.get("text", "")
            for event in events
            if self.period(event) == "message"
            for content in self.agent(event).get("content", [])
        )
        self.assertEqual(text, "OK")
        self.assertEqual(self.period(completed), "completed")
        self.assertNotEqual(self.agent(completed)["id"], "turn-test")
        thinking = next(event for event in events if self.period(event) == "thinking")
        self.assertEqual(self.agent(thinking)["content"][0]["text"], "正在请求自定义模型")
        self.assertNotEqual(self.agent(thinking)["id"], "正在请求自定义模型")

    def test_direct_local_agent_uses_native_gui_and_final_result_query(self):
        source = self.directory.parent / "LocalBridgeCheck.java"
        source.write_text(r'''import dev.yoyobridge.Bridge;
import org.json.*;
public class LocalBridgeCheck {
 public static void main(String[] args) throws Exception {
  JSONObject config = new JSONObject(args[0]);
  JSONArray calls = new JSONArray(); JSONArray replies = new JSONArray();
  Bridge.runLocalTask(config, "测试原生GUI", (name, app, input) -> {
   calls.put(name);
   String result = "gui.create".equals(name) ? "{\"task_id\":\"fixture-task\"}" : "{\"is_final\":true,\"answer\":\"done\"}";
   return new JSONObject().put("runStatus", 200).put("result", result);
  }, (period,text) -> { if ("message".equals(period)) replies.put(text); });
  System.out.println(new JSONObject().put("calls",calls).put("replies",replies));
 }
}''')
        javac = str(Path(JAVA).with_name("javac"))
        subprocess.run([javac, "--release", "8", "-cp", f"{ROOT}/build/lsp/classes:{JSON_JAR}",
                        "-d", str(self.directory.parent), str(source)], capture_output=True, check=True)
        config = self.config(path="/native-flow", enabled=True)
        result = subprocess.run([JAVA, "-cp", f"{self.directory.parent}:{ROOT}/build/lsp/classes:{JSON_JAR}",
                                 "LocalBridgeCheck", json.dumps(config)], capture_output=True, timeout=25, check=True)
        response = json.loads(result.stdout)
        self.assertEqual(response["calls"], ["gui.create", "task-result-query"])
        self.assertEqual(response["replies"], ["DONE"])

    def test_local_conversation_reuse_isolation_config_rotation_and_failure_repair(self):
        self.server.receipts.clear()
        source = self.directory.parent / "LocalSessionCheck.java"
        source.write_text(r'''import dev.yoyobridge.Bridge;
import org.json.*;
public class LocalSessionCheck {
 static Bridge.LocalToolInvoker invoker(final String label, JSONArray seen) {
  return (name, app, input) -> { seen.put(label); if("failing".equals(label)) throw new Exception("fixture tool failure"); return new JSONObject().put("runStatus",200).put("result","{}"); };
 }
 static void run(JSONObject config,String text,String key,String label,JSONArray seen,JSONArray replies) throws Exception {
  Bridge.runLocalTask(config,text,key,invoker(label,seen),(period,value)->{
   if("message".equals(period)) replies.put(label+":"+value);
  });
 }
 public static void main(String[] args) throws Exception {
  JSONObject config=new JSONObject(args[0]); JSONArray seen=new JSONArray(), replies=new JSONArray();
  run(config,"alpha","topic-A","first",seen,replies);
  run(config,"native-on-second","topic-A","second",seen,replies);
  run(config,"beta","topic-A","third",seen,replies);
  run(config,"gamma","topic-B","other-topic",seen,replies);
  JSONObject changed=new JSONObject(config.toString()).put("model","rotated-model");
  run(changed,"delta","topic-A","rotated",seen,replies);
  run(config,"cause tool interruption","failure-topic","failing",seen,replies);
  run(config,"follow up","failure-topic","recovered",seen,replies);
  for(int i=0;i<9;i++) run(config,"evict-"+i,"capacity-"+i,"cap",seen,replies);
  run(config,"after eviction","capacity-0","evicted",seen,replies);
  run(config,"large-one","large-history","large",seen,replies);
  run(config,"large-two","large-history","large-next",seen,replies);
  System.out.println(new JSONObject().put("seen",seen).put("replies",replies));
 }
}''')
        javac = str(Path(JAVA).with_name("javac"))
        classpath = f"{ROOT}/build/lsp/classes:{JSON_JAR}"
        subprocess.run([javac, "--release", "8", "-cp", classpath,
                        "-d", str(self.directory.parent), str(source)], capture_output=True, check=True)
        result = subprocess.run([JAVA, "-cp", f"{self.directory.parent}:{classpath}",
                                 "LocalSessionCheck", json.dumps(self.config(path="/local-session", localExecution=True))],
                                capture_output=True, timeout=45, check=True)
        response = json.loads(result.stdout)
        requests = [payload for path, _, payload in self.server.receipts if path == "/local-session"]

        beta = next(payload for payload in requests
                    if payload["messages"][-1].get("content") == "beta")
        beta_messages = beta["messages"]
        self.assertIn("alpha", [m.get("content") for m in beta_messages])
        self.assertIn("OK", [m.get("content") for m in beta_messages if m.get("role") == "assistant"])
        prompt = beta_messages[0]["content"]
        self.assertIn("gui.create", prompt)
        self.assertIn("task-result-query", prompt)
        self.assertIn("local.ui", prompt)
        self.assertIn("YOYO workflow", prompt)
        self.assertIn("不得用闹钟替代", prompt)
        self.assertNotIn("荣耀云端GUI已禁止微信", prompt)

        gamma = next(payload for payload in requests if payload["messages"][-1].get("content") == "gamma")
        self.assertNotIn("alpha", [m.get("content") for m in gamma["messages"]])
        delta = next(payload for payload in requests if payload["model"] == "rotated-model")
        self.assertEqual([m.get("content") for m in delta["messages"] if m.get("role") == "user"], ["delta"])

        recovered = next(payload for payload in requests
                         if payload["messages"][-1].get("content") == "follow up")
        self.assertFalse(any(m.get("tool_calls") for m in recovered["messages"]))
        self.assertIn("second", response["seen"])
        self.assertTrue(any(item == "recovered:OK" for item in response["replies"]))

        after_eviction = next(payload for payload in requests
                              if payload["messages"][-1].get("content") == "after eviction")
        self.assertEqual([m.get("content") for m in after_eviction["messages"]
                          if m.get("role") == "user"], ["after eviction"])

        bounded = next(payload for payload in requests
                       if payload["messages"][-1].get("content") == "large-two")
        self.assertLessEqual(len(json.dumps(bounded["messages"], ensure_ascii=False)), 512 * 1024)
        self.assertLessEqual(len(bounded["messages"]), 64)
        self.assertEqual([m.get("content") for m in bounded["messages"] if m.get("role") == "user"],
                         ["large-one", "large-two"])
        self.assertFalse(any(m.get("tool_calls") for m in bounded["messages"]))

    def test_native_gui_creation_and_result_query_survive_mcp_context_update(self):
        port = self.free_port()
        self.start_gateway(self.config(path="/native-flow", enabled=True, port=port), provider="yoyo_bridge")
        client = ws_client(port)
        self.addCleanup(client.close)
        client.send_frame(json.dumps(self.envelope("updateContexts", {"contexts":[{
            "header":{"name":"DeviceToolSet"}, "payload":{"tool":[{"name":"battery", "appPkg":"com.hihonor.magicvoice"}]}}]})))
        client.send_frame(json.dumps(self.envelope("userMessage", {}, text="测试原生GUI")))
        schema, _ = self.recv_until(client, lambda e:self.period(e)=="toolresult")
        self.assertIn("task_query", self.agent(schema)["content"][0]["text"])
        for tool_id, command, args, answer in [
            ("native-create", "gui.create", {"app_name":"微信", "task_query":"给最新一条朋友圈点赞"}, {"task_id":"fixture-task"}),
            ("native-query", "task-result-query", {"task_id":"fixture-task"}, {"is_final":True,"answer":"fixture-done"})]:
            request, _ = self.recv_until(client, lambda e:self.reply(e).get("msgType")=="nodeInvokeRequest")
            payload=self.reply(request)["data"]["node"]["payload"]
            self.assertEqual(payload["command"], command)
            self.assertEqual(payload["paramsJSON"]["arguments"], args)
            client.send_frame(json.dumps(self.envelope("nodeInvokeResult", {"node":{"payload":{
                "toolCallId":tool_id,"runStatus":200,"result":json.dumps(answer)}}})))
        completed, _ = self.recv_until(client, lambda e:self.period(e)=="completed")
        self.assertEqual(self.period(completed),"completed")

    def test_fragmented_websocket_message_is_reassembled(self):
        port = self.free_port()
        self.start_gateway(self.config(enabled=True, port=port), provider="yoyo_bridge")
        client = ws_client(port)
        self.addCleanup(client.close)
        payload = json.dumps(self.envelope("userMessage", {}, text="碎片消息")).encode()
        split = len(payload) // 2
        client.send_frame(payload[:split], fin=False)
        client.send_frame(payload[split:], opcode=0, fin=True)
        completed, events = self.recv_until(client, lambda event: self.period(event) == "completed")
        text = "".join(
            content.get("text", "")
            for event in events
            if self.period(event) == "message"
            for content in self.agent(event).get("content", [])
        )
        self.assertEqual(text, "OK")
        self.assertNotEqual(self.agent(completed)["id"], "turn-test")

    def test_tool_schema_call_and_phone_invoke(self):
        port = self.free_port()
        self.start_gateway(
            self.config(path="/meta-flow", enabled=True, port=port, maxToolRounds=6),
            provider="yoyo_bridge",
        )
        client = ws_client(port)
        self.addCleanup(client.close)
        contexts = {
            "contexts": [{
                "header": {"name": "DeviceToolSet", "namespace": "yoyoclaw"},
                "payload": {"tool": [{
                    "name": "toggleEyeTrackTurnPage",
                    "appPkg": "com.android.settings",
                    "version": "1",
                }]},
            }]
        }
        client.send_frame(json.dumps(self.envelope("updateContexts", contexts)))
        client.send_frame(json.dumps(self.envelope("userMessage", {}, text="打开眼动翻页")))

        schema_reply, _ = self.recv_until(
            client, lambda event: self.period(event) == "toolresult"
            and self.agent(event)["id"] == "schema-call")
        self.assertIn("inputSchema", self.agent(schema_reply)["content"][0]["text"])

        tool_request, events = self.recv_until(
            client, lambda event: self.reply(event).get("msgType") == "nodeInvokeRequest")
        tool_use = next(
            event for event in events
            if self.period(event) == "tooluse"
            and self.agent(event)["id"] == "phone-call"
        )
        message = self.agent(tool_use)
        self.assertEqual(message["name"], "toggleEyeTrackTurnPage")
        self.assertEqual(json.loads(message["input"]), {"action": "open"})

        invoke = self.reply(tool_request)["data"]["node"]
        self.assertEqual(invoke["event"], "node.invoke.request")
        self.assertEqual(invoke["payload"]["command"], "toggleEyeTrackTurnPage")
        self.assertEqual(invoke["payload"]["paramsJSON"]["appPkg"], "com.android.settings")
        self.assertEqual(invoke["payload"]["paramsJSON"]["arguments"], {"action": "open"})

        result = self.envelope("nodeInvokeResult", {"node": {
            "payload": {
                "nodeId": "phone-test",
                "toolCallId": "phone-call",
                "runStatus": 200,
                "result": "ran",
            }
        }})
        client.send_frame(json.dumps(result))
        completed, events = self.recv_until(client, lambda event: self.period(event) == "completed")
        text = "".join(
            content.get("text", "")
            for event in events
            if self.period(event) == "message"
            for content in self.agent(event).get("content", [])
        )
        self.assertEqual(text, "DONE")
        self.assertTrue(any(
            self.period(event) == "toolresult"
            and self.agent(event)["id"] == "phone-call"
            for event in events
        ))

    def test_saved_tool_catalog_is_used_before_update_contexts(self):
        port = self.free_port()
        self.start_gateway(
            self.config(path="/meta-flow", enabled=True, port=port, maxToolRounds=6),
            provider="yoyo_bridge",
        )
        client = ws_client(port)
        self.addCleanup(client.close)
        client.send_frame(json.dumps(self.envelope("userMessage", {}, text="打开眼动翻页")))

        schema_reply, _ = self.recv_until(
            client, lambda event: self.period(event) == "toolresult"
            and self.agent(event)["id"] == "schema-call")
        self.assertIn("inputSchema", self.agent(schema_reply)["content"][0]["text"])
        tool_request, _ = self.recv_until(
            client, lambda event: self.reply(event).get("msgType") == "nodeInvokeRequest")
        invoke = self.reply(tool_request)["data"]["node"]
        self.assertEqual(invoke["payload"]["command"], "toggleEyeTrackTurnPage")
        self.assertEqual(invoke["payload"]["paramsJSON"]["appPkg"], "com.android.settings")

    def test_builtin_model_is_transparent_websocket_proxy(self):
        upstream = UpstreamWebSocket().start()
        self.addCleanup(upstream.close)
        port = self.free_port()
        config = self.config(
            enabled=False,
            port=port,
            upstreamWs=f"ws://127.0.0.1:{upstream.port}/upstream",
        )
        self.start_gateway(config, provider="")
        client = ws_client(port)
        self.addCleanup(client.close)
        client.send_frame('{"builtin":"hello"}')
        opcode, payload = client.recv_frame()
        self.assertEqual(opcode, 1)
        self.assertEqual(json.loads(payload), {"upstream": "ok"})
        self.assertEqual(upstream.received, ['{"builtin":"hello"}'])
        if upstream.error:
            raise upstream.error


if __name__ == "__main__":
    unittest.main(verbosity=2)
