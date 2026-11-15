package local.vivodoubleclick.controller;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.res.Resources;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

/** Recognizes only the wireless-debugging network dialog. Never logs its network text. */
final class WifiApprovalUi {
    private final AccessibilityService service;
    private final Handler main;
    // Cache only public system resource strings, never window contents or network text.
    private final Map<String, Resources> packageResources = new HashMap<>();
    private final Map<String, String> resourceStrings = new HashMap<>();
    private String localeTags = "";
    private static final String[] TITLES = normalized(
            "Allow wireless debugging on this network?", "允许通过此网络进行无线调试？",
            "允许在此网络上进行无线调试？", "是否允许在此网络上进行无线调试？",
            "是否允许通过此网络进行无线调试？", "允许无线调试？");
    private static final String[] ALWAYS = normalized("Always allow on this network", "始终允许使用此网络",
            "始终允许通过此网络", "在此网络上始终允许");
    private static final String[] ALLOW = normalized("Allow", "允许");
    WifiApprovalUi(AccessibilityService service, Handler main) { this.service = service; this.main = main; }
    static boolean packageAllowed(String name) { return "com.android.systemui".equals(name) || "com.android.settings".equals(name); }
    void scan() {
        if (!WifiApprovalSession.active()) return;
        KeyguardManager keyguard = service.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isDeviceLocked()) return;
        String tags = service.getResources().getConfiguration().getLocales().toLanguageTags();
        if (!tags.equals(localeTags)) {
            packageResources.clear(); resourceStrings.clear(); localeTags = tags;
        }
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
            String value = normalize(text(node));
            String id = node.getViewIdResourceName();
            if (matches(value, owner, "wifi_debugging_title", TITLES)) snapshot.title = true;
            boolean checkbox = node.isCheckable() && ((id != null && id.endsWith(":id/alwaysUse"))
                    || matches(value, owner, "wifi_debugging_always", ALWAYS));
            if (checkbox && node.isEnabled() && node.isClickable() && snapshot.checkbox == null)
                snapshot.checkbox = AccessibilityNodeInfo.obtain(node);
            if ("android:id/button1".equals(id) && node.isEnabled() && node.isClickable()
                    && matches(value, owner, "wifi_debugging_allow", ALLOW) && snapshot.allow == null)
                snapshot.allow = AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child, snapshot, owner, depth + 1); }
            finally { child.recycle(); }
        }
    }
    private boolean matches(String value, String owner, String resource, String[] choices) {
        if (value.isEmpty()) return false;
        for (String choice : choices) if (value.equals(choice)) return true;
        String key = owner + '/' + resource;
        String localized = resourceStrings.get(key);
        if (localized == null) {
            localized = "";
            try {
                if (!packageResources.containsKey(owner))
                    packageResources.put(owner, service.getPackageManager().getResourcesForApplication(owner));
                Resources resources = packageResources.get(owner);
                int id = resources == null ? 0 : resources.getIdentifier(resource, "string", owner);
                if (id != 0) localized = normalize(resources.getString(id));
            } catch (Exception unavailable) { packageResources.put(owner, null); }
            resourceStrings.put(key, localized);
        }
        return !localized.isEmpty() && value.equals(localized);
    }
    private static String[] normalized(String... values) {
        for (int i = 0; i < values.length; i++) values[i] = normalize(values[i]);
        return values;
    }
    private static String normalize(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r' && ch != '\f'
                    && ch != '\u000b' && ch != '？' && ch != '?' && ch != '。') out.append(ch);
        }
        return out.toString();
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
