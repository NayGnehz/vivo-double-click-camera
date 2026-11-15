package local.vivodoubleclick.controller;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.provider.Settings;

final class RecoveryScheduler {
    private static final int JOB_ID = 0x564443;
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("controller", Context.MODE_PRIVATE); }
    static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (SecurityException denied) { return -1; }
    }
    static boolean enabled(Context context) { return prefs(context).getBoolean("auto_restore", true); }
    static boolean assistantEnabled(Context context) {
        if (PairingSession.isServiceConnected()) return true;
        String enabled;
        try { enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
        catch (SecurityException denied) { return false; }
        if (enabled == null) return false;
        ComponentName own = new ComponentName(context, AutoPairService.class);
        for (String entry : enabled.split(":")) if (own.equals(ComponentName.unflattenFromString(entry))) return true;
        return false;
    }
    static boolean needed(Context context) {
        SharedPreferences p = prefs(context);
        UserManager users = context.getSystemService(UserManager.class);
        return assistantEnabled(context) && RecoveryPolicy.needed(enabled(context), p.getBoolean("paired", false), users != null && users.isUserUnlocked(),
                boot(context), p.getInt("restore_completed_boot", -2), p.getInt("restore_suppressed_boot", -2));
    }
    static synchronized void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || !needed(context) || AutomaticRecovery.GATE.busy()) return;
        SharedPreferences p = prefs(context);
        int attempts = p.getInt("restore_attempt_boot", -2) == boot(context) ? p.getInt("restore_attempts", 0) : 0;
        if (!RecoveryPolicy.mayAttempt(attempts)) return;
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        // A Wi-Fi request already covers later networks. Replacing the same job ID
        // would stop an executing authorization/startup attempt.
        if (RecoveryPolicy.keepPendingJob(pending != null,
                pending == null ? -2 : pending.getExtras().getInt("boot", -2), boot(context))) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putInt("boot", boot(context));
        long generation = p.getLong("restore_job_generation", 0) + 1;
        p.edit().putLong("restore_job_generation", generation).apply();
        extras.putLong("generation", generation);
        NetworkRequest network = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, RecoveryJobService.class))
                .setRequiredNetwork(network).setPersisted(true).setExtras(extras)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS)
            p.edit().putString("restore_message", "等待 Wi-Fi 自动恢复；无需一直打开控制界面。").apply();
    }
    static synchronized void onNetworkAvailable(Context context, Network network) {
        SharedPreferences p = prefs(context);
        int boot = boot(context);
        long handle = network.getNetworkHandle();
        if (p.getInt("restore_network_boot", -2) != boot || p.getLong("restore_network_handle", -1) != handle) {
            p.edit().putInt("restore_network_boot", boot).putLong("restore_network_handle", handle)
                    .putInt("restore_attempt_boot", boot).putInt("restore_attempts", 0).apply();
        }
        schedule(context);
    }
    static synchronized boolean beginAttempt(Context context) {
        if (!needed(context)) return false;
        SharedPreferences p = prefs(context);
        int boot = boot(context);
        int count = p.getInt("restore_attempt_boot", -2) == boot ? p.getInt("restore_attempts", 0) : 0;
        if (!RecoveryPolicy.mayAttempt(count)) return false;
        p.edit().putInt("restore_attempt_boot", boot).putInt("restore_attempts", count + 1)
                .putString("restore_message", "正在自动恢复监听器…").apply();
        return true;
    }
    static boolean mayRetry(Context context) {
        SharedPreferences p = prefs(context);
        int count = p.getInt("restore_attempt_boot", -2) == boot(context) ? p.getInt("restore_attempts", 0) : 0;
        return needed(context) && RecoveryPolicy.mayAttempt(count);
    }
    static void success(Context context) {
        prefs(context).edit().remove("restore_wait_unlock").remove("controller_stopped_boot").putInt("restore_completed_boot", boot(context))
                .putLong("restore_verified_elapsed", SystemClock.elapsedRealtime())
                .putString("restore_message", "正在运行；无线调试已关闭，USB 调试保留。")
                .putBoolean("restore_ok", true).putLong("restore_update", SystemClock.elapsedRealtime()).apply();
    }
    static void progress(Context context, String phase) {
        prefs(context).edit().putString("restore_phase", phase)
                .putString("restore_message", RecoveryPolicy.phaseMessage(phase))
                .putLong("restore_update", SystemClock.elapsedRealtime()).apply();
    }
    static void failed(Context context, Exception failure) {
        prefs(context).edit().putBoolean("restore_ok", false)
                .putString("restore_message", "自动启动未完成：" + (failure instanceof AdbConnectionRetry.DiscoveryTimeout
                        ? "本机调试端口发现超时，连接重试已用尽。" : RecoveryPolicy.phaseFailure(prefs(context).getString("restore_phase", "connect")))
                        + " 本次联网最多尝试三次。")
                .putLong("restore_update", SystemClock.elapsedRealtime()).apply();
    }
    static void waitForUnlock(Context context) { prefs(context).edit().putBoolean("restore_wait_unlock", true).apply(); }
    static void onUserPresent(Context context) {
        if (prefs(context).getBoolean("restore_wait_unlock", false))
            prefs(context).edit().putBoolean("restore_wait_unlock", false).putInt("restore_attempts", 0).apply();
        // Also cover first unlock when earlier boot/network callbacks arrived locked.
        // Never cancel a job already finishing an authorized startup.
        schedule(context);
    }
    static boolean recentlyVerified(Context context) {
        SharedPreferences p = prefs(context);
        return RecoveryPolicy.recentSuccess(boot(context), p.getInt("restore_completed_boot", -2), SystemClock.elapsedRealtime(), p.getLong("restore_verified_elapsed", 0));
    }
    static ControlStatePolicy.Saved savedState(Context context) {
        SharedPreferences p = prefs(context);
        return ControlStatePolicy.saved(boot(context), p.getInt("restore_completed_boot", -2),
                p.getInt("controller_stopped_boot", -2), SystemClock.elapsedRealtime(), p.getLong("restore_verified_elapsed", 0));
    }
    static void stopped(Context context) {
        prefs(context).edit().putInt("controller_stopped_boot", boot(context))
                .putInt("restore_suppressed_boot", boot(context)).putBoolean("restore_ok", false)
                .remove("restore_completed_boot").putLong("restore_verified_elapsed", 0).apply();
    }
    static void manualAction(Context context, String action) {
        if (!"status".equals(action)) AutomaticRecovery.GATE.cancel();
        if ("stop".equals(action)) prefs(context).edit().putInt("restore_suppressed_boot", boot(context)).apply();
        if ("start".equals(action)) prefs(context).edit().remove("restore_suppressed_boot").apply();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null && !"status".equals(action)) scheduler.cancel(JOB_ID);
    }
    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("auto_restore", enabled).apply();
        if (enabled) {
            prefs(context).edit().remove("restore_suppressed_boot").putInt("restore_attempts", 0).apply();
            schedule(context);
        } else {
            AutomaticRecovery.GATE.cancel();
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        }
        AutoPairService.refreshConnected();
    }
    private RecoveryScheduler() { }
}
