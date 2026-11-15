package local.vivodoubleclick;

/** Dependency-free regression suite: javac followed by java -ea DetectorTest. */
public final class DetectorTest {
    private static final String POWER = "/dev/input/event1";
    private static final String OTHER = "/dev/input/event0";
    private static int assertions;

    public static void main(String[] args) {
        parser();
        actualX300Trace();
        boundariesAndSlowTaps();
        longPressesAndRepeats();
        duplicatesAndMirrors();
        volumeChordsAndInterruption();
        cooldownAndCancellation();
        suspendAndClockDiscontinuity();
        droppedStream();
        configuration();
        System.out.println("DetectorTest: " + assertions + " assertions passed");
    }

    private static void parser() {
        DoubleTapDetector.Event e = DoubleTapDetector.parseGeteventLine(
                "[  125196.985463] /dev/input/event1: EV_KEY       KEY_POWER            DOWN");
        check(e != null && POWER.equals(e.device) && "KEY_POWER".equals(e.key)
                && "DOWN".equals(e.action) && e.kernelMicros == 125196985463L && !e.synDropped,
                "parse real event");
        e = DoubleTapDetector.parseGeteventLine("[ 1.2] EV_KEY KEY_VOLUMEUP 00000000");
        check(e != null && e.device == null && "UP".equals(e.action) && e.kernelMicros == 1200000,
                "single-device format and fractional padding");
        e = DoubleTapDetector.parseGeteventLine("[ 2.000000] /dev/input/event0: EV_SYN SYN_DROPPED 00000000");
        check(e != null && e.synDropped, "parse dropped stream marker");
        check(DoubleTapDetector.parseGeteventLine("add device 1: /dev/input/event7") == null, "metadata ignored");
        check(DoubleTapDetector.parseGeteventLine("[ 2.000000] /dev/input/event1: EV_SYN SYN_REPORT 00000000") == null,
                "syn report ignored");
        check(DoubleTapDetector.parseGeteventLine("[ 2.000000] /dev/input/event1: EV_KEY KEY_HOME DOWN") == null,
                "unrelated key ignored");
        check(DoubleTapDetector.parseGeteventLine("[ 99999999999999999999.0] EV_KEY KEY_POWER DOWN") == null,
                "overflow timestamp ignored");
        check(DoubleTapDetector.parseGeteventLine("[ 1.0] EV_KEY KEY_POWER UNKNOWN") == null,
                "unknown value ignored");
    }

