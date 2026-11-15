package local.vivodoubleclick.controller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "VivoCameraControl";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> operations = new ArrayList<>();
    private LocalAdbManager manager;
    private TextView connection;
    private TextView state;
    private TextView stateMessage;
    private TextView pairingMessage;
    private TextView permissionStatus;
    private TextView detail;
    private LinearLayout settingsPanel;
    private Button settingsToggle;
    private Button autoPairButton;
    private EditText connectPort;
    private Switch recoverySwitch;
    private final Runnable autoClose = () -> {
        if (!this.destroyed && this.resumed && !this.busy && !pairingActive()) finishAndRemoveTask();
    };
    private boolean busy;
    private boolean manualPairing;
    private boolean resumed;
    private boolean checkedThisResume;
    private long checkedPairUpdate;
    private SharedPreferences preferences;
    private volatile boolean destroyed;
    private final Runnable pairingUpdate = () -> {
        if (!destroyed) { updateConnection(); refreshIfNeeded(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if ("restore_update".equals(key)) handler.post(() -> {
            if (destroyed || busy || pairingActive()) return;
            if (preferences.getBoolean("restore_ok", false) && RecoveryScheduler.recentlyVerified(this)) renderRecovered();
            else showUnknown(preferences.getString("restore_message", "等待 Wi-Fi 自动恢复。"));
        });
        if (key != null && (key.startsWith("pair_") || "paired".equals(key))) {
            handler.removeCallbacks(pairingUpdate);
            handler.post(pairingUpdate);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("controller", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        panel.setPadding(pad, pad, pad, dp(56));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                panel.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, dp(24) + bars.bottom);
            } else {
                panel.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                        pad + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        scroll.addView(panel);
        text(panel, "Vivo 双击相机", 25).setTypeface(null, Typeface.BOLD);
        text(panel, "1.0 独立控制器", 14);
        state = text(panel, "状态未确认", 34);
        state.setTypeface(null, Typeface.BOLD);
        state.setPadding(0, dp(20), 0, dp(4));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateMessage = text(panel, "连接 Wi-Fi 后，可刷新状态或启动 1.0。", 16);
        stateMessage.setPadding(0, 0, 0, dp(18));
        connection = text(panel, "", 16);
        connection.setTypeface(null, Typeface.BOLD);
        pairingMessage = text(panel, "", 14);
        pairingMessage.setTextColor(Color.DKGRAY);
        pairingMessage.setPadding(0, dp(4), 0, dp(12));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(actions);
        primaryButton(actions, "启动 1.0", v -> runAction("start"));
        primaryButton(actions, "停止", v -> runAction("stop"));
        primaryButton(actions, "刷新", v -> runAction("status"));
        operations.add(button(panel, "关闭控制界面", v -> closeControlInterface()));
        TextView exitHint = text(panel, "关闭界面不停止 1.0；USB 调试已开启时，会先关闭无线调试并保留 USB 调试，不必插线。手机重启并首次解锁后，连接 Wi-Fi 可自动恢复；晚些联网也可以。", 14);
        exitHint.setTextColor(Color.DKGRAY);
        exitHint.setPadding(0, dp(4), 0, dp(16));
        settingsToggle = button(panel, "配对与设置  ▾", v -> setSettingsExpanded(settingsPanel.getVisibility() != View.VISIBLE));
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setVisibility(View.GONE);
        panel.addView(settingsPanel);
        text(settingsPanel, "仅首次使用，或系统已移除本应用的配对时，才需要配对。已配对可直接使用上方启停按钮。", 15);
        autoPairButton = button(settingsPanel, "首次自动配对", v -> showAutoPairing());
        operations.add(autoPairButton);
        operations.add(button(settingsPanel, "手动配对（备用）", v -> showPairing()));
        operations.add(button(settingsPanel, "打开开发者选项", v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException unavailable) { showSettingsHint(); }
        }));
        operations.add(button(settingsPanel, "打开无线调试", v -> {
            if (!SettingsNavigation.openWireless(this)) showSettingsHint();
        }));
        recoverySwitch = new Switch(this);
        recoverySwitch.setText("开机和 Wi-Fi 联网后自动恢复");
        recoverySwitch.setChecked(RecoveryScheduler.enabled(this));
        settingsPanel.addView(recoverySwitch);
        recoverySwitch.setOnCheckedChangeListener((view, enabled) -> {
            handler.removeCallbacks(autoClose);
            RecoveryScheduler.setEnabled(this, enabled);
            updateConnection();
        });
        operations.add(button(settingsPanel, "开启／检查自动恢复助手", v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException unavailable) { showSettingsHint(); }
        }));
        text(settingsPanel, "自动恢复需保留“双击相机：自动恢复助手”的无障碍开关，并在系统中允许本应用自启动。助手待机时不读取窗口；需要时只点网络授权的“允许”，不勾“始终允许”。重启后首次解锁，再连 Wi-Fi 即可恢复。", 14);
        permissionStatus = text(settingsPanel, "", 14);
        connectPort = new EditText(this);
        connectPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectPort.setSingleLine(true);
        connectPort.setHint("连接端口（通常留空，自动发现）");
        settingsPanel.addView(connectPort);
        text(settingsPanel, "自动发现失败时，填写无线调试主页面的连接端口。此处不是配对码窗口的端口。\n\n点击启动时，应用会开启并保留“USB 调试”，让关闭 Wi-Fi 后的 1.0 继续运行，不必插线。停止和刷新不会修改此开关。\n\n助手只在配对或网络授权时读取系统窗口，其他时间待机；日常双击由 1.0 独立运行。", 14);
        detail = text(settingsPanel, "尚无运行记录。", 13);
        detail.setTextIsSelectable(true);
        detail.setVisibility(View.GONE);
        button(settingsPanel, "显示／隐藏运行记录", v -> detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        setContentView(scroll);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        updateConnection();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); parent.addView(text); return text;
    }
    private Button button(LinearLayout parent, String value, View.OnClickListener action) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setOnClickListener(action); parent.addView(button); return button;
    }

    private void primaryButton(LinearLayout row, String label, View.OnClickListener action) {
        Button button = button(row, label, action);
        button.setTextSize(15); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(2), dp(8), dp(2), dp(8));
        button.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        operations.add(button);
    }
    private void setSettingsExpanded(boolean expanded) {
        settingsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        settingsToggle.setText(expanded ? "配对与设置  ▴" : "配对与设置  ▾");
    }
    private void showSettingsHint() {
        new AlertDialog.Builder(this).setMessage("请在系统设置中打开“开发者选项 → 无线调试”。")
                .setPositiveButton("知道了", null).show();
    }

    private void showAutoPairing() {
        if (busy || pairingActive()) return;
        new AlertDialog.Builder(this).setTitle("自动完成首次配对")
                .setMessage("首次需要在无障碍设置中开启“双击相机：自动恢复助手”。助手会跳转无线调试；请您开启无线调试，助手会尝试仅允许本次网络调试。\n\n之后自动打开配对码窗口、完成配对，无需分屏或填写端口。完成后助手回到待机，不拦截按键，不持续扫描窗口。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    PairingSession.begin(getApplicationContext());
                    updateConnection();
                    if (!PairingSession.isServiceConnected()) {
                        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                        catch (ActivityNotFoundException | SecurityException unavailable) {
                            pairingMessage.setText("请在系统无障碍设置中开启“双击相机：自动恢复助手”。");
                        }
                    }
                }).show();
    }

    private void showPairing() {
        if (busy || pairingActive()) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), 0);
        text(form, "在系统“无线调试”中选择“使用配对码配对设备”。保持该窗口打开，用分屏在这里填写。\n\n这里要填配对窗口的端口，不是无线调试主页面的连接端口。", 16);
        EditText port = new EditText(this); port.setHint("配对端口"); port.setInputType(InputType.TYPE_CLASS_NUMBER); form.addView(port);
        EditText code = new EditText(this); code.setHint("6 位配对码"); code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); form.addView(code);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("仅与本机无线调试配对").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("配对", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int pairingPort;
            try { pairingPort = parsePort(port.getText().toString(), false); }
            catch (IllegalArgumentException error) { port.setError("请输入 1–65535 的端口"); return; }
            String pairingCode = code.getText().toString().trim();
            if (!pairingCode.matches("[0-9]{6}")) { code.setError("请输入 6 位数字"); return; }
            code.setText("");
            dialog.dismiss();
            manualPairing = true;
            setBusy(true); updateConnection();
            worker.execute(() -> {
                try {
                    LocalAdbManager adb = getManager();
                    if (!adb.pairLocal(pairingPort, pairingCode)) throw new IOException("Pairing was not accepted");
                    preferences.edit().putBoolean("paired", true).putString("pair_status", "success")
                            .putString("pair_message", "配对成功，可直接使用上方启停按钮。")
                            .putLong("pair_update", System.currentTimeMillis()).commit();
                    handler.post(() -> {
                        if (destroyed) return;
                        manualPairing = false;
                        setBusy(false); updateConnection(); refreshIfNeeded();
                    });
                } catch (Exception error) {
                    Log.e(TAG, "Manual pairing failed", error);
                    handler.post(() -> {
                        if (destroyed) return;
                        manualPairing = false;
                        preferences.edit().putString("pair_status", "failed")
                                .putString("pair_message", "本次配对未完成，请确认配对窗口仍打开、端口和配对码正确。")
                                .putLong("pair_update", System.currentTimeMillis()).apply();
                        setBusy(false); updateConnection();
                        detail.setText(error.getClass().getSimpleName() + ": " + error.getMessage());
                        detail.setVisibility(View.VISIBLE);
                    });
                }
            });
        }));
        dialog.show();
    }

    private static int parsePort(String value, boolean allowEmpty) {
        String trimmed = value.trim();
        if (allowEmpty && trimmed.isEmpty()) return 0;
        int port = Integer.parseInt(trimmed);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return port;
    }

    private LocalAdbManager getManager() throws Exception {
        if (manager == null) manager = new LocalAdbManager(getApplicationContext());
        return manager;
    }

    private void closeControlInterface() {
        if (busy || pairingActive()) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                ControllerRuntime.closeInterface(getApplicationContext());
                handler.post(() -> { if (!destroyed) finishAndRemoveTask(); });
            } catch (Exception error) {
                handler.post(() -> {
                    if (destroyed) return;
                    setBusy(false);
                    new AlertDialog.Builder(this).setTitle("无线调试未确认关闭")
                            .setMessage("界面保持打开。请确认 USB 调试已开启，手动关闭无线调试后重试。监听器未被停止。")
                            .setPositiveButton("知道了", null).show();
                });
            }
        });
    }

    private void runAction(String action) {
        if (busy || pairingActive()) return;
        handler.removeCallbacks(autoClose);
        if (!preferences.getBoolean("paired", false)) { showAutoPairing(); return; }
        final int port;
        try { port = parsePort(connectPort.getText().toString(), true); }
        catch (IllegalArgumentException error) { setSettingsExpanded(true); connectPort.setError("连接端口应为 1–65535，也可以留空"); return; }
        RecoveryScheduler.manualAction(this, action);
        checkedThisResume = true;
        checkedPairUpdate = preferences.getLong("pair_update", 0);
        setBusy(true);
        showUnknown("start".equals(action) ? "正在连接并启动 1.0…" : "stop".equals(action) ? "正在停止 1.0…" : "正在查询状态…");
        worker.execute(() -> {
            try {
                JSONObject result = ControllerRuntime.execute(getApplicationContext(), action, port, () -> {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Controller action interrupted");
                });
                if (result.optBoolean("ok") && "RUNNING".equals(result.optString("state"))) RecoveryScheduler.success(this);
                handler.post(() -> { if (!destroyed) render(result); });
            } catch (Exception error) {
                reportError("操作未完成，未自动关闭界面。请检查 Wi-Fi、恢复助手及调试授权；监听器可能仍在运行。", error);
            }
        });
    }

    private void renderRecovered() {
        try {
            render(new JSONObject().put("ok", true).put("state", "RUNNING")
                    .put("message", "已确认运行，无线调试已关闭，USB 调试保留。").put("log", ""));
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private void render(JSONObject result) {
        checkedThisResume = true;
        checkedPairUpdate = preferences.getLong("pair_update", 0);
        setBusy(false); updateConnection();
        String actual = result.optString("state", "UNKNOWN");
        boolean running = "RUNNING".equals(actual);
        boolean stopped = "STOPPED".equals(actual);
        if (running || stopped) {
            state.setText(running ? "正在运行" : "已停止");
            state.setTextColor(running ? Color.rgb(24, 112, 57) : Color.DKGRAY);
            stateMessage.setText(running ? "双击电源键即可打开相机。" : "点击“启动 1.0”恢复双击相机。");
            if (!result.optBoolean("ok")) stateMessage.append("\n本次操作未完成，当前状态已重新确认。");
        } else showUnknown("暂时无法确认监听状态。请检查 Wi-Fi 和无线调试，再刷新。");
        detail.setText(result.optString("message") + "\n\n" + result.optString("log"));
        if (!result.optBoolean("ok")) detail.setVisibility(View.VISIBLE);
        handler.removeCallbacks(autoClose);
        if (ControllerFlow.shouldClose(result.optBoolean("ok"), running)) {
            stateMessage.setText("运行正常；无线调试已关闭，USB 调试保留。1 秒后关闭控制界面。");
            handler.postDelayed(autoClose, ControllerFlow.CLOSE_DELAY_MS);
        }
    }

    private void showUnknown(String message) {
        state.setText("状态未确认"); state.setTextColor(Color.DKGRAY); stateMessage.setText(message);
    }

    private void reportError(String message, Exception error) {
        Log.e(TAG, message, error);
        handler.post(() -> {
            if (destroyed) return;
            checkedThisResume = true;
            checkedPairUpdate = preferences.getLong("pair_update", 0);
            setBusy(false); updateConnection();
            showUnknown(message);
            detail.setText(error.getClass().getSimpleName() + ": " + error.getMessage()); detail.setVisibility(View.VISIBLE);
        });
    }

    private boolean pairingActive() { return manualPairing || PairingSession.isActive(); }
    private void setBusy(boolean value) { if (value) handler.removeCallbacks(autoClose); busy = value; updateControls(); }
    private void updateControls() {
        boolean enabled = !busy && !pairingActive();
        for (Button button : operations) button.setEnabled(enabled);
        if (connectPort != null) connectPort.setEnabled(enabled);
        if (recoverySwitch != null) recoverySwitch.setEnabled(enabled);
    }
    private void updateConnection() {
        boolean paired = preferences.getBoolean("paired", false);
        String status = preferences.getString("pair_status", "");
        String message = preferences.getString("pair_message", "");
        if (manualPairing) {
            connection.setText("正在配对"); pairingMessage.setText("正在与本机无线调试配对…");
        } else if ("pending".equals(status) || "pairing".equals(status)) {
            connection.setText("pending".equals(status) ? "等待配对" : "正在配对");
            pairingMessage.setText(message);
        } else {
            connection.setText(paired ? "已配对" : "尚未配对");
            if ("success".equals(status)) pairingMessage.setText("配对已完成，可直接使用上方启停按钮。");
            else if (!message.isEmpty()) pairingMessage.setText(message);
            else pairingMessage.setText(paired ? "配对身份已保存，无需重复配对。" : "展开“配对与设置”完成首次配对。");
        }
        autoPairButton.setText(paired ? "重新配对（通常无需）" : "首次自动配对");
        boolean automatic = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(automatic ? (RecoveryScheduler.assistantEnabled(this) ? "自动恢复授权已就绪。" : "调试权限已就绪，请开启自动恢复助手。") : "首次连接前，请手动开启无线调试。");
        updateControls();
    }
    private void refreshIfNeeded() {
        if (!resumed || destroyed || busy || pairingActive() || !preferences.getBoolean("paired", false)) return;
        if (RecoveryScheduler.enabled(this) && !RecoveryScheduler.assistantEnabled(this)) {
            setSettingsExpanded(true);
            showUnknown("请先开启自动恢复助手，并允许本应用自启动；助手待机时不读取窗口。");
            return;
        }
        if (RecoveryScheduler.recentlyVerified(this) && preferences.getBoolean("restore_ok", false)) {
            renderRecovered();
            return;
        }
        if (RecoveryScheduler.needed(this)) {
            RecoveryScheduler.schedule(this);
            showUnknown(preferences.getString("restore_message", "等待 Wi-Fi 自动恢复。"));
            return;
        }
        long update = preferences.getLong("pair_update", 0);
        boolean newSuccess = "success".equals(preferences.getString("pair_status", "")) && update != checkedPairUpdate;
        if (checkedThisResume && !newSuccess) return;
        // Mark before starting so preference callbacks cannot form a refresh loop.
        checkedThisResume = true;
        checkedPairUpdate = update;
        runAction("status");
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (connection != null) updateConnection();
        // onResume follows this callback, including singleTask returns from Settings.
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        RecoveryScheduler.onUserPresent(this);
        checkedThisResume = false;
        PairingSession.recoverInterrupted(getApplicationContext());
        if (connection == null) return;
        updateConnection(); refreshIfNeeded();
    }
    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(autoClose);
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        handler.removeCallbacksAndMessages(null);
        worker.execute(() -> { if (manager != null) try { manager.close(); } catch (IOException ignored) { } });
        worker.shutdown();
        super.onDestroy();
    }
}
