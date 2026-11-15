package local.vivodoubleclick.controller;

final class AccessibilityWorkPolicy {
    static boolean needsWindowEvents(boolean pairing, boolean approving) { return pairing || approving; }
    static int eventMask(boolean working) {
        // AccessibilityEvent constants, kept framework independent for regression tests.
        return 0x20 | (working ? 0x800 : 0); // WINDOW_STATE_CHANGED, WINDOW_CONTENT_CHANGED
    }
    private AccessibilityWorkPolicy() { }
}
