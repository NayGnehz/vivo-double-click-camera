package local.vivodoubleclick.controller;

/** Decisions shared by boot, network and UI triggers. No timers or network scans. */
final class RecoveryPolicy {
    static final int MAX_ATTEMPTS = 3;
    private RecoveryPolicy() { }
    static boolean needed(boolean enabled, boolean paired, boolean unlocked, int boot,
                          int completedBoot, int suppressedBoot) {
        return enabled && paired && unlocked && boot >= 0 && completedBoot != boot && suppressedBoot != boot;
    }
    static boolean keepPendingJob(boolean exists, int pendingBoot, int boot) {
        return exists && boot >= 0 && pendingBoot == boot;
    }
    static boolean sameJob(int boot, long generation, int stoppedBoot, long stoppedGeneration) {
        return boot == stoppedBoot && generation == stoppedGeneration;
    }
    static String phaseFailure(String phase) {
        if ("usb".equals(phase)) return "保留 USB 调试未确认。";
        if ("start".equals(phase)) return "监听器未确认启动。";
        if ("cleanup".equals(phase)) return "关闭无线调试未确认。";
        return "本机调试连接未成功。";
    }
    static String phaseMessage(String phase) {
        if ("usb".equals(phase)) return "正在保留 USB 调试。";
        if ("start".equals(phase)) return "正在启动并确认双击相机。";
        if ("cleanup".equals(phase)) return "正在关闭无线调试并确认设置。";
        return "正在连接本机调试。";
    }
    static boolean mayAttempt(int attempts) { return attempts < MAX_ATTEMPTS; }
    static boolean recentSuccess(int boot, int completedBoot, long now, long verifiedAt) {
        return boot >= 0 && boot == completedBoot && verifiedAt > 0 && now >= verifiedAt && now - verifiedAt <= 5_000L;
    }
}
