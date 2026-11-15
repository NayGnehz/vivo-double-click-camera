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
    private LinearLayout advancedPanel;
    private Button advancedToggle;
    private EditText connectPort;
    private Switch recoverySwitch;
    private final Runnable autoClose = () -> {
        if (!this.destroyed && this.resumed && !this.busy && !pairingActive()) finishAndRemoveTask();
    };
    private boolean busy;
    private boolean manualPairing;
    private boolean resumed;
    private SharedPreferences preferences;
    private volatile boolean destroyed;
    private final Runnable pairingUpdate = () -> {
        if (!destroyed) { updateConnection(); refreshIfNeeded(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if ("restore_update".equals(key)) handler.post(() -> {
            if (destroyed || busy || pairingActive()) return;
            detail.setText(preferences.getString("restore_message", "暂无记录。"));
            if (preferences.getBoolean("restore_ok", false) && RecoveryScheduler.recentlyVerified(this)) renderRecovered();
            else refreshIfNeeded();
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
        state = text(panel, "待启动", 34);
        state.setTypeface(null, Typeface.BOLD);
        state.setPadding(0, dp(20), 0, dp(4));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateMessage = text(panel, "启动后，双击电源键打开相机。", 16);
        stateMessage.setPadding(0, 0, 0, dp(18));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(actions);
        primaryButton(actions, "启动", v -> runAction("start"));
        primaryButton(actions, "停止", v -> runAction("stop"));
        text(panel, "关闭界面后，双击相机继续运行。", 14).setTextColor(Color.DKGRAY);
        operations.add(button(panel, "关闭控制界面", v -> closeControlInterface()));
        settingsToggle = button(panel, "设置  ▾", v -> setSettingsExpanded(settingsPanel.getVisibility() != View.VISIBLE));
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setVisibility(View.GONE);
        panel.addView(settingsPanel);
        connection = text(settingsPanel, "", 16);
        connection.setTypeface(null, Typeface.BOLD);
        pairingMessage = text(settingsPanel, "", 14);
        pairingMessage.setTextColor(Color.DKGRAY);
        pairingMessage.setPadding(0, dp(4), 0, dp(12));
        autoPairButton = button(settingsPanel, "首次配对", v -> showAutoPairing());
        operations.add(autoPairButton);
        recoverySwitch = new Switch(this);
        recoverySwitch.setText("开机联网后自动启动");
        recoverySwitch.setChecked(RecoveryScheduler.enabled(this));
        settingsPanel.addView(recoverySwitch);
        recoverySwitch.setOnCheckedChangeListener((view, enabled) -> {
            handler.removeCallbacks(autoClose);
            RecoveryScheduler.setEnabled(this, enabled);
            updateConnection();
        });
        operations.add(button(settingsPanel, "自动恢复权限", v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException unavailable) { showSettingsHint(); }
        }));
        text(settingsPanel, "请允许应用自启动，并保持恢复助手开启。助手仅在配对和网络授权时读取窗口。", 14);
        permissionStatus = text(settingsPanel, "", 14);
        advancedToggle = button(settingsPanel, "故障排查  ▾", v -> {
            handler.removeCallbacks(autoClose);
            boolean open = advancedPanel.getVisibility() != View.VISIBLE;
            advancedPanel.setVisibility(open ? View.VISIBLE : View.GONE);
            advancedToggle.setText(open ? "故障排查  ▴" : "故障排查  ▾");
        });
        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);
        settingsPanel.addView(advancedPanel);
        operations.add(button(advancedPanel, "检查实时状态", v -> runAction("status")));
        operations.add(button(advancedPanel, "手动配对", v -> showPairing()));
        operations.add(button(advancedPanel, "开发者选项", v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException unavailable) { showSettingsHint(); }
        }));
        operations.add(button(advancedPanel, "无线调试设置", v -> {
            if (!SettingsNavigation.openWireless(this)) showSettingsHint();
        }));
        connectPort = new EditText(this);
        connectPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectPort.setSingleLine(true);
        connectPort.setHint("连接端口（通常留空，自动发现）");
        advancedPanel.addView(connectPort);
        text(advancedPanel, "连接端口通常留空。配对码窗口中的端口不能用于连接。", 14);
        text(advancedPanel, "检查实时状态需要临时开启无线调试。检查正常后会关闭无线调试，保留 USB 调试。", 14);
        detail = text(advancedPanel, preferences.getString("restore_message", "暂无记录。"), 13);
        detail.setTextIsSelectable(true);
        detail.setVisibility(View.GONE);
        button(advancedPanel, "查看运行记录", v -> detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::closeControlInterface);
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
        if (expanded) handler.removeCallbacks(autoClose);
        settingsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        settingsToggle.setText(expanded ? "设置  ▴" : "设置  ▾");
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
                        setSettingsExpanded(true);
            advancedPanel.setVisibility(View.VISIBLE);
            advancedToggle.setText("故障排查  ▴");
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
        setBusy(true);
        showUnknown("start".equals(action) ? "正在启动…" : "stop".equals(action) ? "正在停止…" : "正在查询状态…");
        worker.execute(() -> {
            try {
                JSONObject result = ControllerRuntime.execute(getApplicationContext(), action, port, () -> {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Controller action interrupted");
                });
                if (result.optBoolean("ok") && "RUNNING".equals(result.optString("state"))) RecoveryScheduler.success(this);
                if ("STOPPED".equals(result.optString("state"))) RecoveryScheduler.stopped(this);
                handler.post(() -> { if (!destroyed) render(result); });
            } catch (Exception error) {
                reportError("操作未完成。请检查 Wi-Fi 与调试授权，详情见设置。", error);
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
        setBusy(false); updateConnection();
        String actual = result.optString("state", "UNKNOWN");
        boolean running = "RUNNING".equals(actual);
        boolean stopped = "STOPPED".equals(actual);
        if (running || stopped) {
            state.setText(running ? "正在运行" : "已停止");
            state.setTextColor(running ? Color.rgb(24, 112, 57) : Color.DKGRAY);
            stateMessage.setText(running ? "双击电源键即可打开相机。" : "点击“启动”开启双击相机。");
            if (!result.optBoolean("ok")) stateMessage.append("\n本次操作未完成，当前状态已重新确认。");
        } else showSavedState("本次检查未完成，请在设置中查看详情。");
        if (!result.optBoolean("ok")) setSettingsExpanded(true);
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
            setBusy(false); updateConnection();
            showSavedState(message);
            setSettingsExpanded(true);
            advancedPanel.setVisibility(View.VISIBLE);
            advancedToggle.setText("故障排查  ▴");
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
            if ("success".equals(status)) pairingMessage.setText("配对已完成。");
            else if (!message.isEmpty()) pairingMessage.setText(message);
            else pairingMessage.setText(paired ? "配对身份已保存，无需重复配对。" : "在设置中完成首次配对。");
        }
        autoPairButton.setText(paired ? "重新配对" : "首次配对");
        pairingMessage.setVisibility(pairingActive() || "failed".equals(status) ? View.VISIBLE : View.GONE);
        boolean automatic = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(automatic ? (RecoveryScheduler.assistantEnabled(this) ? "自动恢复授权已就绪。" : "调试权限已就绪，请开启自动恢复助手。") : "首次连接前，请手动开启无线调试。");
        updateControls();
    }
    private void showSavedState(String message) {
        ControlStatePolicy.Saved saved = RecoveryScheduler.savedState(this);
        state.setText(saved == ControlStatePolicy.Saved.STARTED ? "已启动" : saved == ControlStatePolicy.Saved.STOPPED ? "已停止" : "待启动");
        state.setTextColor(saved == ControlStatePolicy.Saved.STARTED ? Color.rgb(24,112,57) : Color.DKGRAY);
        String hint = saved == ControlStatePolicy.Saved.STARTED ? "本次开机已确认启动。实时检查见设置。"
                : saved == ControlStatePolicy.Saved.STOPPED ? "点击“启动”开启双击相机。" : "连接 Wi-Fi 后点击“启动”。";
        stateMessage.setText(message == null ? hint : message);
    }
    private void refreshIfNeeded() {
        if (!resumed || destroyed || busy || pairingActive()) return;
        if (!preferences.getBoolean("paired", false)) {
            state.setText("首次使用");
            stateMessage.setText("在设置中完成一次配对，即可使用。");
            setSettingsExpanded(true);
            return;
        }
        // Opening the UI is passive: it neither toggles debugging nor starts an
        // authorization session. A saved launch is explicitly labelled as such.
        showSavedState(null);
        if (RecoveryScheduler.savedState(this) == ControlStatePolicy.Saved.UNKNOWN) {
            if (RecoveryScheduler.needed(this)) {
                RecoveryScheduler.schedule(this);
                stateMessage.setText(preferences.getString("restore_message", "等待 Wi-Fi 自动启动。"));
            } else if (RecoveryScheduler.enabled(this) && !RecoveryScheduler.assistantEnabled(this)) {
                stateMessage.setText("自动启动需要开启恢复助手，也可以手动启动。");
            }
        }
    }
    @Override public void onBackPressed() { closeControlInterface(); }
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
