package local.vivodoubleclick;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java, read-only interpretation of physical getevent key events. */
public final class DoubleTapDetector {
    public static final long DEFAULT_DOUBLE_TAP_MS = 350;
    public static final long DEFAULT_MAX_PRESS_MS = 300;
    public static final long DEFAULT_COOLDOWN_MS = 1000;

    private static final Pattern GETEVENT_LINE = Pattern.compile(
            "^\\s*\\[\\s*(\\d+)\\.(\\d+)\\s*]\\s*"
            + "(?:([^\\s:]+):\\s*)?(EV_KEY|EV_SYN)\\s+(\\S+)\\s+(\\S+)\\s*$");

    public static final class Event {
        /** Null when getevent is reading a single device and omits the path. */
        public final String device;
        public final String key;
        public final String action;
        public final long kernelMicros;
        public final boolean synDropped;

        private Event(String device, String key, String value, long kernelMicros,
                      boolean synDropped) {
            this.device = device;
            this.key = key;
            this.action = value;
            this.kernelMicros = kernelMicros;
            this.synDropped = synDropped;
        }
    }

    /** Parses labeled getevent -lt output. Metadata, SYN_REPORT and other keys return null. */
    public static Event parseGeteventLine(String line) {
        if (line == null) return null;
        Matcher m = GETEVENT_LINE.matcher(line);
        if (!m.matches()) return null;
        String kind = m.group(4);
        String key = m.group(5);
        boolean dropped = "EV_SYN".equals(kind) && "SYN_DROPPED".equals(key);
        if (!dropped && (!"EV_KEY".equals(kind) || !isRelevantKey(key))) return null;
        String value = dropped ? m.group(6) : normalizeValue(m.group(6));
        if (value == null) return null;
        try {
            String fraction = m.group(2);
            if (fraction.length() > 6) fraction = fraction.substring(0, 6);
            while (fraction.length() < 6) fraction += "0";
            long micros = Math.addExact(Math.multiplyExact(Long.parseLong(m.group(1)),
                    1_000_000L), Long.parseLong(fraction));
            return new Event(m.group(3), key, value, micros, dropped);
        } catch (NumberFormatException | ArithmeticException invalidTimestamp) {
            return null;
        }
    }

    private final long doubleTapWindowMs;
    private final long maxPressMs;
    private final long cooldownMs;
    private final Set<String> heldVolumeKeys = new HashSet<String>();
    private String powerDevice;
    private boolean powerDown;
    private boolean firstTapReady;
    private boolean droppedBarrier;
    private boolean cancelPending;
    private int candidate; // 0: invalid, 1: first press, 2: second press
    private long firstDownKernel;
    private long firstDownElapsed;
    private long pressDownKernel;
    private long pressDownElapsed;
    private long lastPowerKernel = -1;
    private long lastElapsed = -1;
    private long cooldownUntil = -1;

    public DoubleTapDetector() {
        this(DEFAULT_DOUBLE_TAP_MS, DEFAULT_MAX_PRESS_MS, DEFAULT_COOLDOWN_MS);
    }

    public DoubleTapDetector(long doubleTapWindowMs, long maxPressMs, long cooldownMs) {
        if (doubleTapWindowMs <= 0 || maxPressMs <= 0 || cooldownMs < 0) {
            throw new IllegalArgumentException("Windows must be positive; cooldown must be nonnegative");
        }
        this.doubleTapWindowMs = doubleTapWindowMs;
        this.maxPressMs = maxPressMs;
        this.cooldownMs = cooldownMs;
    }

