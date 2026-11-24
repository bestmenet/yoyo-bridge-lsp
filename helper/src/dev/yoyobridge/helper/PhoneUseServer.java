package dev.yoyobridge.helper;

import android.content.Context;
import dev.yoyobridge.Bridge;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authenticated loopback endpoint inside YOYO. The CLI owns the LAN listener. */
final class PhoneUseServer implements AutoCloseable {
    static final int PORT = 38766;
    private static final int NONCE_BYTES = 32;
    private static final int MAX_REQUEST = 64 * 1024;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Semaphore TOOL_SLOT = new Semaphore(1);
    private static final String TARGET = BridgeSettingsProvider.TARGET_PACKAGE;

    private final NativeToolExecutor executor;
    private final byte[] token;
    private final JSONArray catalog;
    private final ServerSocket listener;
    private final Set<Socket> clients = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> daemon(task, "yobg-client"));

    PhoneUseServer(Context context, ClassLoader loader, String token) throws IOException {
        if (!isToken(token)) throw new IllegalArgumentException("Invalid phone-use token");
        this.token = token.getBytes(StandardCharsets.US_ASCII);
        executor = new NativeToolExecutor(context, loader);
        catalog = loadCatalog(new File(context.getFilesDir(), "mcp_tools.json"));
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress("127.0.0.1", PORT), 8);
            daemon(this::accept, "yobg-loopback").start();
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    static boolean isToken(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    boolean usesToken(String value) {
        return isActive() && MessageDigest.isEqual(
            token, value.getBytes(StandardCharsets.US_ASCII));
    }

    boolean isActive() {
        return !listener.isClosed();
    }

    private void accept() {
        try {
            while (!listener.isClosed()) {
                Socket socket = listener.accept();
                clients.add(socket);
                try {
                    workers.execute(() -> handle(socket));
                } catch (RejectedExecutionException busy) {
                    clients.remove(socket);
                    closeSocket(socket);
                }
            }
        } catch (IOException closed) {
            close();
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(4000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] serverNonce = new byte[NONCE_BYTES];
            byte[] clientNonce = new byte[NONCE_BYTES];
            byte[] proof = new byte[NONCE_BYTES];
            RANDOM.nextBytes(serverNonce);
            output.write(serverNonce);
            output.flush();
            input.readFully(clientNonce);
            input.readFully(proof);
            if (!MessageDigest.isEqual(proof, sign("client", serverNonce, clientNonce))) return;
            output.write(sign("server", serverNonce, clientNonce));
            output.flush();

            int length = input.readInt();
            if (length < 1 || length > MAX_REQUEST) return;
            byte[] body = new byte[length];
            input.readFully(body);
            if (listener.isClosed()) return;
            JSONObject response = dispatch(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RESPONSE)
                bytes = failure("YOYO response exceeds the size limit").toString().getBytes(StandardCharsets.UTF_8);
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        } catch (Exception ignored) {
            // Pairing secrets, tool arguments, and results stay out of the log.
        } finally {
            clients.remove(client);
        }
    }

    private JSONObject dispatch(JSONObject request) throws org.json.JSONException {
        String operation = request.optString("op");
        if ("list".equals(operation)) return new JSONObject()
            .put("phoneUse", Bridge.phoneUseToolDefinitions()).put("catalog", catalog);
        if (!"call".equals(operation)) return failure("Unknown operation");

        String name = request.optString("name");
        JSONObject args = request.optJSONObject("arguments");
        if (args == null) return failure("Tool arguments must be an object");
        boolean nativeMcp = request.has("appPkg");
        String app = nativeMcp ? request.optString("appPkg") : TARGET;
        if (nativeMcp) {
            if (!catalogContains(app, name)) return failure("Native tool is not in the YOYO catalog");
        } else if ("gui.create".equals(name)) {
            if (!validStrings(args, "app_name", "task_query")) return failure("app_name and task_query are required");
        } else if ("task-result-query".equals(name)) {
            if (!validStrings(args, "task_id")) return failure("task_id is required");
        } else {
            return failure("Unknown phone-use tool");
        }

        if (!TOOL_SLOT.tryAcquire()) return failure("YOYO is handling another MCP tool call");
        try {
            if (listener.isClosed()) return failure("Phone use is disabled");
            if (nativeMcp) return executor.invokeMcp(name, app, args);
            return executor.invoke(name, app, args);
        } catch (Exception | LinkageError error) {
            return failure("YOYO native tool is unavailable");
        } finally {
            TOOL_SLOT.release();
        }
    }

    private static boolean validStrings(JSONObject args, String... keys) {
        if (args.length() != keys.length) return false;
        for (String key : keys) {
            Object value = args.opt(key);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) return false;
        }
        return true;
    }

    private boolean catalogContains(String app, String name) {
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.optJSONObject(i);
            if (name.equals(item.optString("name")) && app.equals(item.optString("appPkg"))) return true;
        }
        return false;
    }

    private static JSONArray loadCatalog(File file) {
        JSONArray catalog = new JSONArray();
        if (!file.isFile() || file.length() > MAX_RESPONSE / 2) return catalog;
        try {
            JSONArray tools = new JSONObject(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8)).optJSONArray("tools");
            if (tools == null) return catalog;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject item = tools.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name");
                JSONObject schema = item.optJSONObject("inputSchema");
                if (name.isEmpty() || schema == null || !"object".equals(schema.optString("type"))) continue;
                catalog.put(new JSONObject().put("name", name).put("appPkg", item.optString("appPkg"))
                    .put("description", item.optString("description")).put("inputSchema", schema));
            }
        } catch (Exception ignored) {
            return new JSONArray();
        }
        return catalog;
    }

    private byte[] sign(String role, byte[] serverNonce, byte[] clientNonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        mac.update(("yobg/1:" + role).getBytes(StandardCharsets.US_ASCII));
        mac.update(serverNonce);
        return mac.doFinal(clientNonce);
    }

    private static JSONObject failure(String message) throws org.json.JSONException {
        return new JSONObject().put("runStatus", 500).put("result", message);
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket client : clients) closeSocket(client);
        // A native action already submitted to YOYO may continue after the socket closes.
        workers.shutdown();
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}
