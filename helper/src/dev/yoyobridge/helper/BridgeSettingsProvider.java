package dev.yoyobridge.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private configuration and status bridge for the helper and YOYO. */
public final class BridgeSettingsProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.yoyobridge.helper.settings";
    public static final String TARGET_PACKAGE = "com.hihonor.magicvoice";
    private static final String UPSTREAM_WS =
        "wss://yoyoclaw-drcn.hiboard.hihonorcloud.com/aicloud/yoyo-claw-service/v2/yoyoclaw/connect";
    private static final String CONFIG_FILE = "config.json";
    private static final String STATUS_FILE = "status.json";

    @Override public boolean onCreate() { return getContext() != null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    /** Returns a fresh config object, using the required schema defaults when absent. */
    public static JSONObject getConfig(Context context) {
        File file = new File(context.getApplicationContext().getFilesDir(), CONFIG_FILE);
        if (!file.isFile()) return defaults();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 65536)];
            int used = 0;
            while (used < data.length) {
                int count = in.read(data, used, data.length - used);
                if (count < 0) break;
                used += count;
            }
            JSONObject saved = new JSONObject(new String(data, 0, used, StandardCharsets.UTF_8));
            JSONObject result = defaults();
            for (String key : new String[]{"enabled", "scope", "providerSlot", "endpoint", "apiKey",
                    "model", "allowHttp", "stream", "port", "maxToolRounds", "upstreamWs"}) {
                if (saved.has(key)) result.put(key, saved.get(key));
            }
            return result;
        } catch (Exception ignored) {
            return defaults();
        }
    }

    /** Writes config.json through a synced temporary file and atomic rename. */
    public static void saveConfig(Context context, JSONObject config) throws IOException {
        File dir = context.getApplicationContext().getFilesDir();
        File target = new File(dir, CONFIG_FILE);
        File temporary = File.createTempFile("config-", ".tmp", dir);
        try {
            byte[] data = config.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(data);
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("load".equals(method)) {
            requireCaller();
            Bundle result = new Bundle();
            result.putString("config", getConfig(getContext()).toString());
            return result;
        }
        if ("report".equals(method)) {
            requireCaller();
            String json = extras == null ? null : extras.getString("status");
            if (json == null && extras != null) json = extras.getString("snapshot");
            if (json == null) json = arg;
            if (json == null || json.length() > 65536) throw new IllegalArgumentException("snapshot required");
            try {
                JSONObject snapshot = new JSONObject(json);
                scrubSecrets(snapshot, getConfig(getContext()).optString("apiKey", ""));
                snapshot.put("reportedAt", System.currentTimeMillis());
                writeStatus(snapshot);
                Bundle result = new Bundle();
                result.putBoolean("ok", true);
                return result;
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException("invalid snapshot");
            }
        }
        if ("status".equals(method)) {
            requireCaller();
            Bundle result = new Bundle();
            result.putString("status", readStatus().toString());
            return result;
        }
        throw new IllegalArgumentException("unsupported method");
    }

    private void requireCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String item : packages) if (TARGET_PACKAGE.equals(item)) return;
        throw new SecurityException("caller not allowed");
    }

    private void writeStatus(JSONObject value) {
        File dir = getContext().getFilesDir();
        File target = new File(dir, STATUS_FILE);
        File temporary = null;
        try {
            temporary = File.createTempFile("status-", ".tmp", dir);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(value.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("could not store status");
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    private JSONObject readStatus() {
        File file = new File(getContext().getFilesDir(), STATUS_FILE);
        if (!file.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 65536)];
            int used = 0;
            while (used < data.length) {
                int count = in.read(data, used, data.length - used);
                if (count < 0) break;
                used += count;
            }
            JSONObject value = new JSONObject(new String(data, 0, used, StandardCharsets.UTF_8));
            scrubSecrets(value, getConfig(getContext()).optString("apiKey", ""));
            return value;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void scrubSecrets(JSONObject object, String secret) throws org.json.JSONException {
        JSONArray names = object.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            if ("apiKey".equalsIgnoreCase(key) || "authorization".equalsIgnoreCase(key)) {
                object.remove(key);
            } else {
                Object value = object.opt(key);
                if (value instanceof JSONObject) scrubSecrets((JSONObject) value, secret);
                else if (value instanceof JSONArray) {
                    object.put(key, scrubSecrets((JSONArray) value, secret));
                } else if (value instanceof String && !secret.isEmpty()) {
                    object.put(key, ((String) value).replace(secret, "[已隐藏]"));
                }
            }
        }
    }

    private static JSONArray scrubSecrets(JSONArray array, String secret) throws org.json.JSONException {
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) scrubSecrets((JSONObject) value, secret);
            else if (value instanceof JSONArray) array.put(i, scrubSecrets((JSONArray) value, secret));
            else if (value instanceof String && !secret.isEmpty())
                array.put(i, ((String) value).replace(secret, "[已隐藏]"));
        }
        return array;
    }

    private static JSONObject defaults() {
        JSONObject value = new JSONObject();
        try {
            value.put("schemaVersion", 2).put("enabled", false)
                .put("scope", "custom-provider-only").put("providerSlot", "yoyo_bridge")
                .put("endpoint", "").put("apiKey", "").put("model", "")
                .put("allowHttp", false).put("stream", false).put("port", 38765)
                .put("maxToolRounds", 16).put("upstreamWs", UPSTREAM_WS);
            return value;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("could not create default config", e);
        }
    }
}
