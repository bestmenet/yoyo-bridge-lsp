package dev.yoyobridge.helper;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Passive opt-in observation. Never changes arguments, results or routing. */
public final class NativeTrace {
    private static volatile File dir;
    private static ClassLoader loader;
    private static final ThreadLocal<Boolean> writing = new ThreadLocal<>();
    public static void attach(Context context) { dir = new File(context.getFilesDir(), "yoyo-lsp"); }
    private static boolean enabled() {
        File d = dir;
        return d != null && new File(d, "native-trace.enabled").isFile();
    }
    public static void install(ClassLoader cl) {
        loader = cl;
        observe("com.hihonor.magicvoice.phonebase.sdkframe.sdk.AiProviderProxy", "recognizeText", "sendRobotMsgCard");
        observe("com.hihonor.magicvoice.phonebase.sdkframe.sdk.AiProviderProxy$2", "onPartialResult", "onResult", "onUiResult", "onNluResult", "onError");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.VoiceControlService", "recognizeText");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.VoiceControlService$KitBaseRecognizeListener", "onPartialResult", "onResult", "onNluResult", "onError");
        observe("com.hihonor.magicvoice.phonebase.plan.PlanManager", "addKitMsg", "uploadEvent");
        observe("com.hihonor.magicvoice.phoneaction.tool.McpNodeTool", "execute");
        observe("com.hihonor.magicvoice.phoneaction.tool.ActionGroupNodeTool", "execute");
        observe("com.hihonor.magicvoice.frameworkui.component.content.FwContentUiMsgProcessor", "handleLLMStreamOut");
        observe("com.hihonor.magicvoice.frameworkui.component.content.presenter.FwBaseContentPresenter", "processDialogItemAddEvent");
        observe("com.hihonor.magicvoice.yoyotask.shared.chat.WorkFlowMainChatManager", "schedule", "onMessageReceived", "onMessageSent", "handleClientDirectiveEvent", "uploadMsgToMainChat");
        observe("com.hihonor.magicvoice.yoyotask.core.network.websocket.WebSocketClient", "sendMessage", "notifyMessageReceived");
        observe("com.hihonor.magicvoice.yoyotask.shared.workflow.WorkFlowRecordManager", "addOrUpdateWorkFlowRecord");
        observe("com.hihonor.magicvoice.yoyotask.shared.chat.WorkFlowChatHelper", "payLoadToDsl", "handlePayLoadMessage");
        observe("com.hihonor.magicvoice.yoyotask.shared.workflow.WorkFlowUseCase", "addOrUpdateWorkFlow", "deleteWorkFlows", "getWorkFlowListStrictly", "workFlowTrialOperation");
        observe("com.hihonor.magicvoice.yoyotask.core.common.util.MCPClientManager", "executeToolAsync");
        observe("com.hihonor.magicvoice.phonebase.storage.VoiceSession", "setDialogId", "setSessionId", "setCheckId");
        logHooks("com.hihonor.magicvoice.common.log.MvLog");
        logHooks("com.hihonor.voiceaccess.utils.log.RunLog");
        logHooks("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.base.utils.IALog");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.http.HttpRequestImpl",
            "doPostEventStringAsync", "doPostEventJsonAsync", "doPostEventTextAsync");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.http.websocket.WebSocketClient", "send");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.http.websocket.WebSocketClient$1", "onMessage");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.CloudAbilityProxy", "startNluAnalyze");
        observe("com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.recognizer.HonorVoiceWsClient", "sendDataWhenOpen", "dealOnMessage");
        observe("com.hihonor.magicvoice.business.WorkFlowActionGroup", "startWorkCreateForMain");
    }
    private static void observe(String name, String... names) {
        try {
            Class<?> c = XposedHelpers.findClass(name, loader);
            for (Method m : c.getDeclaredMethods()) for (String n : names) if (m.getName().equals(n)) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled()) return;
                        if (n.equals("send") && (p.args.length != 1 || !(p.args[0] instanceof String))) return;
                        if (n.equals("onMessage") && (p.args.length < 2 || !(p.args[1] instanceof String))) return;
                        try {
                            JSONArray args = new JSONArray();
                            for (Object v : p.args) args.put(value(v));
                            record("call", name + "." + n, args);
                            if (n.equals("recognizeText") && p.thisObject != null) {
                                Object provider = XposedHelpers.getObjectField(p.thisObject, "aiProvider");
                                if (provider != null) record("provider", name, provider.getClass().getName());
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable e) { XposedBridge.log("YOYO Trace unavailable: " + name + " " + e.getClass().getSimpleName()); }
    }
    private static void logHooks(String name) {
        try {
            Class<?> c = XposedHelpers.findClass(name, loader);
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!(n.equals("debug") || n.equals("debugmulti") || n.equals("devdebugged") || n.equals("info") || n.equals("d") || n.equals("i"))) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled() || Boolean.TRUE.equals(writing.get())) return;
                        try {
                            if (p.args.length < 2 || !(p.args[0] instanceof String) || !(p.args[1] instanceof String)) return;
                            String tag = (String)p.args[0], msg = (String)p.args[1];
                            if (!relevantLog(tag)) return;
                            if (p.args.length > 2 && p.args[2] instanceof Object[]) {
                                try { msg = String.format(Locale.ROOT, msg, (Object[])p.args[2]); } catch (Throwable ignored) {}
                            }
                            record("log", tag, msg);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable e) { XposedBridge.log("YOYO Trace logger unavailable: " + name); }
    }
    private static Object value(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Intent) return value(((Intent)v).getExtras());
        if (v instanceof Context) return v.getClass().getName();
        if (v.getClass().getName().startsWith("okhttp3.") || v.getClass().getName().startsWith("okio.")) return v.getClass().getName();
        if (v instanceof Bundle) {
            JSONObject o = new JSONObject();
            for (String k : ((Bundle)v).keySet()) try {
                o.put(k, secret(k) ? "[REDACTED]" : value(((Bundle)v).get(k)));
            } catch (Throwable ignored) {}
            return o;
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        try {
            Class<?> gson = XposedHelpers.findClass("com.hihonor.magicvoice.phonebase.util.GsonUtils", loader);
            return XposedHelpers.callStaticMethod(gson, "toJson", v);
        } catch (Throwable e) { return v.getClass().getName() + ":" + String.valueOf(v); }
    }
    private static boolean secret(String k) {
        return k.toLowerCase(Locale.ROOT).matches(".*(token|authorization|cookie|password|apikey|api_key|secret).*" );
    }
    private static boolean relevantLog(String tag) {
        String key = tag.toLowerCase(Locale.ROOT);
        for (String part : new String[]{"provider", "recogniz", "http", "socket", "plan", "tool", "mcp", "directive", "stream", "context", "session", "workflow", "tasktrial", "carddisplay"}) {
            if (key.contains(part)) return true;
        }
        return false;
    }
    private static synchronized void record(String kind, String point, Object payload) {
        if (Boolean.TRUE.equals(writing.get())) return;
        writing.set(true);
        try {
            dir.mkdirs();
            File out = new File(dir, "native-trace-" + Process.myPid() + ".private.jsonl");
            if (out.length() > 16L * 1024 * 1024) {
                File old = new File(out.getPath() + ".1");
                if (old.exists() && !old.delete()) return;
                if (!out.renameTo(old)) return;
            }
            JSONObject row = new JSONObject().put("timeMs", System.currentTimeMillis())
                .put("elapsedNs", android.os.SystemClock.elapsedRealtimeNanos())
                .put("pid", Process.myPid()).put("thread", Thread.currentThread().getName())
                .put("kind", kind).put("point", point).put("payload", payload);
            try (FileOutputStream stream = new FileOutputStream(out, true)) {
                out.setReadable(false, false); out.setWritable(false, false);
                out.setReadable(true, true); out.setWritable(true, true);
                stream.write((row.toString() + "\n").getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {} finally { writing.remove(); }
    }
}
