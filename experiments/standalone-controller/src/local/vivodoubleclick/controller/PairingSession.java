package local.vivodoubleclick.controller;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import io.github.muntashirakon.adb.PairingConnectionCtx;
import java.lang.ref.WeakReference;

/** In-memory, explicitly armed authorization for one Settings pairing attempt. */
public final class PairingSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long LIMIT_MS = 120_000L;
    private static final String TAG = "VivoPairing";
    private static Context application;
    private static WeakReference<AutoPairService> service = new WeakReference<>(null);
    private static long generation;
    private static long deadline;
    private static boolean armed;
    private static boolean pairing;
    private static Runnable expiry;
    private static String progressStage = "";

    private PairingSession() { }

    public static synchronized void begin(Context context) {
        setApplication(context);
        if (isActive()) {
            Log.i(TAG, "BEGIN_IGNORED_ACTIVE");
            return;
        }
        if (expiry != null) MAIN.removeCallbacks(expiry);
        final long token = ++generation;
        deadline = SystemClock.elapsedRealtime() + LIMIT_MS;
        armed = true;
        pairing = false;
        progressStage = "";
        write("pending", "等待一次性配对。请按系统提示手动开启无线调试并确认网络。", false);
        expiry = new Runnable() {
            @Override public void run() {
                synchronized (PairingSession.class) {
                    if (!armed || token != generation) return;
                    if (!isActive()) return;
                    // Handler uses uptime; recheck elapsed time shortly after a wake-up.
                    MAIN.postDelayed(this, Math.min(1_000L, deadline - SystemClock.elapsedRealtime()));
                }
            }
        };
        MAIN.postDelayed(expiry, 1_000L);
        AutoPairService connected = service.get();
        if (connected != null) connected.scheduleLaunch(token);
    }

    /** A process restart never silently restores permission to read a pairing window. */
    public static synchronized void recoverInterrupted(Context context) {
        setApplication(context);
        if (armed) {
            isActive();
            return;
        }
        String stored = application.getSharedPreferences("controller", Context.MODE_PRIVATE)
                .getString("pair_status", "");
        if (!"pending".equals(stored) && !"pairing".equals(stored)) return;
        ++generation;
        write("failed", "上次配对因应用进程重启而中断。请重新点击“自动配对”。", false);
        showToast("上次配对已中断，请返回应用重新发起。");
    }

    public static synchronized void cancel() {
        if (!armed) return;
        boolean cancelIo = pairing;
        armed = false;
        pairing = false;
        ++generation;
        if (expiry != null) MAIN.removeCallbacks(expiry);
        write("cancelled", "已取消自动配对，已停止读取设置。", false);
        if (cancelIo) PairingConnectionCtx.cancelActivePairing();
        showToast("已取消自动配对。");
    }

    public static synchronized boolean isActive() {
        if (armed && SystemClock.elapsedRealtime() >= deadline)
            finish(generation, "timeout", "自动配对已超时，已停止读取设置。请返回应用重新发起。", false);
        return armed;
    }

    public static synchronized boolean isServiceConnected() { return service.get() != null; }

    static synchronized long activeToken() { return isActive() ? generation : 0; }

    static synchronized boolean isWaiting(long token) {
        return isActive() && token == generation && !pairing;
    }

    static synchronized boolean isCurrent(long token) {
        return isActive() && token == generation;
    }

    static synchronized boolean claim(long token) {
        if (!isWaiting(token)) return false;
        pairing = true;
        write("pairing", "已识别系统配对窗口，正在与本机配对…", false);
        return true;
    }

    static synchronized void progress(long token, String stage, String message) {
        if (!isWaiting(token) || stage.equals(progressStage)) return;
        progressStage = stage;
        Log.i(TAG, "PROGRESS " + stage);
        write("pending", message, false);
    }

    static synchronized void attach(AutoPairService connected) {
        service = new WeakReference<>(connected);
        Log.i(TAG, "SERVICE_CONNECTED");
        recoverInterrupted(connected);
        if (isActive()) connected.scheduleLaunch(generation);
    }

    static synchronized void detach(AutoPairService disconnected) {
        if (service.get() != disconnected) return;
        service.clear();
        Log.i(TAG, "SERVICE_DISCONNECTED");
        if (armed) finish(generation, "failed", "配对辅助已断开，已停止本次配对。", false);
    }

    static synchronized void finish(long token, String state, String message, boolean success) {
        if (!armed || token != generation) return;
        if (SystemClock.elapsedRealtime() >= deadline) {
            state = "timeout";
            message = "自动配对已超时，已停止读取设置。请返回应用重新发起。";
            success = false;
        }
        boolean cancelIo = pairing && !success;
        armed = false;
        pairing = false;
        if (expiry != null) MAIN.removeCallbacks(expiry);
        write(state, message, success);
        if (cancelIo) PairingConnectionCtx.cancelActivePairing();
        final boolean completed = success;
        final String resultMessage = message;
        final Context context = application;
        final AutoPairService connected = service.get();
        MAIN.post(() -> {
            // A newer user-initiated attempt must not be interrupted by an older callback.
            synchronized (PairingSession.class) {
                if (token != generation || armed) return;
            }
            showToast(completed ? "配对成功，正在返回应用。" : resultMessage);
            try {
                Context launcher = connected != null ? connected : context;
                // Vivo can leave Settings on top of our own task. CLEAR_TOP removes that
                // Settings activity instead of merely bringing the whole task forward.
                launcher.startActivity(new Intent(launcher, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                Log.i(TAG, "RETURN_REQUESTED");
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "RETURN_FAILED " + unavailable.getClass().getSimpleName());
                showToast("配对结果已保存，请手动返回应用查看。");
            } finally {
                if (completed && connected != null) MAIN.postDelayed(() -> {
                    synchronized (PairingSession.class) {
                        if (token != generation || armed || service.get() != connected) return;
                    }
                    Log.i(TAG, "DISABLE_COMPLETED_HELPER");
                    connected.disableSelf();
                }, 500);
            }
        });
    }

    private static void setApplication(Context context) {
        application = context.getApplicationContext();
        if (application == null) throw new IllegalArgumentException("Application context is required");
    }

    private static void showToast(String message) {
        final Context context = application;
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static void write(String state, String message, boolean success) {
        Log.i(TAG, "STATE " + state);
        SharedPreferences.Editor update = application.getSharedPreferences("controller", Context.MODE_PRIVATE)
                .edit().putString("pair_status", state).putString("pair_message", message)
                .putLong("pair_update", System.currentTimeMillis());
        if (success) update.putBoolean("paired", true);
        update.apply();
    }
}
