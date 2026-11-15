package local.vivodoubleclick.controller;

/** Decisions shared by boot, network and UI triggers. No timers or network scans. */
final class RecoveryPolicy {
    static final int MAX_ATTEMPTS = 3;
    private RecoveryPolicy() { }
    static boolean needed(boolean enabled, boolean paired, boolean unlocked, int boot,
                          int completedBoot, int suppressedBoot) {
        return enabled && paired && unlocked && boot >= 0 && completedBoot != boot && suppressedBoot != boot;
    }
    static boolean mayAttempt(int attempts) { return attempts < MAX_ATTEMPTS; }
    static boolean recentSuccess(int boot, int completedBoot, long now, long verifiedAt) {
        return boot >= 0 && boot == completedBoot && verifiedAt > 0 && now >= verifiedAt && now - verifiedAt <= 5_000L;
    }
}
