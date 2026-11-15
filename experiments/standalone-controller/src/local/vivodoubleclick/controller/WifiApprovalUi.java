package local.vivodoubleclick.controller;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.res.Resources;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/** Recognizes only the wireless-debugging network dialog. Never logs its network text. */
final class WifiApprovalUi {
    private final AccessibilityService service;
    private final Handler main;
    WifiApprovalUi(AccessibilityService service, Handler main) { this.service = service; this.main = main; }
    static boolean packageAllowed(String name) { return "com.android.systemui".equals(name) || "com.android.settings".equals(name); }
    void scan() {
        if (!WifiApprovalSession.active()) return;
        KeyguardManager keyguard = service.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isDeviceLocked()) return;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        Snapshot snapshot = new Snapshot();
        try {
            String owner = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (!packageAllowed(owner)) return;
            collect(root, snapshot, owner, 0);
            WifiApprovalPolicy.Action action = WifiApprovalPolicy.decide(WifiApprovalSession.active(), true, true,
                    snapshot.title, snapshot.checkbox != null, snapshot.checkbox != null && snapshot.checkbox.isChecked(),
                    snapshot.allow != null, WifiApprovalSession.allowed(), WifiApprovalSession.unchecked());
            if (action == WifiApprovalPolicy.Action.UNCHECK_ALWAYS) {
                WifiApprovalSession.markUnchecked();
                if (snapshot.checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    // One bounded readback after the action, not a repeating window scan.
                    main.postDelayed(this::scan, 150);
                }
            } else if (action == WifiApprovalPolicy.Action.ALLOW_ONCE) {
                WifiApprovalSession.markAllowed();
                snapshot.allow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } catch (RuntimeException staleWindow) {
            // Await the next window event. Never click a guessed coordinate or unrelated dialog.
        } finally {
            if (snapshot.checkbox != null) snapshot.checkbox.recycle();
            if (snapshot.allow != null) snapshot.allow.recycle();
            root.recycle();
        }
    }
    private void collect(AccessibilityNodeInfo node, Snapshot snapshot, String owner, int depth) {
        if (depth > 24 || ++snapshot.visited > 256) return;
        if (node.isVisibleToUser()) {
            String value = text(node);
            String id = node.getViewIdResourceName();
            if (matches(value, owner, "wifi_debugging_title", "Allow wireless debugging on this network?",
                    "允许通过此网络进行无线调试？", "允许在此网络上进行无线调试？", "是否允许在此网络上进行无线调试？",
                    "是否允许通过此网络进行无线调试？", "允许无线调试？")) snapshot.title = true;
            boolean checkbox = node.isCheckable() && ((id != null && id.endsWith(":id/alwaysUse"))
                    || matches(value, owner, "wifi_debugging_always", "Always allow on this network", "始终允许使用此网络", "始终允许通过此网络", "在此网络上始终允许"));
            if (checkbox && node.isEnabled() && node.isClickable() && snapshot.checkbox == null)
                snapshot.checkbox = AccessibilityNodeInfo.obtain(node);
            if ("android:id/button1".equals(id) && node.isEnabled() && node.isClickable()
                    && matches(value, owner, "wifi_debugging_allow", "Allow", "允许") && snapshot.allow == null)
                snapshot.allow = AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child, snapshot, owner, depth + 1); }
            finally { child.recycle(); }
        }
    }
    private boolean matches(String value, String owner, String resource, String... choices) {
        String normalized = normalize(value);
        try {
            Resources resources = service.getPackageManager().getResourcesForApplication(owner);
            int id = resources.getIdentifier(resource, "string", owner);
            if (id != 0 && normalized.equals(normalize(resources.getString(id)))) return true;
        } catch (Exception unavailable) { }
        for (String choice : choices) if (normalized.equals(normalize(choice))) return true;
        return false;
    }
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s？?。]", "");
    }
    private static String text(AccessibilityNodeInfo node) {
        CharSequence value = node.getText();
        if (value == null) value = node.getContentDescription();
        return value == null ? "" : value.toString();
    }
    private static final class Snapshot {
        int visited;
        boolean title;
        AccessibilityNodeInfo checkbox;
        AccessibilityNodeInfo allow;
    }
}
