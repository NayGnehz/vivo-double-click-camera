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
        if (scheduler == null || !needed(context)) return;
        SharedPreferences p = prefs(context);
        int attempts = p.getInt("restore_attempt_boot", -2) == boot(context) ? p.getInt("restore_attempts", 0) : 0;
        if (!RecoveryPolicy.mayAttempt(attempts)) return;
        long handle = p.getLong("restore_network_handle", -1);
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        if (pending != null && pending.getExtras().getInt("boot", -2) == boot(context)
                && pending.getExtras().getLong("network", -1) == handle) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putInt("boot", boot(context));
        extras.putLong("network", handle);
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
        return needed(context) && RecoveryPolicy.mayAttempt(prefs(context).getInt("restore_attempts", 0));
    }
    static void success(Context context) {
        prefs(context).edit().putInt("restore_completed_boot", boot(context))
                .putLong("restore_verified_elapsed", SystemClock.elapsedRealtime())
                .putString("restore_message", "正在运行；无线调试已关闭，USB 调试保留。")
                .putBoolean("restore_ok", true).putLong("restore_update", SystemClock.elapsedRealtime()).apply();
    }
    static void failed(Context context) {
        prefs(context).edit().putBoolean("restore_ok", false)
                .putString("restore_message", "自动恢复未完成。请检查无障碍助手、Wi-Fi 和调试授权；本次联网最多尝试三次。")
                .putLong("restore_update", SystemClock.elapsedRealtime()).apply();
    }
    static void waitForUnlock(Context context) { prefs(context).edit().putBoolean("restore_wait_unlock", true).apply(); }
    static void onUserPresent(Context context) {
        if (!prefs(context).getBoolean("restore_wait_unlock", false)) return;
        prefs(context).edit().putBoolean("restore_wait_unlock", false).putInt("restore_attempts", 0).apply();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
        schedule(context);
    }
    static boolean recentlyVerified(Context context) {
        SharedPreferences p = prefs(context);
        return RecoveryPolicy.recentSuccess(boot(context), p.getInt("restore_completed_boot", -2), SystemClock.elapsedRealtime(), p.getLong("restore_verified_elapsed", 0));
    }
    static void manualAction(Context context, String action) {
        if ("stop".equals(action)) prefs(context).edit().putInt("restore_suppressed_boot", boot(context))
                .putBoolean("restore_ok", false).putLong("restore_verified_elapsed", 0).remove("restore_completed_boot").apply();
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
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        }
        AutoPairService.refreshConnected();
    }
    private RecoveryScheduler() { }
}
