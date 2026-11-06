package dev.yoyobridge.helper;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** Learns public plugin metadata from a trusted native workflow response. */
public final class NativeWorkflowCapabilities {
    private static final String[] EXECUTION_KEYS = {
        "pluginType", "pluginId", "pkgName", "version", "toolVersion", "runMode",
        "callbackMode", "lockScreenEnable", "underageControl", "permissions",
        "filterDisplayCard", "filterDisplayText", "needContentBuffer", "certFingerprint"
    };
    private NativeWorkflowCapabilities() {}

    public static synchronized boolean capture(Context context, String commonPayload) throws Exception {
        JSONObject tools = extractTools(new JSONObject(commonPayload));
        if (tools == null) return false;
        return save(context, tools);
    }

    static JSONObject extractTools(JSONObject root) throws Exception {
        if (root.optJSONObject("payload") != null) root = root.getJSONObject("payload");
        JSONObject data = root.optJSONObject("data");
        JSONObject event = data == null ? null : data.optJSONObject("event");
        JSONObject header = event == null ? null : event.optJSONObject("header");
        if (header == null || !"Tool.yoyoTask".equals(header.optString("namespace"))
                || !"create_trigger_task".equals(header.optString("name"))) return null;
        JSONObject payload = event.optJSONObject("payload");
        JSONArray tasks = payload == null ? null : payload.optJSONArray("tasks");
        JSONObject tools = new JSONObject();
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null || !"create_trigger_task".equals(task.optString("tool"))) continue;
            putTool(tools, task);
            JSONObject parameters = task.optJSONObject("parameters");
            if (parameters == null) continue;
            for (String kind : new String[]{"triggers", "actions"}) {
                JSONArray list = parameters.optJSONArray(kind);
                if (list != null) for (int j = 0; j < list.length(); j++) {
                    JSONObject item = list.optJSONObject(j);
                    if (item != null) putTool(tools, item);
                }
            }
        }
        for (String name : new String[]{"create_trigger_task", "time_fence", "gui_task_create"})
            if (!tools.has(name)) return null;
        return tools;
    }

    private static boolean save(Context context, JSONObject tools) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo("com.hihonor.magicvoice", 0);
        JSONObject saved = new JSONObject().put("schemaVersion", 1)
            .put("sourcePackage", "com.hihonor.magicvoice")
            .put("sourceVersionName", info.versionName).put("sourceVersionCode", info.versionCode)
            .put("sourceLastUpdateTime", info.lastUpdateTime)
            .put("capturedAt", System.currentTimeMillis()).put("tools", tools);
        File dir = new File(context.getFilesDir(), "yoyo-lsp");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("state unavailable");
        File target = new File(dir, "native-workflow-capabilities.json");
        File temporary = File.createTempFile("workflow-capabilities-", ".tmp", dir);
        try {
            temporary.setReadable(false, false); temporary.setWritable(false, false);
            temporary.setReadable(true, true); temporary.setWritable(true, true);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(saved.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
        return true;
    }

    private static void putTool(JSONObject tools, JSONObject source) throws Exception {
        String name = source.optString("tool");
        if (!"create_trigger_task".equals(name) && !"time_fence".equals(name)
                && !"gui_task_create".equals(name)) return;
        JSONObject original = source.optJSONObject("executeParams");
        if (source.optString("toolId").isEmpty() || original == null
                || original.optString("pluginId").isEmpty() || !original.has("version")
                || original.optString("pkgName").trim().isEmpty()) return;
        JSONObject deps = original.optJSONObject("toolDeps");
        if (original.has("toolDeps") && (deps == null || deps.length() != 0)) return;
        JSONObject execution = new JSONObject();
        for (String key : EXECUTION_KEYS) if (original.has(key)) execution.put(key, original.get(key));
        execution.put("toolDeps", new JSONObject());
        JSONArray env = original.optJSONArray("pluginEnv");
        if (env != null) {
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < env.length(); i++) {
                JSONObject item = env.optJSONObject(i);
                if (item != null && ("name".equals(item.optString("key"))
                        || "namespace".equals(item.optString("key"))))
                    filtered.put(new JSONObject().put("key", item.getString("key"))
                        .put("value", item.optString("value")));
            }
            execution.put("pluginEnv", filtered);
        }
        JSONObject tool = new JSONObject().put("toolId", source.getString("toolId"))
            .put("executeParams", execution);
        if ("gui_task_create".equals(name)) {
            JSONObject call = source.optJSONObject("mcpCall");
            if (call == null || !"gui_task_create".equals(call.optString("name"))) return;
            JSONObject arguments = call.optJSONObject("arguments"), staticArguments = new JSONObject();
            for (String key : new String[]{"taskMode", "enableRewrite", "showUser", "business_id", "showResult"})
                if (arguments != null && arguments.has(key)) staticArguments.put(key, arguments.get(key));
            tool.put("mcpCall", new JSONObject().put("name", call.getString("name"))
                .put("appPkg", call.optString("appPkg")).put("arguments", staticArguments));
        }
        tools.put(name, tool);
    }
}
