package dev.yoyobridge.helper;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** The measured daily time-fence + GUI subset, using native DSL, storage and cards. */
public final class NativeWorkflowExecutor {
    private static final String BASE = "com.hihonor.magicvoice.yoyotask.shared.";
    private static final String UI_TOPIC = "com.hihonor.magicvoice.frameworkui.topic.FwConversationTopicManager";
    private static final Map<String, Operation> OPERATIONS = new LinkedHashMap<>();
    private static final int MAX_OPERATIONS = 32;
    private static final java.security.SecureRandom IDS = new java.security.SecureRandom();
    private final Context context;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NativeWorkflowExecutor(Context context, ClassLoader loader) {
        this.context = context;
        this.loader = loader;
    }

    public JSONObject createDailyGui(JSONObject args) {
        if (Looper.myLooper() == Looper.getMainLooper()) return error("MAIN_THREAD", "请从后台线程调用");
        try {
            int hour = integer(args, "hour", 0, 23), minute = integer(args, "minute", 0, 59);
            String app = required(args, "app_name", 100), pkg = required(args, "pkg_name", 255);
            String query = required(args, "task_query", 2000), scope = required(args, "_requestScope", 128);
            if (!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) return error("INVALID_ARGUMENT", "应用包名格式无效");
            context.getPackageManager().getApplicationInfo(pkg, 0);
            String key = digest(scope + "\u0000" + hour + "\u0000" + minute + "\u0000" + pkg + "\u0000" + query);
            synchronized (OPERATIONS) {
                Operation existing = OPERATIONS.get(key);
                if (existing != null) return existing.snapshot();
            }
            JSONObject caps = readCapabilities();
            Object topicManager = callStatic(UI_TOPIC, "getInstance", new Class<?>[0]);
            long topic = ((Number) call(topicManager, "getTopicId", new Class<?>[0])).longValue();
            String conversation = String.valueOf(call(topicManager, "getConversationId", new Class<?>[0]));
            if (topic <= 0 || conversation.isEmpty() || "null".equals(conversation))
                return error("UNSUPPORTED", "原生话题尚未建立，请在当前对话稍后重试");
            JSONObject payload = buildPayload(caps, hour, minute, app, pkg, query, Calendar.getInstance());
            Operation op = new Operation(topic, conversation, payload.getJSONArray("tasks").getJSONObject(0).getJSONObject("extMap").getString("workflowId"));
            synchronized (OPERATIONS) {
                if (OPERATIONS.containsKey(key)) return OPERATIONS.get(key).snapshot();
                if (OPERATIONS.size() >= MAX_OPERATIONS) {
                    Iterator<Operation> it = OPERATIONS.values().iterator();
                    while (it.hasNext() && OPERATIONS.size() >= MAX_OPERATIONS) {
                        if (it.next().finished) it.remove();
                    }
                }
                if (OPERATIONS.size() >= MAX_OPERATIONS) return error("BUSY", "已有任务正在确认保存状态，请勿重复创建");
                OPERATIONS.put(key, op);
            }
            if (!main.post(() -> startConversion(op, payload))) op.fail("DISPATCH", "无法派发原生转换请求");
            boolean completed;
            try { completed = op.done.await(60, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return op.snapshot();
            }
            if (!completed) {
                synchronized (op) {
                    if (!op.saveStarted && !op.finished) {
                        op.cancelled = true;
                        op.fail("DSL_TIMEOUT", "原生转换超时，未提交手机端保存");
                    }
                }
            }
            return op.snapshot();
        } catch (Throwable error) {
            return error("UNSUPPORTED", "原生任务能力或参数不可用（" + rootCause(error).getClass().getSimpleName() + "）");
        }
    }

    public JSONObject getStatus(String requestId) {
        synchronized (OPERATIONS) {
            for (Operation op : OPERATIONS.values()) if (op.id.equals(requestId)) { requestReadback(op); return op.snapshot(); }
        }
        return error("NOT_FOUND", "此进程中没有对应创建请求；请在 YOYO 任务中心核对，不要盲目重建");
    }

    private JSONObject readCapabilities() throws Exception {
        File file = new File(context.getFilesDir(), "yoyo-lsp/native-workflow-capabilities.json");
        if (!file.isFile() || file.length() > 65536) throw new IllegalStateException("capabilities missing");
        JSONObject caps = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        PackageInfo info = context.getPackageManager().getPackageInfo("com.hihonor.magicvoice", 0);
        if (caps.optInt("schemaVersion") != 1 || !"com.hihonor.magicvoice".equals(caps.optString("sourcePackage"))
                || !String.valueOf(info.versionName).equals(caps.optString("sourceVersionName"))
                || info.versionCode != caps.optLong("sourceVersionCode")
                || info.lastUpdateTime != caps.optLong("sourceLastUpdateTime"))
            throw new IllegalStateException("capabilities version mismatch");
        Object manager = callStatic(BASE + "agenttool.WorkFlowAgentToolsManager", "getInstance", new Class<?>[0]);
        List<?> catalog = (List<?>) call(manager, "getToolList", new Class<?>[0]);
        JSONObject tools = caps.getJSONObject("tools");
        for (String name : new String[]{"create_trigger_task", "time_fence", "gui_task_create"}) {
            JSONObject tool = tools.getJSONObject(name);
            JSONObject execution = tool.getJSONObject("executeParams");
            if (execution.optString("pluginId").isEmpty() || !execution.has("version")
                    || execution.optString("pkgName").trim().isEmpty()) throw new IllegalStateException("plugin metadata missing");
            boolean matched = false;
            if (catalog != null) for (Object entry : catalog) {
                if (name.equals(call(entry, "getName", new Class<?>[0]))
                        && tool.getString("toolId").equals(call(entry, "getId", new Class<?>[0]))) matched = true;
            }
            if (!matched) throw new IllegalStateException("native tool identity mismatch");
        }
        JSONObject gui = tools.getJSONObject("gui_task_create").getJSONObject("mcpCall");
        if (!"gui_task_create".equals(gui.optString("name")) || gui.optString("appPkg").trim().isEmpty())
            throw new IllegalStateException("native GUI metadata missing");
        // Check the actual JVM surface before any write, not decompiler aliases.
        Class<?> result = load(BASE + "workflow.model.BrainMcpResult");
        result.getMethod("isSuccess"); result.getMethod("getSucceededIds");
        Class<?> task = load(BASE + "workflow.model.Task");
        task.getMethod("getExtMap");
        load(BASE + "workflow.model.ExtMapBean").getMethod("getWorkflowId");
        Class<?> useCase = load(BASE + "workflow.WorkFlowUseCase");
        Class<?> listener = load(BASE + "workflow.WorkFlowUseCase$WorkFlowListener");
        useCase.getConstructor();
        useCase.getMethod("addOrUpdateWorkFlow", List.class, Map.class, listener);
        useCase.getMethod("getWorkFlowListStrictly", listener, Runnable.class);
        load(BASE + "chat.WorkFlowCardDisplayHelper").getMethod("showTrialRunCard", Map.class, task, String.class);
        load(BASE + "constants.CardConstants").getField("WORK_FLOW_TOPIC_ID");
        return caps;
    }

    static JSONObject buildPayload(JSONObject caps, int hour, int minute, String app, String pkg,
            String query, Calendar now) throws Exception {
        JSONObject tools = caps.getJSONObject("tools");
        String callId = "call_" + UUID.randomUUID().toString().replace("-", "");
        Calendar first = (Calendar) now.clone();
        first.set(Calendar.HOUR_OF_DAY, hour); first.set(Calendar.MINUTE, minute);
        first.set(Calendar.SECOND, 0); first.set(Calendar.MILLISECOND, 0);
        boolean tomorrow = !first.after(now);
        if (tomorrow) first.add(Calendar.DAY_OF_MONTH, 1);
        String rule = "drr.rrule(drr.DAILY,startTime=now+dr.relativedelta("
            + (tomorrow ? "days=1," : "") + "hour=" + hour + ",minute=" + minute + ",second=0))";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        format.setTimeZone(now.getTimeZone());
        JSONObject infer = new JSONObject().put("rrule", rule).put("repeat", "DAILY")
            .put("dtstart", format.format(first.getTime()));
        for (String name : new String[]{"dtend", "year_until", "interval", "count", "until", "bymonth",
                "bymonthday", "byweekday", "year", "vagueTime", "vagueDate", "vagueEndDate", "vagueEndTime"}) infer.put(name, "");
        infer.put("byhour", String.valueOf(hour)).put("byminute", String.valueOf(minute)).put("bysecond", "0").put("lunar", "0");
        String condition = String.format(Locale.ROOT, "每天%02d:%02d", hour, minute);
        JSONObject time = tool(tools.getJSONObject("time_fence"), "time_fence", callId, condition)
            .put("title", "时间围栏").put("tags", "trigger").put("functionExecuteScript", "")
            .put("parameters", new JSONObject().put("fence_type", "重复触发")
                .put("start_datetime", new JSONObject().put("modelRes", rule).put("type", "repeat").put("inferResult", infer).toString()));
        JSONObject gui = tool(tools.getJSONObject("gui_task_create"), "gui_task_create", callId, query)
            .put("title", "操控手机").put("tags", "action")
            .put("parameters", new JSONObject().put("app_name", app).put("pkg_name", pkg).put("task_query", query));
        JSONObject mcp = new JSONObject(tools.getJSONObject("gui_task_create").getJSONObject("mcpCall").toString());
        mcp.getJSONObject("arguments").put("app_name", app).put("task_query", query);
        gui.put("mcpCall", mcp).put("pluginParamsJSONString", new JSONObject().put("app_name", app).put("task_query", query).toString());
        // The native planning service supplies a fresh opaque 26-digit identity;
        // the DSL converter preserves it rather than allocating one.
        String workflowId = new java.math.BigInteger(87, IDS)
            .mod(new java.math.BigInteger("90000000000000000000000000"))
            .add(new java.math.BigInteger("10000000000000000000000000")).toString();
        String workflowName = condition + " " + app + "任务";
        JSONObject rawTask = new JSONObject().put("id", workflowId).put("name", workflowName)
            .put("input", new JSONObject().put("triggers", condition).put("actions", query))
            .put("content", condition + "，" + query).put("metadata", new JSONObject());
        JSONObject task = tool(tools.getJSONObject("create_trigger_task"), "create_trigger_task", callId, query)
            .put("parameters", new JSONObject().put("triggers", new JSONArray().put(time)).put("actions", new JSONArray().put(gui)))
            .put("extMap", new JSONObject().put("workflowId", workflowId)
                .put("workflowRaw", new JSONArray().put(rawTask).toString())
                .put("workflowName", workflowName).put("workflowDesc", condition + " " + query));
        // IDs and raw task history are generated from this request; no prior task or account data is copied.
        return new JSONObject().put("executor", "client.domain").put("planBoardId", UUID.randomUUID().toString().replace("-", ""))
            .put("planTaskId", UUID.randomUUID().toString().replace("-", "")).put("tasks", new JSONArray().put(task));
    }

    private static JSONObject tool(JSONObject metadata, String name, String callId, String description) throws Exception {
        JSONObject execution = new JSONObject(metadata.getJSONObject("executeParams").toString());
        execution.put("skillDesc", description);
        return new JSONObject().put("tool", name).put("toolId", metadata.getString("toolId"))
            .put("toolCallId", callId).put("executeParams", execution);
    }

    private void startConversion(Operation op, JSONObject payload) {
        try {
            if (op.cancelled) return;
            Class<?> payloadClass = load(BASE + "commonmodel.PayLoad");
            Object gson = callStatic("com.hihonor.magicvoice.phonebase.util.JsonUtil", "getGsonNumber", new Class<?>[0]);
            Object nativePayload = call(gson, "fromJson", new Class<?>[]{String.class, Class.class}, payload.toString(), payloadClass);
            Class<?> listenerClass = load("com.hihonor.magicvoice.yoyotask.core.network.websocket.WebSocketClient$WebSocketEventListener");
            Object noOp = Proxy.newProxyInstance(loader, new Class<?>[]{listenerClass}, (proxy, method, args) -> objectMethod(proxy, method, args));
            Class<?> abilityClass = load(BASE + "chat.WorkFlowAbilityWrapper");
            Object ability = abilityClass.getConstructor(listenerClass).newInstance(noOp);
            Class<?> errorClass = load(BASE + "chat.WorkFlowChatHelper$ErrorReporterWithTip");
            Object errors = Proxy.newProxyInstance(loader, new Class<?>[]{errorClass}, (proxy, method, args) -> {
                if ("report".equals(method.getName())) op.fail("NATIVE_DSL", "原生 DSL 转换未成功，未保存任务");
                return objectMethod(proxy, method, args);
            });
            java.util.function.Consumer<Object> success = group -> main.post(() -> prepareAndSave(op, group));
            callStatic(BASE + "chat.WorkFlowChatHelper", "handlePayLoadMessage",
                new Class<?>[]{payloadClass, abilityClass, errorClass, java.util.function.Consumer.class}, nativePayload, ability, errors, success);
        } catch (Throwable failure) { op.fail("NATIVE_DSL", "原生转换接口不可用（" + rootCause(failure).getClass().getSimpleName() + "）"); }
    }

    @SuppressWarnings("unchecked")
    private void prepareAndSave(Operation op, Object group) {
        try {
            if (op.cancelled || op.finished) return;
            Class<?> groupClass = load(BASE + "workflow.model.WorkflowGroup");
            List<?> tasks = (List<?>) call(group, "getTasks", new Class<?>[0]);
            if (tasks == null || tasks.size() != 1) { op.fail("DSL_RESULT", "转换结果不属于已验证的单任务范围，未保存"); return; }
            List<?> rawIds = (List<?>) call(group, "getWorkflowIdsFromTask", new Class<?>[0]);
            List<String> ids = new ArrayList<>();
            if (rawIds != null) for (Object id : rawIds) if (id != null && !String.valueOf(id).isEmpty()) ids.add(String.valueOf(id));
            if (ids.size() != tasks.size()) { op.fail("DSL_MISSING_IDS", "原生转换未分配完整任务 ID，未保存"); return; }
            if (ids.size() != 1 || !op.expectedId.equals(ids.get(0))) {
                op.fail("DSL_ID_MISMATCH", "原生转换返回的任务身份与本次请求不符，未保存"); return;
            }
            op.ids = new JSONArray(ids);
            Class<?> errorClass = load(BASE + "chat.WorkFlowChatHelper$ErrorReporterWithTip");
            Object validation = Proxy.newProxyInstance(loader, new Class<?>[]{errorClass}, (proxy, method, args) -> {
                if ("report".equals(method.getName())) op.fail("NATIVE_VALIDATION", "原生任务校验未通过，未保存");
                return objectMethod(proxy, method, args);
            });
            boolean valid = (Boolean) callStatic(BASE + "chat.WorkFlowChatHelper", "validateGroupTasks", new Class<?>[]{List.class, errorClass}, tasks, validation);
            if (!valid || op.finished) return;
            callStatic(BASE + "chat.WorkFlowChatHelper", "ensureExecuteType", new Class<?>[]{groupClass, Map.class}, group, Collections.emptyMap());
            callStatic(BASE + "chat.WorkFlowChatHelper", "applyGroupActionDefaults", new Class<?>[]{groupClass}, group);
            String prefix = String.valueOf(load(BASE + "constants.WorkflowConstants").getField("KEY_YOYO_MAIN").get(null));
            String cacheKey = "yoyo_bridge_workflow_" + op.id;
            String cacheClass = "com.hihonor.magicvoice.phonebase.storage.MemoryCacheEnhance";
            Map<String, String> times;
            try {
                callStatic(cacheClass, "put", new Class<?>[]{String.class, Object.class}, cacheKey, prefix + op.conversation);
                times = (Map<String, String>) callStatic(BASE + "chat.WorkFlowChatHelper", "buildTaskExtras", new Class<?>[]{groupClass, String.class}, group, cacheKey);
            } finally { callStatic(cacheClass, "remove", new Class<?>[]{String.class}, cacheKey); }
            Map<String, Object> parameters = (Map<String, Object>) callStatic(BASE + "chat.WorkFlowChatHelper", "buildMixedOperationParams",
                new Class<?>[]{List.class, List.class, groupClass, String.class}, tasks, Collections.emptyList(), group, String.valueOf(System.currentTimeMillis()));
            Object useCase = load(BASE + "workflow.WorkFlowUseCase").getConstructor().newInstance();
            Class<?> callbackClass = load(BASE + "workflow.WorkFlowUseCase$WorkFlowListener");
            op.useCase = useCase; op.tasks = tasks; op.times = times; op.prefix = prefix;
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass}, (proxy, method, values) -> {
                if ("onResult".equals(method.getName())) handleSaved(op, values == null ? null : values[0], tasks, times, prefix);
                return objectMethod(proxy, method, values);
            });
            synchronized (op) {
                if (op.cancelled || op.finished) return;
                op.saveStarted = true;
                call(useCase, "addOrUpdateWorkFlow", new Class<?>[]{List.class, Map.class, callbackClass}, ids, parameters, callback);
            }
        } catch (Throwable failure) {
            if (op.saveStarted) { diagnostic("save_invocation", failure); op.unknown(); requestReadback(op); }
            else op.fail("NATIVE_SAVE", "原生保存接口不可用，未提交保存（" + rootCause(failure).getClass().getSimpleName() + "）");
        }
    }

    private void handleSaved(Operation op, Object result, List<?> tasks, Map<String, String> times, String prefix) {
        try {
            if (result == null || !Boolean.TRUE.equals(call(result, "isSuccess", new Class<?>[0]))) {
                diagnostic("callback_not_success", null); op.unknown(); requestReadback(op); return;
            }
            List<?> succeeded = (List<?>) call(result, "getSucceededIds", new Class<?>[0]);
            List<String> saved = new ArrayList<>();
            if (succeeded != null) for (Object id : succeeded) saved.add(String.valueOf(id));
            for (int i = 0; i < op.ids.length(); i++) if (!saved.contains(op.ids.getString(i))) {
                diagnostic("callback_ids_incomplete", null); op.unknown(); requestReadback(op); return;
            }
            completeSaved(op, "native_callback");
        } catch (Throwable failure) {
            diagnostic("save_callback", failure); op.unknown(); requestReadback(op);
        }
    }

    /** Read-only reconciliation: never retries the create operation. */
    private void requestReadback(Operation op) {
        synchronized (op) {
            if (!op.saveStarted || op.finished || op.reading || op.useCase == null) return;
            op.reading = true;
        }
        main.post(() -> {
            try {
                Class<?> listener = load(BASE + "workflow.WorkFlowUseCase$WorkFlowListener");
                Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, values) -> {
                    if ("onResult".equals(method.getName())) {
                        try {
                            if (values != null && values.length == 1 && values[0] instanceof List) {
                                for (Object task : (List<?>) values[0]) {
                                    Object ext = call(task, "getExtMap", new Class<?>[0]);
                                    if (ext != null && op.expectedId.equals(call(ext, "getWorkflowId", new Class<?>[0]))) {
                                        completeSaved(op, "native_storage_readback"); break;
                                    }
                                }
                            }
                        } catch (Throwable failure) { diagnostic("storage_readback_result", failure); }
                        finally { op.reading = false; }
                    }
                    return objectMethod(proxy, method, values);
                });
                Runnable failure = () -> { diagnostic("storage_readback_failed", null); op.reading = false; };
                call(op.useCase, "getWorkFlowListStrictly", new Class<?>[]{listener, Runnable.class}, callback, failure);
            } catch (Throwable failure) { diagnostic("storage_readback", failure); op.reading = false; }
        });
    }

    private void completeSaved(Operation op, String verification) {
        synchronized (op) {
            if (op.finished) return;
            boolean displayed = false;
            try {
                Object request = callStatic(BASE + "utils.WorkFlowRequestUtil", "getInstance", new Class<?>[0]);
                call(request, "setHasWorkFlow", new Class<?>[]{boolean.class}, true);
            } catch (Throwable failure) { diagnostic("workflow_context", failure); }
            try {
                Map<String, String> card = new HashMap<>();
                card.put("cardServiceId", "-1"); card.put("cardTopicId", String.valueOf(op.topic));
                String topicKey = String.valueOf(load(BASE + "constants.CardConstants").getField("WORK_FLOW_TOPIC_ID").get(null));
                card.put(topicKey, op.prefix + op.conversation);
                for (Object task : op.tasks) {
                    Object ext = call(task, "getExtMap", new Class<?>[0]);
                    String id = String.valueOf(call(ext, "getWorkflowId", new Class<?>[0]));
                    callStatic(BASE + "chat.WorkFlowCardDisplayHelper", "showTrialRunCard",
                        new Class<?>[]{Map.class, load(BASE + "workflow.model.Task"), String.class}, card, task, op.times.get(id));
                }
                displayed = true;
            } catch (Throwable failure) { diagnostic("native_card", failure); }
            // A UI failure cannot erase a positively verified storage result.
            try { op.succeed(displayed, verification); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static void diagnostic(String stage, Throwable failure) {
        android.util.Log.w("YOYOBridgeWorkflow", stage + (failure == null ? "" : ":" + rootCause(failure).getClass().getSimpleName()));
    }

    private Class<?> load(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private Object callStatic(String type, String name, Class<?>[] signature, Object... args) throws Exception { return load(type).getMethod(name, signature).invoke(null, args); }
    private static Object call(Object target, String name, Class<?>[] signature, Object... args) throws Exception { return target.getClass().getMethod(name, signature).invoke(target, args); }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("toString".equals(method.getName())) return "YOYO Bridge native workflow callback";
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return args != null && proxy == args[0];
        return null;
    }
    private static int integer(JSONObject args, String key, int low, int high) throws Exception {
        Object value = args.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) throw new IllegalArgumentException(key);
        int number = ((Number) value).intValue();
        if (number < low || number > high) throw new IllegalArgumentException(key);
        return number;
    }
    private static String required(JSONObject args, String key, int max) throws Exception {
        Object value = args.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key);
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > max) throw new IllegalArgumentException(key);
        return text;
    }
    private static String digest(String input) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static Throwable rootCause(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error; }
    private static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("verified", false).put("pending", false).put("status", code).put("message", message); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Operation {
        final String id = UUID.randomUUID().toString();
        final long topic; final String conversation; final String expectedId;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean saveStarted, cancelled, finished, reading;
        volatile Object useCase; volatile List<?> tasks; volatile Map<String, String> times; volatile String prefix;
        volatile JSONArray ids = new JSONArray();
        private JSONObject result;
        Operation(long topic, String conversation, String expectedId) { this.topic = topic; this.conversation = conversation; this.expectedId = expectedId; }
        synchronized JSONObject snapshot() {
            try {
                if (result != null) return new JSONObject(result.toString());
                return new JSONObject().put("ok", true).put("verified", false).put("pending", true)
                    .put("status", "PENDING").put("request_id", id).put("workflow_ids", new JSONArray(ids.toString()))
                    .put("message", "原生任务保存状态待确认，请查询 workflow.get_status，不要重复创建");
            } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        synchronized void fail(String code, String message) {
            if (finished) return;
            result = error(code, message); finished = true; done.countDown();
        }
        synchronized void unknown() { done.countDown(); }
        synchronized void succeed(boolean displayed, String verification) throws Exception {
            if (finished) return;
            result = new JSONObject().put("ok", true).put("verified", true).put("pending", false)
                .put("status", "CREATED").put("request_id", id).put("workflow_ids", new JSONArray(ids.toString()))
                .put("verification", verification).put("native_card_dispatched", displayed).put("message", "YOYO原生定时任务已保存");
            finished = true; done.countDown();
        }
    }
}
