package local.vivodoubleclick.controller;

/** Event-driven coalescing: a first event runs now, later events cannot postpone it. */
final class WindowScanSchedule {
    static final long MIN_GAP_MS = 50L;
    private long nextAllowedAt;
    private boolean pending;
    long request(long now) {
        if (pending) return -1;
        pending = true;
        return Math.max(0L, nextAllowedAt - now);
    }
    void started(long now) { pending = false; nextAllowedAt = now + MIN_GAP_MS; }
    void reset() { pending = false; nextAllowedAt = 0L; }
}