    /**
     * Returns true only on the UP ending the second valid short press.
     *
     * The caller must whitelist the physical power node, while forwarding volume
     * events from every selected physical key node. As an additional mirror guard,
     * the first POWER DOWN pins its source path until reset().
     *
     * kernelMicros is the event timestamp. elapsedMs must come from Android's
     * SystemClock.elapsedRealtime() (which includes suspend), not uptimeMillis().
     * Both clocks are checked; buffered/stale events must be discarded by the caller.
     *
     * After EVERY event, consume cancelScheduled() before scheduling a launch for
     * a true return value. This lets a third press, volume chord or stream error
     * cancel a launch which the caller has delayed to let normal power handling settle.
     */
    public boolean onKey(String device, String key, String value,
                         long kernelMicros, long elapsedMs) {
        if (!isRelevantKey(key)) return false;
        value = normalizeValue(value);
        if (value == null) return false;
        if (device == null || device.length() == 0) {
            throw new IllegalArgumentException("Provide the physical source device path");
        }
        if (kernelMicros < 0 || elapsedMs < 0) {
            throw new IllegalArgumentException("Timestamps must be nonnegative");
        }
        boolean volume = !"KEY_POWER".equals(key);
        if (!volume && powerDevice != null && !powerDevice.equals(device)) return false;

        if (lastElapsed > elapsedMs) {
            onSynDropped(device);
            lastElapsed = elapsedMs;
            return false;
        }
        lastElapsed = elapsedMs;

        if (volume) {
            String volumeId = device + '\u0000' + key;
            if ("UP".equals(value)) heldVolumeKeys.remove(volumeId);
            else heldVolumeKeys.add(volumeId);
            invalidateCandidate();
            cancelPending = true;
            return false;
        }

        if (powerDevice == null) {
            // An UP or REPEAT without a DOWN must never claim the source device.
            if (!"DOWN".equals(value)) return false;
            powerDevice = device;
        }
        if (lastPowerKernel > kernelMicros) {
            onSynDropped(device);
            lastPowerKernel = kernelMicros;
            return false;
        }
        lastPowerKernel = kernelMicros;

        if ("REPEAT".equals(value)) {
            // Repeated hold events never count as taps, even if emitted unusually early.
            invalidateCandidate();
            cancelPending = true;
            return false;
        }
        if ("DOWN".equals(value)) {
            if (powerDown) return false; // Duplicate edge: preserve the original press time.
            powerDown = true;
            pressDownKernel = kernelMicros;
            pressDownElapsed = elapsedMs;
            cancelPending = true;
            candidate = 0;
            if (droppedBarrier || !heldVolumeKeys.isEmpty() || elapsedMs < cooldownUntil) {
                firstTapReady = false;
                return false;
            }
            if (firstTapReady
                    && withinMicros(kernelMicros, firstDownKernel, doubleTapWindowMs)
                    && withinMillis(elapsedMs, firstDownElapsed, doubleTapWindowMs)) {
                candidate = 2;
                firstTapReady = false;
            } else {
                candidate = 1;
                firstTapReady = false;
                firstDownKernel = kernelMicros;
                firstDownElapsed = elapsedMs;
            }
            return false;
        }

        // UP after a dropped stream is a synchronization barrier, never a tap.
        if (droppedBarrier) {
            droppedBarrier = false;
            powerDown = false;
            invalidateCandidate();
            cancelPending = true;
            return false;
        }
        if (!powerDown) return false;
        powerDown = false;
        int completedCandidate = candidate;
        candidate = 0;
        if (!heldVolumeKeys.isEmpty()
                || !withinMicros(kernelMicros, pressDownKernel, maxPressMs)
                || !withinMillis(elapsedMs, pressDownElapsed, maxPressMs)) {
            invalidateCandidate();
            cancelPending = true;
            return false;
        }
        if (completedCandidate == 1) {
            firstTapReady = true;
        } else if (completedCandidate == 2) {
            firstTapReady = false;
            cooldownUntil = saturatedAdd(elapsedMs, cooldownMs);
            return true;
        }
        return false;
    }

    /**
     * Cancels the current gesture and delayed launch after SYN_DROPPED.
     * Retains known held volume keys; requires a POWER UP before recognizing taps.
     * Missing volume state cannot be reconstructed from this stream. A caller
     * requiring strict chord safety should stop and resynchronize key state.
     */
    public void onSynDropped(String device) {
        invalidateCandidate();
        powerDown = false;
        droppedBarrier = true;
        cancelPending = true;
        lastPowerKernel = -1;
    }

    /** Consumes the request to cancel any already scheduled camera launch. */
    public boolean cancelScheduled() {
        boolean result = cancelPending;
        cancelPending = false;
        return result;
    }

    /** Fully resets the detector. Call only when the caller knows all keys are released. */
    public void reset() {
        powerDevice = null;
        powerDown = false;
        firstTapReady = false;
        droppedBarrier = false;
        candidate = 0;
        heldVolumeKeys.clear();
        lastPowerKernel = -1;
        lastElapsed = -1;
        cooldownUntil = -1;
        cancelPending = true;
    }

    private void invalidateCandidate() {
        candidate = 0;
        firstTapReady = false;
    }

    private static boolean isRelevantKey(String key) {
        return "KEY_POWER".equals(key) || "KEY_VOLUMEUP".equals(key)
                || "KEY_VOLUMEDOWN".equals(key);
    }

    private static String normalizeValue(String value) {
        if ("DOWN".equals(value) || "00000001".equals(value) || "1".equals(value)) return "DOWN";
        if ("UP".equals(value) || "00000000".equals(value) || "0".equals(value)) return "UP";
        if ("REPEAT".equals(value) || "00000002".equals(value) || "2".equals(value)) return "REPEAT";
        return null;
    }

    private static boolean withinMicros(long end, long start, long limitMs) {
        long limit = limitMs > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : limitMs * 1000;
        return end >= start && end - start <= limit;
    }

    private static boolean withinMillis(long end, long start, long limitMs) {
        return end >= start && end - start <= limitMs;
    }

    private static long saturatedAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
