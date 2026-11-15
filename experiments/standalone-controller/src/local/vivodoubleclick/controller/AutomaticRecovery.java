package local.vivodoubleclick.controller;

import android.app.KeyguardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONObject;

/** Shared bounded execution for a system job and the already-bound helper. */
final class AutomaticRecovery {
    static final RecoveryGate GATE = new RecoveryGate();
    static boolean locked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isDeviceLocked();
    }
    static boolean wifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }
    static boolean run(Context context, RecoveryGate.Ticket ticket) {
        ticket.attach();
        try {
            if (ticket.cancelled() || !RecoveryScheduler.needed(context)) return false;
            if (locked(context)) { RecoveryScheduler.waitForUnlock(context); return false; }
            if (!RecoveryScheduler.beginAttempt(context)) return false;
            JSONObject result = ControllerRuntime.execute(context, "start", 0, () -> {
                if (ticket.cancelled() || Thread.currentThread().isInterrupted() || !RecoveryScheduler.needed(context))
                    throw new InterruptedException("Recovery cancelled");
            }, phase -> RecoveryScheduler.progress(context, phase));
            if (!result.optBoolean("ok") || !"RUNNING".equals(result.optString("state")))
                throw new IllegalStateException("Daemon not verified running");
            if (!ticket.cancelled()) RecoveryScheduler.success(context);
            return false;
        } catch (Exception failure) {
            if (ticket.cancelled()) return false;
            RecoveryScheduler.failed(context, failure);
            if (locked(context)) { RecoveryScheduler.waitForUnlock(context); return false; }
            return RecoveryScheduler.mayRetry(context);
        } finally {
            ticket.detach();
            GATE.release(ticket);
        }
    }
    private AutomaticRecovery() { }
}
