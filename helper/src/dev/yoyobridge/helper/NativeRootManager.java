package dev.yoyobridge.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Starts and checks the private root UI broker from the helper app process. */
public final class NativeRootManager {
    private static final String YOYO_PACKAGE = "com.hihonor.magicvoice";
    private static final String BROKER_CLASS = "dev.yoyobridge.helper.RootUiBroker";
    private static final String PROCESS_NAME = "yoyo-bridge-local-ui";
    private static final long SU_TIMEOUT_SECONDS = 5L;
    private static final long STARTUP_TIMEOUT_MS = 4000L;
    private static final int MAX_CAPTURE_BYTES = 4096;

    private NativeRootManager() {}

    public static JSONObject start(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context is required");

        JSONObject status = readStatus();
        if (status != null) {
            if (status.optBoolean("ok", false)) {
                return status.put("message", "Root UI broker is already running");
            }
            return status;
        }

        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;

        try {
            ApplicationInfo yoyo = appContext.getPackageManager()
                .getApplicationInfo(YOYO_PACKAGE, 0);
            int yoyoUid = yoyo.uid;
            int helperUid = android.os.Process.myUid();
            String apkPath = appContext.getPackageCodePath();
            File filesDir = appContext.getFilesDir();
            if (apkPath == null || filesDir == null) return failure("Helper app paths are unavailable");

            File logFile = new File(filesDir, "root-ui.log");
            String command = "CLASSPATH=" + shellQuote(apkPath)
                + " /system/bin/app_process /system/bin --nice-name=" + PROCESS_NAME
                + " " + BROKER_CLASS
                + " " + yoyoUid + " " + helperUid
                + " >" + shellQuote(logFile.getAbsolutePath())
                + " 2>&1 </dev/null &";

            JSONObject launch = runSu(command);
            if (!launch.optBoolean("ok", false)) return launch;

            long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                status = readStatus();
                if (status != null) {
                    if (status.optBoolean("ok", false)) {
                        return status.put("message", "Root UI broker started");
                    }
                    return status;
                }
                Thread.sleep(200L);
            }
            return failure("Root UI broker did not become ready");
        } catch (Exception ignored) {
            return failure("Unable to start the root UI broker");
        }
    }

    private static JSONObject readStatus() {
        try {
            JSONObject status = NativeRootClient.status();
            if (status.optBoolean("ok", false)
                    && status.optInt("uid", -1) == 0
                    && status.optInt("pid", -1) > 0) {
                return status;
            }
            return failure("The root UI socket is occupied by an untrusted process");
        } catch (NativeRootClient.BrokerUnavailableException unavailable) {
            return null;
        } catch (Exception ignored) {
            return failure("The root UI socket returned an invalid status response");
        }
    }

    private static JSONObject runSu(String command) {
        java.lang.Process process = null;
        Thread stdout = null;
        Thread stderr = null;
        try {
            process = Runtime.getRuntime().exec(new String[] { "su", "-c", command });
            stdout = drainBounded(process.getInputStream());
            stderr = drainBounded(process.getErrorStream());
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                joinQuietly(stdout);
                joinQuietly(stderr);
                return failure("Root launch command timed out");
            }
            joinQuietly(stdout);
            joinQuietly(stderr);
            if (process.exitValue() != 0) return failure("Root launch command failed");
            return new JSONObject().put("ok", true).put("message", "Root launch command accepted");
        } catch (Exception ignored) {
            if (process != null) process.destroy();
            joinQuietly(stdout);
            joinQuietly(stderr);
            return failure("Unable to execute the root launch command");
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private static Thread drainBounded(final InputStream input) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buffer = new byte[512];
                byte[] captured = new byte[MAX_CAPTURE_BYTES];
                int capturedBytes = 0;
                try {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        int retained = Math.min(count, captured.length - capturedBytes);
                        if (retained > 0) {
                            System.arraycopy(buffer, 0, captured, capturedBytes, retained);
                            capturedBytes += retained;
                        }
                    }
                } catch (IOException ignored) {
                    // Output is intentionally not surfaced to callers.
                }
            }
        }, "root-ui-su-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(250L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static JSONObject failure(String message) {
        return new JSONObject().put("ok", false).put("message", message);
    }
}
