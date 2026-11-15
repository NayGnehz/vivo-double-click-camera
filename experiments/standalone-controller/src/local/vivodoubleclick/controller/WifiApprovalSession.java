package local.vivodoubleclick.controller;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Explicit, bounded authorization to click Allow once; idle services never inspect windows. */
final class WifiApprovalSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long deadline;
    private static boolean armed;
    private static boolean allowed;
    private static boolean unchecked;
    private static final Runnable EXPIRE = WifiApprovalSession::end;
    static synchronized void begin(long duration) {
        deadline = SystemClock.elapsedRealtime() + duration;
        armed = true; allowed = false; unchecked = false;
        MAIN.removeCallbacks(EXPIRE);
        MAIN.postDelayed(EXPIRE, duration);
        AutoPairService.refreshConnected();
    }
    static synchronized boolean active() { return armed && SystemClock.elapsedRealtime() < deadline; }
    static synchronized boolean allowed() { return allowed; }
    static synchronized boolean unchecked() { return unchecked; }
    static synchronized void markAllowed() { allowed = true; }
    static synchronized void markUnchecked() { unchecked = true; }
    static synchronized void end() {
        armed = false;
        MAIN.removeCallbacks(EXPIRE);
        AutoPairService.refreshConnected();
    }
    private WifiApprovalSession() { }
}
