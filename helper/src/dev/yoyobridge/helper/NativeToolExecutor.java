package dev.yoyobridge.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.yoyobridge.Bridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.json.JSONObject;

/** Invokes YOYO's native tools from the injected YOYO process without linking to YOYO classes. */
public final class NativeToolExecutor implements Bridge.LocalToolInvoker {
    private static final long WAIT_SECONDS = 60L;
    private static final String GUI_HANDLER = "ai.openclaw.android.node.gui.GuiHandler";
    private static final String TASK_QUERY_HANDLER =
        "ai.openclaw.android.node.handler.TaskResultQueryHandler";
    private static final String INVOKE_RESULT = "ai.openclaw.android.gateway.GatewaySession$InvokeResult";
    private static final String NODE_TOOL_CALLBACK =
        "com.hihonor.magicvoice.node.core.NodeToolCallback";
    private static final String CONTINUATION = "kotlin.coroutines.Continuation";

    private final Context context;
    private final ClassLoader loader;
    private final Handler mainHandler;
    private volatile Object guiHandler;

    public NativeToolExecutor(Context context, ClassLoader loader) {
        if (context == null || loader == null) {
            throw new IllegalArgumentException("context and loader are required");
        }
        this.context = context;
        this.loader = loader;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public JSONObject invoke(String name, String appPkg, JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        if ("local.ui".equals(name)) {
            JSONObject value = NativeRootClient.call(safeArgs);
            return response(value.optBoolean("ok") ? 200 : 500, value.toString());
        }
        if ("gui.create".equals(name)) {
            return invokeGuiCreate(safeArgs);
        }
        if ("task-result-query".equals(name)) {
            return invokeTaskResultQuery(safeArgs);
        }
        return invokeMcp(name, appPkg, safeArgs);
    }

    private JSONObject invokeGuiCreate(final JSONObject args) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return error("MAIN_THREAD", "gui.create must be invoked off the main thread");
        }
        final AtomicReference<Object> result = new AtomicReference<Object>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object handler = guiHandler;
                    if (handler == null) {
                        synchronized (NativeToolExecutor.this) {
                            handler = guiHandler;
                            if (handler == null) {
                                Class<?> type = load(GUI_HANDLER);
                                Constructor<?> constructor = type.getConstructor(Context.class);
                                handler = constructor.newInstance(context);
                                guiHandler = handler;
                            }
                        }
                    }
                    Method method = handler.getClass().getMethod(
                        "handleGuiCreate", String.class, String.class, String.class, String.class);
                    result.set(method.invoke(handler, args.toString(), "local-yoyo-bridge",
                        UUID.randomUUID().toString(), UUID.randomUUID().toString()));
                } catch (Throwable t) {
                    failure.set(unwrap(t));
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!posted) {
            return error("DISPATCH_FAILED", "Unable to post gui.create to the main thread");
        }
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            return error("TIMEOUT", "gui.create timed out");
        }
        if (failure.get() != null) {
            return error("GUI_ERROR", message(failure.get()));
        }
        return invokeResult(result.get());
    }

    private JSONObject invokeTaskResultQuery(final JSONObject args) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Object> resumed = new AtomicReference<Object>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Class<?> continuationType = load(CONTINUATION);
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[] { continuationType },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] values) throws Throwable {
                    String methodName = method.getName();
                    if ("getContext".equals(methodName)) {
                        Class<?> empty = load("kotlin.coroutines.EmptyCoroutineContext");
                        return empty.getField("INSTANCE").get(null);
                    }
                    if ("resumeWith".equals(methodName)) {
                        Object value = values == null || values.length == 0 ? null : values[0];
                        try {
                            Class<?> resultKt = load("kotlin.ResultKt");
                            resultKt.getMethod("throwOnFailure", Object.class).invoke(null, value);
                            resumed.set(value);
                        } catch (Throwable t) {
                            failure.set(unwrap(t));
                        } finally {
                            latch.countDown();
                        }
                        return null;
                    }
                    if ("toString".equals(methodName)) return "NativeToolExecutor.Continuation";
                    if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
                    if ("equals".equals(methodName)) return proxy == values[0];
                    return null;
                }
            });

        Class<?> handlerType = load(TASK_QUERY_HANDLER);
        Object handler = handlerType.getConstructor().newInstance();
        Object returned;
        try {
            returned = handlerType.getMethod("handleTaskResultQuery", String.class, continuationType)
                .invoke(handler, args.toString(), continuation);
        } catch (Throwable t) {
            return error("TASK_QUERY_ERROR", message(unwrap(t)));
        }
        if (!isCoroutineSuspended(returned)) {
            return invokeResult(returned);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return error("MAIN_THREAD", "Suspended task-result-query cannot wait on the main thread");
        }
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            return error("TIMEOUT", "task-result-query timed out");
        }
        if (failure.get() != null) {
            return error("TASK_QUERY_ERROR", message(failure.get()));
        }
        return invokeResult(resumed.get());
    }

    private JSONObject invokeMcp(final String name, String appPkg, final JSONObject args)
            throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return error("MAIN_THREAD", "MCP tool invocation must run off the main thread");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JSONObject> result = new AtomicReference<JSONObject>();
        final String toolCallId = UUID.randomUUID().toString();
        Class<?> callbackType = load(NODE_TOOL_CALLBACK);
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[] { callbackType },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] values) throws Throwable {
                    String methodName = method.getName();
                    if ("onComplete".equals(methodName)) {
                        Object nodeResult = values == null ? null : values[0];
                        int status = ((Number) nodeResult.getClass().getMethod("getRunStatus")
                            .invoke(nodeResult)).intValue();
                        Object payload = nodeResult.getClass().getMethod("getPayload").invoke(nodeResult);
                        result.set(response(status == 200 ? 200 : 500,
                            payload == null ? "" : String.valueOf(payload)));
                        latch.countDown();
                    } else if ("onError".equals(methodName)) {
                        Object nodeError = values == null ? null : values[0];
                        result.set(error(readString(nodeError, "getCode", "MCP_ERROR"),
                            readString(nodeError, "getMessage", "MCP tool failed")));
                        latch.countDown();
                    }
                    return null;
                }
            });

        JSONObject params = new JSONObject()
            .put("name", name)
            .put("appPkg", appPkg == null ? "" : appPkg)
            .put("arguments", args)
            .put("executeParams", args);
        Class<?> toolType = load("com.hihonor.magicvoice.phoneaction.tool.McpNodeTool");
        Object tool = toolType.getConstructor(String.class, String.class, String.class, Map.class)
            .newInstance(name, appPkg == null ? "" : appPkg, "", new HashMap<Object, Object>());
        Class<?> invokeContextType = load("com.hihonor.magicvoice.node.core.NodeInvokeContext");
        toolType.getMethod("execute", String.class, String.class, callbackType, invokeContextType)
            .invoke(tool, params.toString(), toolCallId, callback, null);
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            return error("TIMEOUT", "MCP tool invocation timed out");
        }
        JSONObject completed = result.get();
        return completed == null ? error("MCP_ERROR", "MCP tool returned no result") : completed;
    }

    private JSONObject invokeResult(Object value) throws Exception {
        if (value == null) return error("EMPTY_RESULT", "Native handler returned no result");
        boolean ok = ((Boolean) value.getClass().getMethod("getOk").invoke(value)).booleanValue();
        Object payload = value.getClass().getMethod("getPayloadJson").invoke(value);
        if (ok) return response(200, payload == null ? "" : String.valueOf(payload));
        Object error = value.getClass().getMethod("getError").invoke(value);
        return error(readString(error, "getCode", "NATIVE_ERROR"),
            readString(error, "getMessage", "Native handler failed"));
    }

    private JSONObject error(String code, String message) throws JSONException {
        return response(500, new JSONObject().put("errorCode", code)
            .put("message", message == null ? "Unknown error" : message).toString());
    }

    private JSONObject response(int status, String value) throws JSONException {
        return new JSONObject().put("runStatus", status).put("result", value);
    }

    private String readString(Object target, String getter, String fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(getter).invoke(target);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return XposedHelpers.findClass(name, loader);
    }

    private boolean isCoroutineSuspended(Object value) {
        try {
            return value == load("kotlin.coroutines.intrinsics.IntrinsicsKt").getMethod(
                "getCOROUTINE_SUSPENDED").invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Throwable unwrap(Throwable value) {
        while ((value instanceof java.lang.reflect.InvocationTargetException)
                && ((java.lang.reflect.InvocationTargetException) value).getCause() != null) {
            value = ((java.lang.reflect.InvocationTargetException) value).getCause();
        }
        return value;
    }

    private static String message(Throwable value) {
        String message = value == null ? null : value.getMessage();
        return message == null || message.length() == 0
            ? (value == null ? "Unknown error" : value.getClass().getSimpleName()) : message;
    }
}
