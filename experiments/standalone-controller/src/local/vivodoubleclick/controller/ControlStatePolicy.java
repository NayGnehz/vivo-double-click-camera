package local.vivodoubleclick.controller;

/** A saved launch is historical evidence, never a live health check. */
final class ControlStatePolicy {
    enum Saved { STARTED, STOPPED, UNKNOWN }
    static Saved saved(int boot, int completedBoot, int stoppedBoot, long now, long verifiedAt) {
        if (boot < 0) return Saved.UNKNOWN;
        if (boot == stoppedBoot) return Saved.STOPPED;
        if (boot == completedBoot && verifiedAt > 0 && now >= verifiedAt) return Saved.STARTED;
        return Saved.UNKNOWN;
    }
    private ControlStatePolicy() { }
}