    private static void actualX300Trace() {
        DoubleTapDetector d = new DoubleTapDetector();
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 125187554643L, 10000), "real single down");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 125187721885L, 10167), "real single up");
        check(!d.onKey(POWER, "KEY_VOLUMEDOWN", "DOWN", 125190336390L, 12782), "real volume down");
        check(!d.onKey(POWER, "KEY_VOLUMEDOWN", "UP", 125190471232L, 12917), "real volume release");
        check(!d.onKey(OTHER, "KEY_VOLUMEUP", "DOWN", 125192559401L, 15005), "other device volume");
        check(!d.onKey(OTHER, "KEY_VOLUMEUP", "UP", 125192715666L, 15161), "other device volume release");
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 125196985463L, 19431), "real double first down");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 125197091776L, 19537), "real double first up");
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 125197147958L, 19593), "real double second down");
        check(d.onKey(POWER, "KEY_POWER", "UP", 125197279540L, 19725), "real double fires on final up");
    }

    private static void boundariesAndSlowTaps() {
        DoubleTapDetector d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        tap(d, 350, 650, true); // DOWN-to-DOWN window; second UP can fall outside it.
        d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        tap(d, 351, 451, false);
        tap(d, 500, 600, true); // Slow second press becomes the first of a new pair.
        d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 350001, 350), "microsecond boundary rejects second");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 450001, 450), "rejected boundary does not fire");
        d = new DoubleTapDetector();
        check(!p(d, "UP", 0), "orphan up ignored");
        tap(d, 100, 200, false);
    }

    private static void longPressesAndRepeats() {
        DoubleTapDetector d = new DoubleTapDetector();
        tap(d, 0, 301, false);
        tap(d, 320, 400, false);
        tap(d, 450, 500, true);
        d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        tap(d, 200, 501, false);
        tap(d, 550, 650, false);
        d = new DoubleTapDetector();
        check(!p(d, "DOWN", 0), "held down");
        check(!p(d, "REPEAT", 50), "repeat never fires");
        check(!p(d, "UP", 100), "repeat invalidates even early short up");
        tap(d, 150, 250, false);
        tap(d, 300, 350, true);
    }

    private static void duplicatesAndMirrors() {
        DoubleTapDetector d = new DoubleTapDetector();
        check(!p(d, "DOWN", 0), "first down");
        check(!p(d, "DOWN", 1), "duplicate down");
        check(!p(d, "UP", 100), "first up");
        check(!p(d, "UP", 101), "duplicate up");
        check(!d.onKey(OTHER, "KEY_POWER", "DOWN", 102000, 102), "mirror down ignored");
        check(!d.onKey(OTHER, "KEY_POWER", "UP", 103000, 103), "mirror up cannot fake second tap");
        tap(d, 200, 300, true);
        check(!p(d, "UP", 301), "duplicate final up cannot fire twice");
        d = new DoubleTapDetector();
        check(!p(d, "DOWN", 0), "duplicate long first down");
        check(!p(d, "DOWN", 290), "duplicate cannot refresh start");
        check(!p(d, "UP", 301), "duplicate held beyond max invalid");
        tap(d, 320, 400, false);
        d = new DoubleTapDetector();
        check(!d.onKey(OTHER, "KEY_POWER", "UP", 0, 0), "orphan mirror up does not pin source");
        tap(d, 10, 100, false);
        tap(d, 200, 300, true);
    }

    private static void volumeChordsAndInterruption() {
        DoubleTapDetector d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        v(d, OTHER, "KEY_VOLUMEUP", "DOWN", 120);
        v(d, OTHER, "KEY_VOLUMEUP", "UP", 140);
        tap(d, 200, 250, false);
        tap(d, 300, 350, true);
        d = new DoubleTapDetector();
        v(d, OTHER, "KEY_VOLUMEUP", "DOWN", 0);
        tap(d, 100, 150, false);
        tap(d, 200, 250, false);
        v(d, POWER, "KEY_VOLUMEDOWN", "DOWN", 260);
        v(d, OTHER, "KEY_VOLUMEUP", "UP", 270);
        tap(d, 300, 350, false); // One volume key still held.
        v(d, POWER, "KEY_VOLUMEDOWN", "UP", 400);
        tap(d, 450, 500, false);
        tap(d, 550, 600, true);
        d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        check(!p(d, "DOWN", 200), "second down before chord");
        v(d, OTHER, "KEY_VOLUMEUP", "DOWN", 210);
        v(d, OTHER, "KEY_VOLUMEUP", "UP", 220);
        check(!p(d, "UP", 250), "volume chord invalidates in-progress power");
        tap(d, 300, 350, false);
        d = new DoubleTapDetector();
        v(d, OTHER, "KEY_VOLUMEUP", "REPEAT", 0);
        tap(d, 100, 150, false);
        tap(d, 200, 250, false);
        v(d, OTHER, "KEY_VOLUMEUP", "UP", 300);
        tap(d, 350, 400, false);
        tap(d, 450, 500, true);
    }

    private static void cooldownAndCancellation() {
        DoubleTapDetector d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        tap(d, 200, 300, true);
        d.cancelScheduled();
        check(!p(d, "DOWN", 400), "third down blocked");
        check(d.cancelScheduled(), "third down cancels delayed launch");
        check(!d.cancelScheduled(), "cancellation consumed");
        check(!p(d, "UP", 500), "triple does not trigger overlapping pair");
        tap(d, 600, 700, false);
        tap(d, 1200, 1250, false);
        tap(d, 1300, 1400, false);
        tap(d, 1500, 1600, true);
        d.cancelScheduled();
        v(d, OTHER, "KEY_VOLUMEUP", "DOWN", 1650);
        check(d.cancelScheduled(), "volume cancels delayed launch");
    }

    private static void suspendAndClockDiscontinuity() {
        DoubleTapDetector d = new DoubleTapDetector();
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 0, 0), "before suspend down");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 100000, 100), "before suspend up");
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 200000, 10200), "suspend breaks pair");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 300000, 10300), "suspend-separated tap no launch");
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 400000, 10400), "new pair after suspend down");
        check(d.onKey(POWER, "KEY_POWER", "UP", 500000, 10500), "new pair after suspend works");
        d = new DoubleTapDetector();
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 0, 0), "suspend during press down");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 100000, 10000), "suspended long press invalid");
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 200000, 10100), "post long press down");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 300000, 10200), "post long press is only first");
        d = new DoubleTapDetector();
        tap(d, 100, 200, false);
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 50000, 250), "kernel regression cancels");
        check(d.cancelScheduled(), "kernel regression cancellation");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 100000, 300), "regression up is barrier");
        d = new DoubleTapDetector();
        tap(d, 100, 200, false);
        check(!d.onKey(POWER, "KEY_POWER", "DOWN", 250000, 50), "elapsed regression cancels");
        check(!d.onKey(POWER, "KEY_POWER", "UP", 300000, 100), "elapsed regression cannot trigger");
    }

    private static void droppedStream() {
        DoubleTapDetector d = new DoubleTapDetector();
        tap(d, 0, 100, false);
        d.onSynDropped(POWER);
        check(d.cancelScheduled(), "drop cancellation");
        tap(d, 200, 250, false); // Its UP clears the barrier.
        tap(d, 300, 350, false);
        tap(d, 400, 450, true);
        d = new DoubleTapDetector();
        v(d, OTHER, "KEY_VOLUMEUP", "DOWN", 0);
        d.onSynDropped(POWER);
        tap(d, 100, 150, false);
        tap(d, 200, 250, false);
        tap(d, 300, 350, false); // Known held volume state is retained.
        v(d, OTHER, "KEY_VOLUMEUP", "UP", 400);
        tap(d, 450, 500, false);
        tap(d, 550, 600, true);
        d.reset();
        check(d.cancelScheduled(), "reset cancellation");
        check(!d.onKey(OTHER, "KEY_POWER", "DOWN", 0, 0), "reset allows new source");
        check(!d.onKey(OTHER, "KEY_POWER", "UP", 100000, 100), "new source first up");
        check(!d.onKey(OTHER, "KEY_POWER", "DOWN", 200000, 200), "new source second down");
        check(d.onKey(OTHER, "KEY_POWER", "UP", 300000, 300), "new source second up");
    }

    private static void configuration() {
        DoubleTapDetector d = new DoubleTapDetector(200, 80, 0);
        tap(d, 0, 80, false);
        tap(d, 200, 280, true);
        tap(d, 300, 350, false);
        tap(d, 400, 450, true);
        boolean thrown = false;
        try { new DoubleTapDetector(0, 300, 1000); }
        catch (IllegalArgumentException expected) { thrown = true; }
        check(thrown, "invalid settings rejected");
    }

    private static void tap(DoubleTapDetector d, long down, long up, boolean expected) {
        check(!p(d, "DOWN", down), "DOWN never triggers at " + down);
        check(p(d, "UP", up) == expected, "tap result at " + up + " expected " + expected);
    }

    private static boolean p(DoubleTapDetector d, String value, long millis) {
        return d.onKey(POWER, "KEY_POWER", value, millis * 1000, millis);
    }

    private static void v(DoubleTapDetector d, String device, String key, String value, long millis) {
        check(!d.onKey(device, key, value, millis * 1000, millis), "volume never triggers");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
