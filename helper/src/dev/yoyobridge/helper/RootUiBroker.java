package dev.yoyobridge.helper;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small, fixed-operation UI broker intended to run as root under app_process. */
public final class RootUiBroker {
    private static final String SOCKET_NAME = "yoyo_bridge_root_ui";
    private static final int MAX_REQUEST_BYTES = 32 * 1024;
    private static final int MAX_XML_INPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_XML_OUTPUT_CHARS = 200_000;
    private static final int MAX_ATTRIBUTE_CHARS = 1_024;
    private static final int COMMAND_TIMEOUT_SECONDS = 20;
    private static final int MAX_CAPTURE_BYTES = 256 * 1024;
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z0-9_.]+");
    private static final Pattern COMPONENT = Pattern.compile("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    private final int uidA;
    private final int uidB;

    private RootUiBroker(int uidA, int uidB) {
        this.uidA = uidA;
        this.uidB = uidB;
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 || args == null || args.length != 2) return;
        int uidA = parseUid(args[0]);
        int uidB = parseUid(args[1]);
        new RootUiBroker(uidA, uidB).serve();
    }

    private static int parseUid(String value) {
        if (value == null || !value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("invalid uid");
        long uid = Long.parseLong(value);
        if (uid > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid uid");
        return (int) uid;
    }

    private void serve() throws IOException {
        try (LocalServerSocket server = new LocalServerSocket(SOCKET_NAME)) {
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    socket.setSoTimeout(COMMAND_TIMEOUT_SECONDS * 1000);
                    handle(socket);
                } catch (IOException ignored) {
                    // A malformed or disconnected client must not stop the listener.
                }
            }
        }
    }

    private void handle(LocalSocket socket) throws IOException {
        Credentials credentials;
        try {
            credentials = socket.getPeerCredentials();
        } catch (IOException e) {
            writeResponse(socket, error("unauthorized"));
            return;
        }
        int uid = credentials.getUid();
        if (uid != 0 && uid != uidA && uid != uidB) {
            writeResponse(socket, error("unauthorized"));
            return;
        }

        String line;
        try {
            line = readRequestLine(socket.getInputStream());
        } catch (IOException e) {
            writeResponse(socket, error("request too large or unreadable"));
            return;
        }
        if (line == null) {
            writeResponse(socket, error("empty request"));
            return;
        }
        JSONObject response;
        try {
            response = dispatch(uid, new JSONObject(line));
        } catch (Exception e) {
            response = error("invalid request or operation failed");
        }
        writeResponse(socket, response);
    }

    private JSONObject dispatch(int peerUid, JSONObject request) throws Exception {
        String action = request.getString("action");
        if ("status".equals(action)) {
            return new JSONObject().put("ok", true).put("uid", Process.myUid()).put("pid", Process.myPid());
        }
        if ("read".equals(action)) return readUi();
        if ("tap".equals(action)) {
            int x = coordinate(request, "x");
            int y = coordinate(request, "y");
            requireSuccess(run("input", "tap", Integer.toString(x), Integer.toString(y)));
            return success("tap");
        }
        if ("swipe".equals(action)) {
            int x1 = coordinate(request, "x1");
            int y1 = coordinate(request, "y1");
            int x2 = coordinate(request, "x2");
            int y2 = coordinate(request, "y2");
            int duration = integer(request, "duration");
            if (duration < 100 || duration > 3000) throw new IllegalArgumentException("invalid duration");
            requireSuccess(run("input", "swipe", Integer.toString(x1), Integer.toString(y1),
                    Integer.toString(x2), Integer.toString(y2), Integer.toString(duration)));
            return success("swipe");
        }
        if ("back".equals(action) || "home".equals(action)) {
            String key = "back".equals(action) ? "KEYCODE_BACK" : "KEYCODE_HOME";
            requireSuccess(run("input", "keyevent", key));
            return success(action);
        }
        if ("open_app".equals(action)) return openApp(request.getString("packageName"));
        throw new IllegalArgumentException("unknown action");
    }

    private JSONObject openApp(String packageName) throws Exception {
        if (!PACKAGE.matcher(packageName).matches()) throw new IllegalArgumentException("invalid package");
        ProcessResult resolution = run("cmd", "package", "resolve-activity", "--brief", packageName);
        if (resolution.exitCode != 0) throw new IOException("activity resolution failed");
        String component = null;
        for (String line : resolution.output.split("\\R")) {
            String candidate = line.trim();
            Matcher matcher = COMPONENT.matcher(candidate);
            if (matcher.matches() && packageName.equals(matcher.group(1))) component = candidate;
        }
        if (component == null) throw new IOException("no valid activity");
        requireSuccess(run("am", "start", "-n", component));
        return new JSONObject().put("ok", true).put("action", "open_app").put("component", component);
    }

    private JSONObject readUi() throws Exception {
        int pid = Process.myPid();
        File file = new File("/data/local/tmp/yoyo-root-ui-" + pid + ".xml");
        // Claim the fixed path atomically so another app cannot pre-place a symlink there.
        if (!file.createNewFile()) throw new IOException("UI dump path unavailable");
        try {
            ProcessResult dump = runWithTimeout(18, "uiautomator", "dump", file.getAbsolutePath());
            requireSuccess(dump);
            if (file.length() > MAX_XML_INPUT_BYTES) throw new IOException("UI dump exceeds limit");
            Document document = parseXml(file);
            Dimensions dimensions = new Dimensions();
            inspectBounds(document.getDocumentElement(), dimensions);
            StringBuilder compact = new StringBuilder();
            appendElement(document.getDocumentElement(), compact, 0);
            JSONObject response = new JSONObject().put("ok", true).put("action", "read")
                    .put("width", dimensions.width).put("height", dimensions.height)
                    .put("xml", compact.toString());
            response.put("image", captureScreen());
            return response;
        } finally {
            // The pathname is fixed and was atomically created by this process.
            file.delete();
        }
    }

    private static String captureScreen() throws Exception {
        File image = new File("/data/local/tmp/yoyo-root-screen-" + Process.myPid() + ".png");
        if (!image.createNewFile()) throw new IOException("screen path unavailable");
        android.graphics.Bitmap bitmap = null;
        try {
            requireSuccess(run("screencap", "-p", image.getAbsolutePath()));
            bitmap = android.graphics.BitmapFactory.decodeFile(image.getAbsolutePath());
            if (bitmap == null) throw new IOException("screen decode failed");
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, encoded);
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                encoded.toByteArray(), android.util.Base64.NO_WRAP);
        } finally {
            if (bitmap != null) bitmap.recycle();
            image.delete();
        }
    }

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (raw.contains("<!DOCTYPE") || raw.contains("<!ENTITY")) throw new IOException("DTD not allowed");
        factory.setExpandEntityReferences(false);
        try (InputStream input = new FileInputStream(file)) {
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) throws SAXException { throw e; }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            return builder.parse(input);
        }
    }

    private static void inspectBounds(Element element, Dimensions dimensions) {
        String value = element.getAttribute("bounds");
        Matcher matcher = BOUNDS.matcher(value);
        if (matcher.matches()) {
            dimensions.width = Math.max(dimensions.width, parsePositive(matcher.group(3)));
            dimensions.height = Math.max(dimensions.height, parsePositive(matcher.group(4)));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) inspectBounds((Element) child, dimensions);
        }
    }

    private static int parsePositive(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void appendElement(Element element, StringBuilder out, int depth) {
        if (depth > 256) return;
        String name = element.getTagName().matches("[A-Za-z_][A-Za-z0-9_.-]*") ? element.getTagName() : "node";
        StringBuilder opening = new StringBuilder().append('<').append(name);
        appendAttribute(element, opening, "class");
        appendAttribute(element, opening, "text");
        appendAttribute(element, opening, "content-desc");
        appendAttribute(element, opening, "resource-id");
        appendAttribute(element, opening, "bounds");
        appendAttribute(element, opening, "clickable");
        appendAttribute(element, opening, "scrollable");
        opening.append('>');
        String closing = "</" + name + ">";
        if (out.length() + opening.length() + closing.length() > MAX_XML_OUTPUT_CHARS) return;
        out.append(opening);
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) appendElement((Element) child, out, depth + 1);
            if (out.length() + closing.length() >= MAX_XML_OUTPUT_CHARS) break;
        }
        out.append(closing);
    }

    private static void appendAttribute(Element element, StringBuilder out, String name) {
        if (!element.hasAttribute(name)) return;
        String value = element.getAttribute(name);
        if (value.length() > MAX_ATTRIBUTE_CHARS) value = value.substring(0, MAX_ATTRIBUTE_CHARS);
        out.append(' ').append(name).append("=\"")
                .append(escapeXml(value)).append('"');
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class Dimensions {
        int width;
        int height;
    }

    private static int coordinate(JSONObject request, String name) throws Exception {
        int value = integer(request, name);
        if (value < 0 || value > 10000) throw new IllegalArgumentException("invalid coordinate");
        return value;
    }

    private static int integer(JSONObject request, String name) throws Exception {
        Object value = request.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer required");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("integer out of range");
        return (int) number;
    }

    private static String readRequestLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (bytes.size() >= MAX_REQUEST_BYTES) throw new IOException("request too large");
            bytes.write(value);
        }
        if (value == -1 && bytes.size() == 0) return null;
        byte[] data = bytes.toByteArray();
        int length = data.length;
        if (length > 0 && data[length - 1] == '\r') length--;
        return new String(data, 0, length, StandardCharsets.UTF_8);
    }

    private static ProcessResult run(String... command) throws Exception {
        return runWithTimeout(COMMAND_TIMEOUT_SECONDS, command);
    }

    private static ProcessResult runWithTimeout(int timeoutSeconds, String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try (InputStream stream = process.getInputStream()) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (captured) {
                        int keep = Math.min(count, MAX_CAPTURE_BYTES - captured.size());
                        if (keep > 0) captured.write(buffer, 0, keep);
                        if (keep < count) truncated[0] = true;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "root-ui-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new IOException("command timeout");
        }
        reader.join(1000);
        if (reader.isAlive()) throw new IOException("command output timeout");
        if (truncated[0]) throw new IOException("command output exceeds limit");
        String output;
        synchronized (captured) {
            output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private static void requireSuccess(ProcessResult result) throws IOException {
        if (result.exitCode != 0) throw new IOException("command failed");
    }

    private static JSONObject success(String action) throws Exception {
        return new JSONObject().put("ok", true).put("action", action);
    }

    private static JSONObject error(String message) throws IOException {
        try {
            return new JSONObject().put("ok", false).put("error", message);
        } catch (Exception e) {
            throw new IOException("response encoding failed", e);
        }
    }

    private static void writeResponse(LocalSocket socket, JSONObject response) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
