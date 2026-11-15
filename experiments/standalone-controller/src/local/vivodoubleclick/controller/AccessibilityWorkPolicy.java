package local.vivodoubleclick.controller;

final class AccessibilityWorkPolicy {
    static boolean needsWindowEvents(boolean pairing, boolean approving) { return pairing || approving; }
    private AccessibilityWorkPolicy() { }
}
