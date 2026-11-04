package dev.yoyobridge.helper;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Client for the private, abstract-namespace root UI broker socket. */
public final class NativeRootClient {
    private static final String SOCKET_NAME = "yoyo_bridge_root_ui";
    private static final int CALL_TIMEOUT_MS = 30000;
    private static final int STATUS_TIMEOUT_MS = 500;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private NativeRootClient() {}

    public static JSONObject call(JSONObject request) throws Exception {
        return call(request, CALL_TIMEOUT_MS);
    }

    public static boolean isRunning() {
        try {
            JSONObject status = status();
            return status.optBoolean("ok", false)
                && status.optInt("uid", -1) == 0
                && status.optInt("pid", -1) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static JSONObject status() throws Exception {
        return call(new JSONObject().put("action", "status"), STATUS_TIMEOUT_MS);
    }

    private static JSONObject call(JSONObject request, int timeoutMs) throws Exception {
        if (request == null) throw new IllegalArgumentException("request is required");

        LocalSocket socket = new LocalSocket();
        try {
            try {
                socket.connect(new LocalSocketAddress(
                    SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            } catch (IOException unavailable) {
                throw new BrokerUnavailableException();
            }
            if (socket.getPeerCredentials().getUid() != 0) throw new IOException("Broker peer is not root");
            socket.setSoTimeout(timeoutMs);

            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();

            byte[] response = readLine(new java.io.BufferedInputStream(socket.getInputStream(), 16384));
            return new JSONObject(new String(response, StandardCharsets.UTF_8));
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Preserve the request or response failure, if any.
            }
        }
    }

    static final class BrokerUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static byte[] readLine(InputStream input) throws IOException {
        byte[] response = new byte[MAX_RESPONSE_BYTES];
        int used = 0;
        while (true) {
            int value = input.read();
            if (value < 0) throw new IOException("Broker closed before completing its response");
            if (value == '\n') {
                byte[] line = new byte[used];
                System.arraycopy(response, 0, line, 0, used);
                return line;
            }
            if (used == MAX_RESPONSE_BYTES) {
                throw new IOException("Broker response exceeds the size limit");
            }
            response[used++] = (byte) value;
        }
    }
}
