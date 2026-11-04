package dev.yoyobridge.helper;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import org.json.JSONObject;
import dev.yoyobridge.Bridge;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Installs the model entry and app-policy overrides into YOYO.
 *
 * YOYO refreshes that list from its cloud service and calls
 * CustomModelSpHelper.saveFromFullList(). The hook appends one local SavedModel
 * after the original method finishes. No built-in model is changed. Separate
 * hooks disable the cloud app allowlist and stale local app-state checks.
 */
public final class Hook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.hihonor.magicvoice";
    private static final String CLOUD_POLICY_CLASS =
        "com.hihonor.magicvoice.settings.data.CloudWhiteBlockListManager";
    private static final String DATA_UTILS_CLASS =
        "com.hihonor.magicvoice.settings.data.MaDataUtils";
    private static final String APP_STATE_ALIGNER_CLASS =
        "com.hihonor.magicvoice.settings.data.MaAppStateManager$DefaultEnabledAligner";
    private static final String UI_AGENT_UTIL_CLASS =
        "com.hihonor.magicvoice.utils.UIAgentUtil";
    private static final String UI_AGENT_HELPER_CLASS =
        "com.hihonor.magicvoice.phonebase.service.UIAgentHelper";
    private static final String PLAN_UTIL_CLASS =
        "com.hihonor.magicvoice.phonebase.plan.util.PlanUtil";
    private static final String HELPER_CLASS =
        "com.hihonor.magicvoice.frameworkui.component.menu.modelmenu.model.CustomModelSpHelper";
    private static final String SAVED_MODEL_CLASS = HELPER_CLASS + "$SavedModel";
    private static final String PROVIDER_CODE = "yoyo_bridge";
    private static final long PACKAGE_CACHE_MS = 10000L;

    private static volatile Context cachedContext;
    private static volatile Set<String> cachedPackages;
    private static volatile long cachedPackageTime;
    private static volatile long lastPolicyLogTime;
    private static volatile ClassLoader targetClassLoader;

    @Override
    public void handleLoadPackage(LoadPackageParam loadPackageParam) {
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) return;
        targetClassLoader = loadPackageParam.classLoader;
        NativeTrace.install(loadPackageParam.classLoader);
        installEmbeddedRuntime(loadPackageParam);
        installNativeTextBridge(loadPackageParam.classLoader);
        installModelListHook(loadPackageParam.classLoader);
        installCapabilityGateHooks(loadPackageParam.classLoader);
        installAllAppPolicyHooks(loadPackageParam.classLoader);
        installWechatExecutionPolicy(loadPackageParam.classLoader);
    }

    private static final Set<Class<?>> interceptedProviders = new HashSet<>();
    private static final ThreadLocal<Object> requestProxy = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicBoolean localTaskRunning =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static boolean selectedCustom(ClassLoader loader) {
        try {
            if (runtimeConfig == null || !runtimeConfig.optBoolean("enabled")) return false;
            Class<?> modes = XposedHelpers.findClass("com.hihonor.magicvoice.phonebase.util.AgentModeUtil", loader);
            return "pro".equals(XposedHelpers.callStaticMethod(modes, "getAgentMode"))
                && "yoyo_bridge".equals(XposedHelpers.callStaticMethod(modes, "getProProviderCode"));
        } catch (Throwable ignored) { return false; }
    }

    private static void installNativeTextBridge(ClassLoader loader) {
        try {
            Class<?> proxyClass = XposedHelpers.findClass(
                "com.hihonor.magicvoice.phonebase.sdkframe.sdk.AiProviderProxy", loader);
            XposedHelpers.findAndHookMethod(proxyClass, "recognizeText", Intent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!selectedCustom(loader)) return;
                    Intent request = (Intent) param.args[0];
                    if (request == null || request.getStringExtra("text") == null) return;
                    Object provider = XposedHelpers.getObjectField(param.thisObject, "aiProvider");
                    if (provider == null) return;
                    Class<?> providerClass = provider.getClass().getMethod("recognizeText", Intent.class).getDeclaringClass();
                    synchronized (interceptedProviders) {
                        if (!interceptedProviders.contains(providerClass)) {
                            XposedHelpers.findAndHookMethod(providerClass, "recognizeText", Intent.class, new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam nativeParam) {
                                    Intent input = (Intent) nativeParam.args[0];
                                    Object proxy = requestProxy.get();
                                    if (proxy == null || input == null || !input.getBooleanExtra("yoyo_bridge_local", false)) return;
                                    nativeParam.setResult(null);
                                    String text = input.getStringExtra("text");
                                    XposedBridge.log("YOYO Bridge: intercepted native text request");
                                    if (!localTaskRunning.compareAndSet(false, true)) {
                                        showRobot(proxy, "上一个自定义模型任务仍在执行，请稍等。");
                                        return;
                                    }
                                    JSONObject config = runtimeConfig;
                                    Context context = cachedContext;
                                    String conversationKey = nativeConversationKey(loader);
                                    new Thread(() -> {
                                        try {
                                            Bridge.useLocalState(new File(context.getFilesDir(), "yoyo-lsp"));
                                            NativeToolExecutor executor = new NativeToolExecutor(context, loader);
                                            Bridge.runLocalTask(config, text, conversationKey, executor, (period, reply) -> {
                                                if (!conversationKey.isEmpty() && !conversationKey.equals(nativeConversationKey(loader))) return;
                                                if ("thinking".equals(period)) showRobot(proxy,
                                                    "正在使用自定义模型 " + config.optString("model") + " 处理任务…");
                                                else if ("message".equals(period) && !reply.isEmpty()) showRobot(proxy, reply);
                                            });
                                        } catch (Throwable error) {
                                            XposedBridge.log("YOYO Bridge: local model task error " + error.getClass().getSimpleName());
                                            if (conversationKey.isEmpty() || conversationKey.equals(nativeConversationKey(loader)))
                                                showRobot(proxy, "自定义模型桥接失败，请在 YOYO Bridge 查看日志。");
                                        } finally {
                                            localTaskRunning.set(false);
                                            report(context);
                                        }
                                    }, "yoyo-custom-local-agent").start();
                                }
                            });
                            interceptedProviders.add(providerClass);
                            XposedBridge.log("YOYO Bridge: native provider intercept ready=" + providerClass.getName());
                        }
                    }
                    request.putExtra("yoyo_bridge_local", true);
                    requestProxy.set(param.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) { requestProxy.remove(); }
            });
            XposedBridge.log("YOYO Bridge: native text bridge hook ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: native text bridge hook failed: " + error);
        }
    }

    private static String nativeConversationKey(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                "com.hihonor.magicvoice.frameworkui.topic.FwConversationTopicManager", loader);
            Object instance = XposedHelpers.callStaticMethod(manager, "getInstance");
            Object topic = XposedHelpers.callMethod(instance, "getTopicId");
            long id = topic instanceof Number ? ((Number) topic).longValue() : -1;
            // The UI creates the topic synchronously with its first input card.
            // Pending negative IDs must never share a history cache entry.
            return id > 0 ? "topic:" + id : "";
        } catch (Throwable ignored) { return ""; }
    }

    private static void showRobot(Object proxy, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                XposedHelpers.callMethod(proxy, "removeAutoLoadingCard");
                XposedHelpers.callMethod(proxy, "sendRobotMsgCard", text);
            } catch (Throwable error) {
                XposedBridge.log("YOYO Bridge: reply card failed: " + error.getClass().getSimpleName());
            }
        });
    }

    private static volatile JSONObject runtimeConfig;
    private static final Uri SETTINGS = Uri.parse("content://dev.yoyobridge.helper.settings");
    private static final java.util.concurrent.ExecutorService runtimeExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    private static void installEmbeddedRuntime(LoadPackageParam pkg) {
        try {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("ai.openclaw.android.core.network.ClawConnectManager",
                pkg.classLoader), "getClawWsUrl", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JSONObject config = runtimeConfig;
                        if (config != null && selectedCustom(pkg.classLoader))
                            param.setResult("ws://127.0.0.1:" + config.optInt("port", 38765)
                                + "/aicloud/yoyo-claw-service/v2/yoyoclaw/connect");
                    }
                });
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) param.args[0];
                    cachedContext = context;
                    NativeTrace.attach(context);
                    // URL hooks run in every process; only main process owns the socket server.
                    if (TARGET_PACKAGE.equals(pkg.processName)) {
                        BroadcastReceiver receiver = new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent intent) {
                                String action = intent.getStringExtra("action");
                                runtimeExecutor.execute(() -> reload(c, action));
                            }
                        };
                        IntentFilter filter = new IntentFilter("dev.yoyobridge.helper.APPLY");
                        if (android.os.Build.VERSION.SDK_INT >= 33)
                            context.registerReceiver(receiver, filter, "dev.yoyobridge.helper.CONTROL", null, 2 /* RECEIVER_EXPORTED */);
                        else context.registerReceiver(receiver, filter, "dev.yoyobridge.helper.CONTROL", null);
                        reload(context, "reload");
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                runtimeExecutor.execute(() -> report(context));
                                new Handler(Looper.getMainLooper()).postDelayed(this, 4000);
                            }
                        }, 4000);
                    } else readConfig(context);
                }
            });
            XposedBridge.log("YOYO Bridge: embedded runtime hook ready process=" + pkg.processName);
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: embedded runtime hook failed: " + error);
        }
    }

    private static void readConfig(Context context) {
        try {
            Bundle result = context.getContentResolver().call(SETTINGS, "load", null, null);
            runtimeConfig = new JSONObject(result.getString("config"));
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: settings provider unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void reload(Context context, String action) {
        try {
            readConfig(context);
            if (runtimeConfig == null) return;
            File dir = new File(context.getFilesDir(), "yoyo-lsp");
            Bridge.startEmbedded(dir, runtimeConfig);
            if (runtimeConfig.optBoolean("localExecution")) runtimeExecutor.execute(() -> {
                try { context.getContentResolver().call(SETTINGS, "rootStart", null, null); }
                catch (Throwable error) { XposedBridge.log("YOYO Bridge: local executor startup unavailable"); }
            });
            if ("activate".equals(action)) {
                SharedPreferences prefs = context.getSharedPreferences(TARGET_PACKAGE, Context.MODE_PRIVATE);
                SharedPreferences backup = context.getSharedPreferences("yoyo_lsp_route_backup", Context.MODE_PRIVATE);
                if (!backup.getBoolean("saved", false)) {
                    SharedPreferences.Editor b = backup.edit().putBoolean("saved", true);
                    for (String k : new String[]{"claw_agent_mode", "claw_pro_provider_code", "claw_pro_model", "claw_pro_model_name"})
                        b.putString(k, prefs.getString(k, ""));
                    b.commit();
                }
                prefs.edit().putString("claw_agent_mode", "pro")
                    .putString("claw_pro_provider_code", "yoyo_bridge")
                    .putString("claw_pro_model", "yoyo-bridge-custom")
                    .putString("claw_pro_model_name", "自定义模型").commit();
            } else if ("deactivate".equals(action)) {
                SharedPreferences prefs = context.getSharedPreferences(TARGET_PACKAGE, Context.MODE_PRIVATE);
                SharedPreferences backup = context.getSharedPreferences("yoyo_lsp_route_backup", Context.MODE_PRIVATE);
                SharedPreferences.Editor e = prefs.edit();
                for (String k : new String[]{"claw_agent_mode", "claw_pro_provider_code", "claw_pro_model", "claw_pro_model_name"})
                    e.putString(k, backup.getString(k, ""));
                e.commit();
                backup.edit().clear().commit();
            }
            report(context);
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: reload failed: " + error);
        }
    }

    private static void report(Context context) {
        try {
            Bundle bundle = new Bundle();
            bundle.putString("status", Bridge.embeddedStatus().toString());
            context.getContentResolver().call(SETTINGS, "report", null, bundle);
        } catch (Throwable ignored) {}
    }

    private static void installWechatExecutionPolicy(ClassLoader loader) {
        try {
            Class<?> agent = XposedHelpers.findClass("com.hihonor.magicvoice.business.CommonUIAgent", loader);
            Class<?> action = XposedHelpers.findClass("com.hihonor.magicvoice.business.CommonUIAgent$AppTakeOverAction", loader);
            Class<?> result = XposedHelpers.findClass("com.hihonor.magicvoice.business.CommonUIAgent$AppTakeOverResult", loader);
            final Object proceed = java.lang.Enum.valueOf((Class) action, "CONTINUE");
            XposedHelpers.findAndHookMethod(agent, "checkAppTakeOver", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!"com.tencent.mm".equals(param.args[0]) || !selectedCustom(loader)) return;
                    param.setResult(XposedHelpers.newInstance(result, proceed, null));
                    XposedBridge.log("YOYO Bridge: WeChat local app takeover policy allowed for custom task");
                }
            });
            Class<?> response = XposedHelpers.findClass("com.hihonor.magicvoice.cloud.UIAgentResponse", loader);
            XposedHelpers.findAndHookMethod(agent, "afterCloud", response, long.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object data = XposedHelpers.callMethod(param.args[0], "getData");
                        Object actions = XposedHelpers.callMethod(data, "getAction");
                        Object summary = XposedHelpers.callMethod(data, "getSummarize");
                        Object actionName = actions instanceof java.util.Map ? ((java.util.Map) actions).get("action") : "unknown";
                        boolean forbidden = String.valueOf(summary).contains("不允许操控");
                        XposedBridge.log("YOYO Bridge: GUI cloud action=" + actionName + " appForbidden=" + forbidden);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("YOYO Bridge: WeChat runtime execution policy hook ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: WeChat runtime policy hook: " + error);
        }
    }

    private static void installModelListHook(ClassLoader classLoader) {
        try {
            final Class<?> helperClass = XposedHelpers.findClass(HELPER_CLASS, classLoader);
            final Class<?> savedModelClass = XposedHelpers.findClass(SAVED_MODEL_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(helperClass, "saveFromFullList", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            appendBridgeModel(helperClass, savedModelClass);
                        } catch (Throwable error) {
                            XposedBridge.log("YOYO Bridge: model-list hook failed: " + error);
                        }
                    }
                });
            XposedBridge.log("YOYO Bridge: model-list hook ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: target hook unavailable: " + error);
        }
    }

    private static void installCapabilityGateHooks(ClassLoader classLoader) {
        try {
            final Class<?> helperClass = XposedHelpers.findClass(UI_AGENT_HELPER_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(
                helperClass,
                "isCloudGUIConfigOn",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });
            XposedBridge.log("YOYO Bridge: offline cloud-GUI gate override ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: cloud-GUI gate hook unavailable: " + error);
        }

        try {
            final Class<?> planUtilClass = XposedHelpers.findClass(PLAN_UTIL_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(
                planUtilClass,
                "isClawMode",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });
            XposedBridge.log("YOYO Bridge: YOYO Next gate override ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: YOYO Next gate hook unavailable: " + error);
        }
    }

    private static void installAllAppPolicyHooks(ClassLoader classLoader) {
        try {
            final Class<?> cloudPolicyClass = XposedHelpers.findClass(CLOUD_POLICY_CLASS, classLoader);
            final Class<?> resultClass =
                XposedHelpers.findClass(CLOUD_POLICY_CLASS + "$WhiteBlockListResult", classLoader);
            final XC_MethodHook allowAllApps = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Set<String> packages = allInstalledPackages();
                        if (packages.isEmpty()) {
                            logPolicyFailure("installed package set is empty");
                            return;
                        }
                        Object result = XposedHelpers.newInstance(
                            resultClass, packages, new HashSet<>(packages), 1);
                        param.setResult(result);
                        logPolicyReadyOnce(packages.size());
                    } catch (Throwable error) {
                        logPolicyFailure("cloud allowlist override failed: " + error);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cloudPolicyClass, "getWhiteBlockApps", allowAllApps);
            XposedHelpers.findAndHookMethod(cloudPolicyClass, "getWhiteBlockAppsFresh", allowAllApps);
            XposedBridge.log("YOYO Bridge: cloud app allowlist bypass ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: cloud app allowlist hook unavailable: " + error);
        }

        try {
            final Class<?> dataUtilsClass = XposedHelpers.findClass(DATA_UTILS_CLASS, classLoader);
            final Class<?> alignerClass = XposedHelpers.findClass(APP_STATE_ALIGNER_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(
                dataUtilsClass,
                "buildAppInfoBean",
                Context.class,
                PackageManager.class,
                ApplicationInfo.class,
                Set.class,
                boolean.class,
                alignerClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object bean = param.getResult();
                        if (bean == null) return;
                        try {
                            XposedHelpers.setObjectField(bean, "isChecked", true);
                            XposedHelpers.setObjectField(bean, "unsupported", false);
                            XposedHelpers.setObjectField(bean, "unsupportedType", 0);
                        } catch (Throwable error) {
                            logPolicyFailure("app metadata override failed: " + error);
                        }
                    }
                });
            XposedBridge.log("YOYO Bridge: app metadata override ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: app metadata hook unavailable: " + error);
        }

        try {
            final Class<?> dataUtilsClass = XposedHelpers.findClass(DATA_UTILS_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(
                dataUtilsClass,
                "isAppClosed",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                });
            XposedBridge.log("YOYO Bridge: per-app execution-state override ready");
        } catch (Throwable error) {
            XposedBridge.log("YOYO Bridge: per-app execution-state hook unavailable: " + error);
        }

        XposedBridge.log("YOYO Bridge: all-app policy override ready");
    }

    private static Set<String> allInstalledPackages() {
        long now = System.currentTimeMillis();
        Set<String> packages = cachedPackages;
        if (packages != null && now - cachedPackageTime < PACKAGE_CACHE_MS) return packages;

        Context context = currentContext();
        if (context == null) return new HashSet<>();
        HashSet<String> result = new HashSet<>();
        for (ApplicationInfo info : context.getPackageManager().getInstalledApplications(0)) {
            if (info != null && info.packageName != null && !info.packageName.isEmpty()) {
                result.add(info.packageName);
            }
        }
        if (!result.isEmpty()) {
            cachedPackages = result;
            cachedPackageTime = now;
        }
        return result;
    }

    private static Context currentContext() {
        Context context = cachedContext;
        if (context != null) return context;
        try {
            Class<?> activityThread =
                XposedHelpers.findClass("android.app.ActivityThread", null);
            Object value = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (value instanceof Context) {
                cachedContext = (Context) value;
                return cachedContext;
            }
        } catch (Throwable ignored) {
            // Fall through to YOYO's own application context.
        }
        try {
            Class<?> util = XposedHelpers.findClass(UI_AGENT_UTIL_CLASS, targetClassLoader);
            Object value = XposedHelpers.callStaticMethod(util, "getAppContext");
            if (value instanceof Context) {
                cachedContext = (Context) value;
                return cachedContext;
            }
        } catch (Throwable ignored) {
            // A later call may run after YOYO has initialized its process context.
        }
        return null;
    }

    private static void logPolicyReadyOnce(int count) {
        long now = System.currentTimeMillis();
        if (now - lastPolicyLogTime < 60000L) return;
        lastPolicyLogTime = now;
        XposedBridge.log("YOYO Bridge: cloud app allowlist bypassed, packages=" + count);
    }

    private static void logPolicyFailure(String message) {
        long now = System.currentTimeMillis();
        if (now - lastPolicyLogTime < 60000L) return;
        lastPolicyLogTime = now;
        XposedBridge.log("YOYO Bridge: " + message);
    }

    private static void appendBridgeModel(Class<?> helperClass, Class<?> savedModelClass) {
        Object currentValue = XposedHelpers.callStaticMethod(helperClass, "getConfiguredModels");
        ArrayList<Object> result = new ArrayList<>();
        if (currentValue instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) currentValue) {
                Object provider = XposedHelpers.getObjectField(item, "providerCode");
                if (!PROVIDER_CODE.equals(provider)) result.add(item);
            }
        }

        Object bridge = XposedHelpers.newInstance(savedModelClass);
        XposedHelpers.setObjectField(bridge, "providerCode", PROVIDER_CODE);
        XposedHelpers.setObjectField(bridge, "providerName", "YOYO Bridge");
        XposedHelpers.setObjectField(bridge, "modelId", "yoyo-bridge-custom");
        XposedHelpers.setObjectField(bridge, "modelName", "自定义模型");
        XposedHelpers.setObjectField(bridge, "providerMenuIcon", "");
        XposedHelpers.setObjectField(bridge, "providerMenuIconDark", "");
        XposedHelpers.setObjectField(bridge, "iconUrl", "");
        result.add(bridge);
        XposedHelpers.callStaticMethod(helperClass, "saveConfiguredModels", result);
    }
}
