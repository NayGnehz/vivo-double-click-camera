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
        TextView exitHint = text(panel, "关闭界面不停止 1.0；USB 调试已开启时，会先关闭无线调试并保留 USB 调试，不必插线。手机重启后需连接 Wi-Fi，再点启动。", 14);
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
        permissionStatus = text(settingsPanel, "", 14);
        connectPort = new EditText(this);
        connectPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectPort.setSingleLine(true);
        connectPort.setHint("连接端口（通常留空，自动发现）");
        settingsPanel.addView(connectPort);
        text(settingsPanel, "自动发现失败时，填写无线调试主页面的连接端口。此处不是配对码窗口的端口。\n\n点击启动时，应用会开启并保留“USB 调试”，让关闭 Wi-Fi 后的 1.0 继续运行，不必插线。停止和刷新不会修改此开关。\n\n配对助手只用于首次配置，成功后自行关闭；日常双击由 1.0 独立运行。", 14);
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
                .setMessage("首次需要在无障碍设置中开启“双击相机：首次配对助手”。助手会跳转无线调试；请您开启无线调试，并确认系统询问的网络授权。\n\n之后自动打开配对码窗口、完成配对，无需分屏或填写端口。成功后助手自行关闭。它不拦截任何按键。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    PairingSession.begin(getApplicationContext());
                    updateConnection();
                    if (!PairingSession.isServiceConnected()) {
                        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                        catch (ActivityNotFoundException | SecurityException unavailable) {
                            pairingMessage.setText("请在系统无障碍设置中开启“双击相机：首次配对助手”。");
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
                DebuggingExitPolicy.prepareToClose(new DebuggingExitPolicy.SettingsAccess() {
                    @Override public int readUsb() {
                        return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, -1);
                    }
                    @Override public int readWireless() {
                        return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", -1);
                    }
                    @Override public boolean disableWireless() {
                        return Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
                    }
                    @Override public void waitForChange() throws InterruptedException {
                        Thread.sleep(250);
                    }
                });
                // Readback uses Settings directly: disabling wireless ADB may close its connection.
                handler.post(() -> { if (!destroyed) finishAndRemoveTask(); });
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                Log.w(TAG, "Could not prepare debugging settings for UI exit", error);
                handler.post(() -> {
                    if (destroyed) return;
                    setBusy(false);
                    new AlertDialog.Builder(this).setTitle("无线调试未确认关闭")
                            .setMessage("控制界面保持打开。请确认“USB 调试”已开启，并在开发者选项中手动关闭“无线调试”，然后重试。监听器未被停止。")
                            .setPositiveButton("知道了", null).show();
                });
            }
        });
    }

    private void runAction(String action) {
        if (busy || pairingActive()) return;
        if (!preferences.getBoolean("paired", false)) { showAutoPairing(); return; }
        final int port;
        try { port = parsePort(connectPort.getText().toString(), true); }
        catch (IllegalArgumentException error) { setSettingsExpanded(true); connectPort.setError("连接端口应为 1–65535，也可以留空"); return; }
        checkedThisResume = true;
        checkedPairUpdate = preferences.getLong("pair_update", 0);
        setBusy(true);
        showUnknown("start".equals(action) ? "正在连接并启动 1.0…" : "stop".equals(action) ? "正在连接并停止 1.0…" : "正在查询运行状态…");
        worker.execute(() -> {
            try {
                LocalAdbManager adb = getManager();
                if (!adb.isConnected()) {
                    if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
                        Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                    if (!adb.connectLocal(port)) throw new IOException("No local wireless debugging connection found");
                }
                AdbShell shell = new AdbShell(adb);
                DeviceController.Result identity = shell.run("id -u", 6);
                if (identity.exitCode != 0 || !"2000".equals(identity.output.trim())) throw new IOException("The ADB connection is not shell UID 2000");
                if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                    DeviceController.Result permission = shell.run("pm grant local.vivodoubleclick.controller android.permission.WRITE_SECURE_SETTINGS", 8);
                    if (permission.exitCode != 0) Log.w(TAG, "Automatic wireless-debugging permission was not granted");
                }
                // Only an explicit Start may change USB debugging, after confirming shell UID 2000.
                if ("start".equals(action)) ensureUsbDebugging(shell);
                DeviceController controller = new DeviceController(getApplicationContext(), shell);
                JSONObject result = "start".equals(action) ? controller.start() : "stop".equals(action) ? controller.stop() : controller.status();
                Log.i(TAG, "RESULT " + result);
                handler.post(() -> { if (!destroyed) render(result); });
            } catch (Exception error) {
                try { if (manager != null) manager.disconnect(); } catch (IOException ignored) { }
                reportError(error instanceof UsbDebuggingException ? error.getMessage()
                        : "操作未完成。请确认 Wi-Fi 已连接、无线调试已开启。自动发现失败时可填写主页面的连接端口。", error);
            }
        });
    }

    private void ensureUsbDebugging(AdbShell shell) throws UsbDebuggingException {
        try {
            if (readUsbDebugging(shell)) return;
            if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                boolean accepted = false;
                try { accepted = Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 1); }
                catch (SecurityException denied) { Log.w(TAG, "USB debugging setting write was denied"); }
                if (!accepted) Log.w(TAG, "USB debugging setting write was not accepted");
                // A successful setter is not sufficient: the framework observer can revert the value.
                if (awaitUsbDebugging(shell)) return;
            }
            // Avoid writing again if a delayed observer or the user has already enabled it.
            if (readUsbDebugging(shell)) return;
            DeviceController.Result setting = shell.run("settings put global adb_enabled 1", 6);
            if (setting.exitCode != 0) throw new IOException("USB debugging shell setting was rejected");
            if (!awaitUsbDebugging(shell)) throw new IOException("USB debugging did not remain enabled after readback");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UsbDebuggingException(failure);
        }
    }

    private boolean readUsbDebugging(AdbShell shell) throws Exception {
        try {
            int enabled = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, -1);
            if (enabled == 0 || enabled == 1) return enabled == 1;
        } catch (SecurityException denied) {
            // Some firmware restricts the app-side read; the already verified shell can read it.
        }
        DeviceController.Result setting = shell.run("settings get global adb_enabled", 4);
        String value = setting.output.trim();
        if (setting.exitCode != 0 || !("0".equals(value) || "1".equals(value)))
            throw new IOException("Could not read the USB debugging setting");
        return "1".equals(value);
    }

    private boolean awaitUsbDebugging(AdbShell shell) throws Exception {
        int confirmed = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            Thread.sleep(250);
            confirmed = readUsbDebugging(shell) ? confirmed + 1 : 0;
            if (confirmed == 2) return true;
        }
        return false;
    }

    private static final class UsbDebuggingException extends IOException {
        UsbDebuggingException(Exception cause) {
            super("本次启动未执行：无法确认“USB 调试”已开启。请在开发者选项中手动开启“USB 调试”，不必插线，然后返回重试。现有监听状态尚未确认。", cause);
        }
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
    private void setBusy(boolean value) { busy = value; updateControls(); }
    private void updateControls() {
        boolean enabled = !busy && !pairingActive();
        for (Button button : operations) button.setEnabled(enabled);
        if (connectPort != null) connectPort.setEnabled(enabled);
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
        permissionStatus.setText(automatic ? "无线调试启动权限已就绪。" : "首次连接前，请手动开启无线调试。");
        updateControls();
    }
    private void refreshIfNeeded() {
        if (!resumed || destroyed || busy || pairingActive() || !preferences.getBoolean("paired", false)) return;
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
        checkedThisResume = false;
        PairingSession.recoverInterrupted(getApplicationContext());
        if (connection == null) return;
        updateConnection(); refreshIfNeeded();
    }
    @Override protected void onPause() {
        resumed = false;
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
