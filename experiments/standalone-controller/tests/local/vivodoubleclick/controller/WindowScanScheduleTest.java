package local.vivodoubleclick.controller;

/** Simulated event storms must never postpone the first pending inspection. */
public final class WindowScanScheduleTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] ignored) {
        WindowScanSchedule schedule = new WindowScanSchedule();
        check(schedule.request(1000)==0,"first authorization event has no app delay");
        check(schedule.request(1001)==-1,"a pending first event is not postponed");
        schedule.started(1001);
        check(schedule.request(1002)==49,"only a recent scan imposes a short throttle");
        for(int t=1003;t<1051;t++) check(schedule.request(t)==-1,"busy content events cannot move the deadline");
        schedule.started(1051);
        check(schedule.request(2000)==0,"a later window is inspected immediately");
        schedule.reset();
        check(schedule.request(2001)==0,"a new work session does not inherit throttle delay");
        schedule.reset();
        long due=-1;
        int scans=0;
        for(long now=3000;now<4000;now++) {
            if(due==now) { schedule.started(now);scans++;due=-1; }
            long delay=schedule.request(now);
            if(delay>=0) {
                check(due==-1,"there is at most one event-triggered scan queued");
                due=now+delay;
                if(delay==0) { schedule.started(now);scans++;due=-1; }
            }
        }
        check(scans>=20&&scans<=21,"continuous events do not starve scans or exceed the throttle");
        schedule.reset();
        check(schedule.request(5000)==0,"idle reset clears a previously pending event");
        check(WindowScanSchedule.MIN_GAP_MS==50,"active coalescing has a fixed short ceiling");
        System.out.println("WindowScanScheduleTest: "+checks+" assertions passed");
    }
}
