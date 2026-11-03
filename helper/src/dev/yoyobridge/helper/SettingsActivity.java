package dev.yoyobridge.helper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** Native Material settings screen for YOYO Bridge. */
public final class SettingsActivity extends Activity {
    private static final Uri SETTINGS_URI = Uri.parse("content://" + BridgeSettingsProvider.AUTHORITY);
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText endpointInput;
    private EditText modelInput;
    private EditText keyInput;
    private EditText roundsInput;
    private CheckBox enabledInput;
    private CheckBox httpInput;
    private CheckBox streamInput;
    private RadioGroup keyMode;
    private RadioButton keepKey;
    private RadioButton replaceKey;
    private RadioButton clearKey;
    private TextView statusText;
    private TextView logText;
    private TextView feedbackText;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { loadStatus(); main.postDelayed(this, 4000); }
    };
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }

    @Override protected void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        buildPage();
        loadConfig();
        loadStatus();
    }

    private void buildPage() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, dp(18), pad, dp(28));
        scroll.addView(page);

        TextView title = text("YOYO Bridge", 26, true);
        page.addView(title);
        TextView intro = text("自定义模型连接与 Next Pro 管理", 14, false);
        intro.setPadding(0, dp(4), 0, dp(18));
        page.addView(intro);

        enabledInput = check("启用自定义模型桥接");
        page.addView(enabledInput);
        page.addView(label("API 完整 endpoint"));
        endpointInput = edit("https://example.com/v1/chat/completions", false);
        page.addView(endpointInput);
        page.addView(label("模型名称"));
        modelInput = edit("例如 gpt-4.1-mini", false);
        page.addView(modelInput);

        page.addView(label("API key"));
        keyInput = edit("密钥不会回显；选择替换后输入新密钥", true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(keyInput);
        keyMode = new RadioGroup(this);
        keyMode.setOrientation(RadioGroup.VERTICAL);
        keepKey = radio("保留已保存密钥");
        replaceKey = radio("替换为输入的新密钥");
        clearKey = radio("清除密钥");
        keyMode.addView(keepKey);
        keyMode.addView(replaceKey);
        keyMode.addView(clearKey);
        keepKey.setChecked(true);
        keyMode.setOnCheckedChangeListener((group, checkedId) -> {
            keyInput.setEnabled(checkedId == replaceKey.getId());
            if (checkedId != replaceKey.getId()) keyInput.setText("");
        });
        page.addView(keyMode);

        httpInput = check("允许 HTTP（不加密）");
        page.addView(httpInput);
        streamInput = check("启用流式响应");
        page.addView(streamInput);
        page.addView(label("最大工具调用轮数（1–24）"));
        roundsInput = edit("16", false);
        roundsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        page.addView(roundsInput);

        LinearLayout saveRow = row(page);
        Button save = button("保存并应用");
        saveRow.addView(save, weight());
        save.setOnClickListener(v -> saveConfig());

        LinearLayout actionRow = row(page);
        Button test = button("连接测试");
        Button activate = button("激活 Next Pro");
        actionRow.addView(test, weight());
        actionRow.addView(activate, weight());
        test.setOnClickListener(v -> testConnection());
        activate.setOnClickListener(v -> activate());

        Button deactivate = button("停用并恢复 YOYO 内置模型");
        LinearLayout deactivateRow = row(page);
        deactivateRow.addView(deactivate, weight());
        deactivate.setOnClickListener(v -> sendAction("deactivate"));

        feedbackText = text("", 13, false);
        page.addView(feedbackText);
        page.addView(label("连接与代理状态"));
        statusText = text("尚无状态快照", 14, false);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        page.addView(statusText);
        page.addView(label("状态日志"));
        logText = text("等待 YOYO 上报状态。", 13, false);
        logText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logText.setTextIsSelectable(true);
        page.addView(logText);
        setContentView(scroll);
    }

    private void loadConfig() {
        try {
            Bundle result = getContentResolver().call(SETTINGS_URI, "load", null, null);
            JSONObject config = new JSONObject(result == null ? "{}" : result.getString("config", "{}"));
            enabledInput.setChecked(config.optBoolean("enabled", false));
            endpointInput.setText(config.optString("endpoint", ""));
            modelInput.setText(config.optString("model", ""));
            httpInput.setChecked(config.optBoolean("allowHttp", false));
            streamInput.setChecked(config.optBoolean("stream", false));
            roundsInput.setText(String.valueOf(config.optInt("maxToolRounds", 16)));
            keyInput.setEnabled(false);
        } catch (Exception e) {
            appendLog("配置读取失败，请检查 helper 私密存储。", false);
        }
    }

    private JSONObject makeConfig(boolean forTest) throws Exception {
        JSONObject config = BridgeSettingsProvider.getConfig(this);
        String endpoint = endpointInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        int rounds;
        try { rounds = Integer.parseInt(roundsInput.getText().toString().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("工具轮数请输入 1 至 24 的整数"); }
        if (rounds < 1 || rounds > 24) throw new IllegalArgumentException("工具轮数请输入 1 至 24 的整数");
        config.put("schemaVersion", 2).put("enabled", enabledInput.isChecked())
            .put("scope", "custom-provider-only").put("providerSlot", "yoyo_bridge")
            .put("endpoint", endpoint).put("model", model).put("allowHttp", httpInput.isChecked())
            .put("stream", streamInput.isChecked()).put("port", config.optInt("port", 38765))
            .put("maxToolRounds", rounds);
        if (keyMode.getCheckedRadioButtonId() == clearKey.getId()) config.put("apiKey", "");
        else if (keyMode.getCheckedRadioButtonId() == replaceKey.getId()) {
            String replacement = keyInput.getText().toString();
            if (replacement.trim().isEmpty()) throw new IllegalArgumentException("请输入新密钥，或选择保留/清除");
            config.put("apiKey", replacement);
        }
        if (forTest && (endpoint.isEmpty() || model.isEmpty()))
            throw new IllegalArgumentException("请填写完整 endpoint 和模型名称");
        if (enabledInput.isChecked() && (endpoint.isEmpty() || model.isEmpty()))
            throw new IllegalArgumentException("启用前请填写完整 endpoint 和模型名称");
        return dev.yoyobridge.Bridge.validateConfig(config);
    }

    private boolean saveConfig() {
        try {
            JSONObject config = makeConfig(false);
            BridgeSettingsProvider.saveConfig(this, config);
            sendApplyBroadcast("reload");
            keyInput.setText("");
            keyInput.setEnabled(false);
            keepKey.setChecked(true);
            appendLog("配置已保存并通知桥接重载。endpoint/端口变更后请重开 YOYO。", false);
            Toast.makeText(this, "已保存并应用；endpoint 或端口变更后请重开 YOYO", Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception e) {
            showError(e, "保存失败");
            return false;
        }
    }

    private void testConnection() {
        final JSONObject config;
        try { config = makeConfig(true); }
        catch (Exception e) { showError(e, "无法测试"); return; }
        appendLog("正在测试连接…", false);
        new Thread(() -> {
            JSONObject outcome;
            try {
                Class<?> bridge = Class.forName("dev.yoyobridge.Bridge");
                Method probe = bridge.getMethod("probeConfig", JSONObject.class);
                Object value = probe.invoke(null, config);
                if (!(value instanceof JSONObject)) throw new IllegalStateException("测试接口返回格式无效");
                outcome = (JSONObject) value;
            } catch (Throwable error) {
                outcome = new JSONObject();
            }
            final JSONObject result = outcome;
            main.post(() -> {
                boolean ok = result.optBoolean("ok", false);
                String message = result.optString("message", ok ? "连接成功" : "连接失败");
                String key = config.optString("apiKey", "");
                if (!key.isEmpty()) message = message.replace(key, "••••••");
                appendLog((ok ? "连接测试成功：" : "连接测试未通过：") + message, false);
                Toast.makeText(this, ok ? "连接成功" : "连接测试未通过", Toast.LENGTH_SHORT).show();
            });
        }, "yoyo-bridge-probe").start();
    }

    private void activate() {
        if (!enabledInput.isChecked()) {
            Toast.makeText(this, "请先启用桥接并保存配置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!saveConfig()) return;
        sendAction("activate");
        Toast.makeText(this, "已请求激活 Next Pro；生效后请重开 YOYO 对话", Toast.LENGTH_LONG).show();
    }

    private void sendAction(String action) {
        sendApplyBroadcast(action);
        appendLog("已向 YOYO 发送操作：" + action, false);
    }

    private void sendApplyBroadcast(String action) {
        Intent intent = new Intent("dev.yoyobridge.helper.APPLY").setPackage(BridgeSettingsProvider.TARGET_PACKAGE);
        intent.putExtra("action", action);
        sendBroadcast(intent);
    }

    private void loadStatus() {
        try {
            Bundle result = getContentResolver().call(SETTINGS_URI, "status", null, null);
            JSONObject snapshot = new JSONObject(result == null ? "{}" : result.getString("status", "{}"));
            showSnapshot(snapshot);
        } catch (Exception e) {
            appendLog("尚未收到 YOYO 状态上报。", false);
        }
    }

    private void showSnapshot(JSONObject snapshot) {
        boolean fresh = System.currentTimeMillis() - snapshot.optLong("reportedAt", 0) < 15000;
        String summary = "YOYO " + snapshot.optString("version", "")
            + " · " + (fresh && snapshot.optBoolean("active", false) ? "运行中" : "未运行或状态待更新");
        JSONObject route = snapshot.optJSONObject("route");
        if (route != null) summary += "\nNext Pro: " + (route.optBoolean("customSelected", false)
            ? "已选择 YOYO Bridge" : "当前使用其他模型");
        statusText.setText(summary);
        String log = snapshot.optString("log", "");
        if (!log.isEmpty()) appendLog(log, true);
        else appendLog("状态已更新。", true);
    }

    private void appendLog(String message, boolean replace) {
        String safe = message == null ? "" : message;
        try {
            String secret = BridgeSettingsProvider.getConfig(this).optString("apiKey", "");
            if (!secret.isEmpty()) safe = safe.replace(secret, "••••••");
        } catch (Exception ignored) {}
        String entry = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + "  " + safe;
        if (!replace && feedbackText != null) {
            feedbackText.setText(entry);
            return;
        }
        if (replace || logText == null) {
            if (logText != null) logText.setText(entry);
            return;
        }
        if (logText == null) return;
        String old = logText.getText().toString();
        String joined = old.isEmpty() ? entry : old + "\n" + entry;
        if (joined.length() > 6000) joined = joined.substring(joined.length() - 6000);
        logText.setText(joined);
    }

    private void showError(Exception error, String prefix) {
        String message = error instanceof IllegalArgumentException ? error.getMessage() : "请检查配置和存储权限";
        appendLog(prefix + "：" + message, false);
        Toast.makeText(this, prefix + "：" + message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setPadding(0, dp(8), 0, dp(4));
        parent.addView(result, new LinearLayout.LayoutParams(-1, -2));
        return result;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private TextView label(String value) {
        TextView view = text(value, 14, true);
        view.setPadding(0, dp(16), 0, dp(6));
        return view;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText edit(String hint, boolean singleLine) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setHint(hint);
        view.setTextSize(16);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackgroundResource(android.R.drawable.edit_text);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return view;
    }

    private CheckBox check(String label) {
        CheckBox view = new CheckBox(this);
        view.setText(label);
        view.setTextSize(16);
        return view;
    }

    private RadioButton radio(String label) {
        RadioButton view = new RadioButton(this);
        view.setId(View.generateViewId());
        view.setText(label);
        view.setTextSize(15);
        return view;
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(13);
        return view;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
