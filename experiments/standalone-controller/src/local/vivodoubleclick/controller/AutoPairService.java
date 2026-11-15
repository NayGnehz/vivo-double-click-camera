package local.vivodoubleclick.controller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.res.Resources;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import java.lang.ref.WeakReference;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-shot Settings UI assistance. It neither filters keys nor operates the camera daemon. */
public final class AutoPairService extends AccessibilityService {
    private static final String SETTINGS = "com.android.settings";
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final Pattern ADDRESS = Pattern.compile(
            "^(?:(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|\\[[0-9a-fA-F:.%]+\\]):([0-9]{1,5})$");
    private final Handler main = new Handler(Looper.getMainLooper());
    private long launchedToken;
    private long clickedToken;
    private long lastScan;
    private long incompleteToken;
    private long incompleteSince;
    private boolean connected;
    private static WeakReference<AutoPairService> live = new WeakReference<>(null);
    private WifiApprovalUi approvalUi;
    private ConnectivityManager connectivity;
    private boolean networkRegistered;
    private boolean unlockRegistered;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { RecoveryScheduler.onNetworkAvailable(AutoPairService.this, network); }
    };
    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            RecoveryScheduler.onUserPresent(context);
            if (WifiApprovalSession.active()) main.post(scanTask);
        }
    };
    static void refreshConnected() {
        AutoPairService service = live.get();
        if (service != null) service.main.post(service::updateWorkState);
    }
    void updateWorkState() {
        if (!connected) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityWorkPolicy.needsWindowEvents(PairingSession.isActive(), WifiApprovalSession.active())
                    ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED : 0;
            info.packageNames = WifiApprovalSession.active() ? new String[]{SETTINGS, "com.android.systemui"} : new String[]{SETTINGS};
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        if (RecoveryScheduler.enabled(this) && !networkRegistered && connectivity != null) {
            NetworkRequest wifi = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connectivity.registerNetworkCallback(wifi, networkCallback, main);
            networkRegistered = true;
        } else if (!RecoveryScheduler.enabled(this) && networkRegistered) {
            connectivity.unregisterNetworkCallback(networkCallback);
            networkRegistered = false;
        }
        main.removeCallbacks(scanTask);
        if (PairingSession.isActive() || WifiApprovalSession.active()) main.post(scanTask);
    }
    private Resources settingsResources;
    private final Runnable scanTask = this::scan;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[] { SETTINGS };
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        try { settingsResources = getPackageManager().getResourcesForApplication(SETTINGS); }
        catch (Exception unavailable) { settingsResources = null; }
        connected = true;
        live = new WeakReference<>(this);
        approvalUi = new WifiApprovalUi(this, main);
        connectivity = getSystemService(ConnectivityManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));
        unlockRegistered = true;
        PairingSession.attach(this);
        updateWorkState();
        RecoveryScheduler.schedule(this);
    }

    void scheduleLaunch(long token) {
        // Let the user's accessibility-enable confirmation finish before navigating.
        main.postDelayed(() -> {
            if (!connected || !PairingSession.isWaiting(token) || launchedToken == token) return;
            launchedToken = token;
            if (!SettingsNavigation.openWireless(this)) {
                PairingSession.finish(token, "failed", "无法打开无线调试设置，请手动打开后重新发起配对。", false);
                return;
            }
            PairingSession.progress(token, "waiting_wireless",
                    "已打开无线调试设置。请开启无线调试；助手会尝试仅允许本次网络调试，并自动打开配对窗口。");
            main.removeCallbacks(scanTask);
            main.postDelayed(scanTask, 500);
        }, 500);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!connected || event == null) return;
        String owner = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!(PairingSession.isActive() && SETTINGS.equals(owner))
                && !(WifiApprovalSession.active() && WifiApprovalUi.packageAllowed(owner))) return;
        main.removeCallbacks(scanTask);
        main.postDelayed(scanTask, 100);
    }

    private void scan() {
        if (connected && WifiApprovalSession.active()) approvalUi.scan();
        long token = PairingSession.activeToken();
        if (!connected || !PairingSession.isWaiting(token)) return;
        lastScan = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            if (root == null || !SETTINGS.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
            Snapshot snapshot = new Snapshot();
            collect(root, snapshot, 0);
            Credentials credentials = snapshot.credentials();
            if (credentials != null) {
                pair(token, credentials);
                return;
            }
            if (snapshot.dialogTitle || snapshot.codeLabel) {
                PairingSession.progress(token, "pairing_window",
                        "已打开系统配对窗口，正在识别配对信息…");
                if (incompleteToken != token) {
                    incompleteToken = token;
                    incompleteSince = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - incompleteSince >= 6_000L) {
                    PairingSession.finish(token, "failed",
                            "无法确认系统配对窗口中的配对码或配对端口。未使用主页面的连接端口，请手动配对或重试。", false);
                    return;
                }
            } else {
                incompleteToken = 0;
            }
            // Never click anything in a pairing dialog or a network-trust confirmation.
            if (!snapshot.dialogTitle && !snapshot.codeLabel && clickedToken != token
                    && clickPairingEntry(root, 0)) {
                clickedToken = token;
                main.postDelayed(scanTask, 150);
                PairingSession.progress(token, "opening_pairing_window", "已点击配对入口，正在等待系统配对窗口…");
            }
        } catch (RuntimeException staleWindow) {
            // A Settings window can disappear during traversal. Wait for a fresh tree.
        } finally {
            if (root != null) root.recycle();
        }
    }

    private void pair(long token, Credentials credentials) {
        if (!PairingSession.claim(token)) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            PairingSession.finish(token, "failed", "上一次配对连接尚在结束，请稍后重新发起。", false);
            return;
        }
        final android.content.Context app = getApplicationContext();
        Thread worker = new Thread(() -> {
            LocalAdbManager manager = null;
            boolean success = false;
            try {
                if (!PairingSession.isCurrent(token)) return;
                manager = new LocalAdbManager(app);
                if (PairingSession.isCurrent(token))
                    success = manager.pairLocal(credentials.port, credentials.code);
            } catch (Exception failure) {
                // Do not log, persist, or display exception data from the credential exchange.
            } finally {
                if (manager != null) try { manager.close(); } catch (IOException ignored) { }
                IN_FLIGHT.set(false);
            }
            PairingSession.finish(token, success ? "success" : "failed",
                    success ? "配对成功，一次性配对辅助已关闭。现在可以启动或停止 1.0。"
                            : "配对未完成。请确认无线调试已开启，并重新打开系统配对码窗口后再试。", success);
        }, "vivo-local-pair");
        worker.setDaemon(true);
        worker.start();
    }

    private void collect(AccessibilityNodeInfo node, Snapshot snapshot, int depth) {
        if (depth > 32 || ++snapshot.visited > 512) return;
        if (node.isVisibleToUser()) {
            String text = text(node);
            String id = node.getViewIdResourceName();
            boolean codeId = id != null && id.endsWith(":id/pairing_code");
            boolean portId = id != null && id.endsWith(":id/pairing_code_port");
            if (matches(text, "adb_pairing_device_dialog_title", "与设备配对", "配对设备", "Pair with device"))
                snapshot.dialogTitle = true;
            if (matches(text, "adb_pairing_device_dialog_pairing_code_label",
                    "Wi-Fi 配对码", "WLAN 配对码", "Wi-Fi pairing code")) snapshot.codeLabel = true;
            if (isPairingEntry(text)) snapshot.pairingEntry = true;
            String digits = text.replaceAll("\\s", "");
            if (digits.matches("[0-9]{6}")) {
                snapshot.codes.add(digits);
                if (codeId) snapshot.identifiedCodes.add(digits);
            }
            Matcher address = ADDRESS.matcher(text.trim());
            if (address.matches()) {
                int port = Integer.parseInt(address.group(1));
                if (port >= 1 && port <= 65535) {
                    snapshot.ports.add(port);
                    if (portId) snapshot.identifiedPorts.add(port);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child, snapshot, depth + 1); }
            finally { child.recycle(); }
        }
    }

    private boolean clickPairingEntry(AccessibilityNodeInfo node, int depth) {
        if (depth > 24) return false;
        if (node.isVisibleToUser() && isPairingEntry(text(node))) {
            AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
            try {
                for (int level = 0; target != null && level < 5; level++) {
                    if (!SETTINGS.contentEquals(target.getPackageName() == null ? "" : target.getPackageName())) break;
                    if (target.isVisibleToUser() && target.isEnabled() && target.isClickable())
                        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    AccessibilityNodeInfo parent = target.getParent();
                    target.recycle();
                    target = parent;
                }
            } finally { if (target != null) target.recycle(); }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (clickPairingEntry(child, depth + 1)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private boolean isPairingEntry(String text) {
        return matches(text, "adb_pair_method_code_title", "使用配对码配对设备", "通过配对码配对设备",
                "Pair device with pairing code")
                || matches(text, "adb_pair_method_code_summary", "使用六位数代码配对新设备",
                "Pair new devices using a six-digit code");
    }

    private boolean matches(String value, String resource, String... alternatives) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        if (settingsResources != null) {
            int id = settingsResources.getIdentifier(resource, "string", SETTINGS);
            if (id != 0) {
                try { if (normalized.equals(normalize(settingsResources.getString(id)))) return true; }
                catch (Resources.NotFoundException ignored) { }
            }
        }
        for (String alternative : alternatives) if (normalized.equals(normalize(alternative))) return true;
        return false;
    }

    private static String text(AccessibilityNodeInfo node) {
        CharSequence value = node.getText();
        if (value == null || value.length() == 0) value = node.getContentDescription();
        return value == null ? "" : value.toString();
    }

    private static String normalize(String value) {
        return value.replace('\u2011', '-').replace('\u2010', '-').replace("\u200e", "")
                .replace("\u200f", "").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final class Snapshot {
        int visited;
        boolean dialogTitle;
        boolean codeLabel;
        boolean pairingEntry;
        final Set<String> codes = new HashSet<>();
        final Set<Integer> ports = new HashSet<>();
        final Set<String> identifiedCodes = new HashSet<>();
        final Set<Integer> identifiedPorts = new HashSet<>();

        Credentials credentials() {
            if (!dialogTitle && !codeLabel) return null;
            if (identifiedCodes.size() == 1 && identifiedPorts.size() == 1)
                return new Credentials(identifiedCodes.iterator().next(), identifiedPorts.iterator().next());
            // Without resource IDs, require both dialog-specific labels and a unique
            // code/address inside this active Settings window, with no underlying entry.
            if (dialogTitle && codeLabel && !pairingEntry && codes.size() == 1 && ports.size() == 1)
                return new Credentials(codes.iterator().next(), ports.iterator().next());
            return null;
        }
    }

    private static final class Credentials {
        final String code;
        final int port;
        Credentials(String code, int port) { this.code = code; this.port = port; }
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        connected = false;
        if (live.get() == this) live.clear();
        if (networkRegistered && connectivity != null) connectivity.unregisterNetworkCallback(networkCallback);
        if (unlockRegistered) unregisterReceiver(unlocked);
        main.removeCallbacksAndMessages(null);
        PairingSession.detach(this);
        super.onDestroy();
    }
}
