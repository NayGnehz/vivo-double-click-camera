package local.vivodoubleclick;

import android.content.Context;
import android.os.Looper;
import android.os.FileObserver;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** A shell-UID, read-only evdev observer. Never grabs or reinjects physical keys. */
public final class Main {
    private static final File ROOT = new File("/data/local/tmp/vivo-double-click");
    private static final Pattern DEVICE = Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
    private static final String CAMERA_ACTION = "android.media.action.STILL_IMAGE_CAMERA_SECURE";
    private final List<Process> readers = new CopyOnWriteArrayList<>();
    private final List<Process> commands = new CopyOnWriteArrayList<>();
    private final AtomicBoolean exitRequested = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object gate = new Object();
    private DoubleTapDetector detector;
    private ScheduledFuture<?> pending;
    private long launchGeneration;
    private PowerManager.WakeLock wakeLock;
    private boolean dryRun;
    private int doubleMs = 350;
    private int delayMs = 150;
    private volatile boolean stopping;
    private boolean launching;
    private RandomAccessFile lockFile;
    private FileLock instanceLock;
    private FileObserver stopObserver;
    private String powerPath;

    public static void main(String[] args) {
        Main main = new Main();
        try { main.run(args); }
        catch (Throwable e) {
            System.err.println("FATAL " + e);
            e.printStackTrace(System.err);
            main.cleanup();
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        boolean selfTest = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--double-ms": doubleMs = Integer.parseInt(args[++i]); break;
                case "--delay-ms": delayMs = Integer.parseInt(args[++i]); break;
                case "--dry-run": dryRun = true; break;
                case "--self-test-camera": selfTest = true; break;
                default: throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (doubleMs < 150 || doubleMs > 600 || delayMs < 0 || delayMs > 1000)
            throw new IllegalArgumentException("double-ms must be 150..600; delay-ms 0..1000");
        if (android.os.Process.myUid() != 2000)
            throw new IllegalStateException("Run with ADB shell UID 2000 (no root required)");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shellContext = system.createPackageContext("com.android.shell", 0);
        PowerManager pm = (PowerManager) shellContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VivoDoubleClick:gesture");
        wakeLock.setReferenceCounted(false);
        // Fail before installing any listener if this firmware rejects shell wake locks.
        wakeLock.acquire(1000);
        wakeLock.release();
        if (selfTest) {
            log("SELF_TEST_CAMERA start");
            launchCamera();
            cleanup();
            System.exit(0);
        }
        if (!ROOT.isDirectory()) throw new IOException("Missing install directory: " + ROOT);
        lockFile = new RandomAccessFile(new File(ROOT, "service.lock"), "rw");
        instanceLock = lockFile.getChannel().tryLock();
        if (instanceLock == null) throw new IllegalStateException("Another instance is running");
        String bootId = new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),
            StandardCharsets.US_ASCII).trim();
        if (!bootId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new IOException("Invalid boot identifier");
        File stateTemp = new File(ROOT, "service.pid.tmp");
        try (Writer w = new FileWriter(stateTemp)) {
            w.write(Integer.toString(android.os.Process.myPid()));
            w.write("\n");
            w.write(bootId);
            w.write("\n");
        }
        Files.move(stateTemp.toPath(), new File(ROOT, "service.pid").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "vdc-cleanup"));
        stopObserver = new FileObserver(ROOT.getAbsolutePath(), FileObserver.CREATE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                if ("stop.request".equals(path)) {
                    log("STOP_REQUEST received");
                    requestExit(0);
                }
            }
        };
        stopObserver.startWatching();
        Map<String, String> devices = discoverDevices();
        powerPath = findUnique(devices, "mtk-pmic-keys");
        String volumePath = findUnique(devices, "gpio-keys");
        detector = new DoubleTapDetector(doubleMs, 300, 1000);
        CountDownLatch opened = new CountDownLatch(2);
        startReader(powerPath, opened);
        startReader(volumePath, opened);
        if (!opened.await(5, TimeUnit.SECONDS))
            throw new IOException("Input reader did not confirm both devices opened");
        for (Process reader : readers)
            if (!reader.isAlive()) throw new IOException("Input reader exited during startup");
        log("READY power=" + powerPath + " volume=" + volumePath + " doubleMs=" + doubleMs
            + " delayMs=" + delayMs + " dryRun=" + dryRun + " uid=" + android.os.Process.myUid());
        Looper.loop();
    }

    private Map<String, String> discoverDevices() throws Exception {
        String output = command(5, "/system/bin/getevent", "-lp");
        Map<String, String> devices = new LinkedHashMap<>();
        String path = null;
        for (String line : output.split("\\r?\\n")) {
            if (line.matches(".*KEY_(POWER|VOLUMEUP|VOLUMEDOWN)\\*.*"))
                throw new IllegalStateException("Release all physical buttons before starting the service");
            Matcher m = DEVICE.matcher(line.trim());
            if (m.matches()) path = m.group(1);
            else if (path != null && line.trim().startsWith("name:")) {
                String name = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
                devices.put(path, name);
            }
        }
        return devices;
    }

    private static String findUnique(Map<String, String> devices, String name) {
        String result = null;
        for (Map.Entry<String, String> entry : devices.entrySet()) {
            if (!name.equals(entry.getValue())) continue;
            if (result != null) throw new IllegalStateException("Ambiguous input device: " + name);
            result = entry.getKey();
        }
        if (result == null) throw new IllegalStateException("Missing physical input device: " + name);
        return result;
    }

    private void startReader(String path, CountDownLatch opened) throws IOException {
        Process reader = new ProcessBuilder("/system/bin/getevent", "-lt", "-v7", path)
            .redirectErrorStream(true).start();
        readers.add(reader);
        new Thread(() -> {
            boolean confirmed = false;
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(reader.getInputStream()))) {
                String line;
                while ((line = lines.readLine()) != null && !stopping) {
                    if (!confirmed && line.trim().startsWith("name:")) {
                        confirmed = true;
                        opened.countDown();
                    }
                    DoubleTapDetector.Event event = DoubleTapDetector.parseGeteventLine(line);
                    if (event != null) handle(path, event);
                    else if (line.contains("Permission denied") || line.startsWith("could not")
                            || line.startsWith("Usage:") || line.contains("invalid option"))
                        throw new IOException(path + ": " + line);
                }
                if (!stopping) throw new EOFException("Input reader ended: " + path);
            } catch (Throwable e) {
                if (!stopping) {
                    log("FATAL_READER " + e);
                    requestExit(2);
                }
            }
        }, "keys-" + new File(path).getName()).start();
    }

    private void handle(String path, DoubleTapDetector.Event event) {
        synchronized (gate) {
            if (stopping) return;
            if (event.synDropped) {
                log("FATAL_SYN_DROPPED " + path + "; restart to resynchronize");
                requestExit(3);
                return;
            }
            if (!("KEY_POWER".equals(event.key) || "KEY_VOLUMEUP".equals(event.key)
                    || "KEY_VOLUMEDOWN".equals(event.key))) return;
            if ("KEY_POWER".equals(event.key) && !powerPath.equals(path)) return;
            long now = SystemClock.elapsedRealtime();
            // Avoid delayed backlog interpreting a stale gesture as current.
            if (Math.abs(SystemClock.uptimeMillis() - event.kernelMicros / 1000) > 1500) {
                cancelPending();
                log("FATAL_STALE_EVENT; restart to resynchronize key state");
                requestExit(4);
                return;
            }
            if ("KEY_POWER".equals(event.key) && "DOWN".equals(event.action))
                wakeLock.acquire(2500);
            boolean fire = detector.onKey(path, event.key, event.action, event.kernelMicros, now);
            if (detector.cancelScheduled()) cancelPending();
            if (dryRun) log("KEY " + path + " " + event.key + " " + event.action);
            if (fire && !launching) {
                log("DOUBLE_TAP recognized");
                final long generation = ++launchGeneration;
                pending = scheduler.schedule(() -> {
                    synchronized (gate) {
                        if (stopping || generation != launchGeneration) return;
                        pending = null;
                        launching = true;
                        wakeLock.acquire(10000);
                    }
                    try {
                        if (dryRun) log("DRY_RUN would launch camera");
                        else launchCamera();
                    } catch (Exception e) {
                        log("CAMERA_ERROR " + e);
                    } finally {
                        synchronized (gate) {
                            launching = false;
                            if (wakeLock.isHeld()) wakeLock.release();
                        }
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void cancelPending() {
        launchGeneration++;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
            log("PENDING_CANCELLED");
        }
    }

    private void launchCamera() throws Exception {
        wakeLock.acquire(10000);
        try {
            command(3, "/system/bin/input", "keyevent", "KEYCODE_WAKEUP");
            String output = command(6, "/system/bin/am", "start", "-W", "--user", "current",
                "-a", CAMERA_ACTION, "-p", "com.android.camera");
            if (output.contains("Error:") || output.contains("Exception"))
                throw new IOException(output.trim());
            log("CAMERA_STARTED " + output.trim().replace('\n', ' '));
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private String command(int timeoutSeconds, String... args) throws Exception {
        final Process process;
        synchronized (gate) {
            if (stopping) throw new InterruptedIOException("Service is stopping");
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            commands.add(process);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    // Bound output, continue draining to prevent subprocess deadlock.
                    if (output.size() < 262144) output.write(buffer, 0, n);
                }
            } catch (IOException ignored) { }
        }, "command-output");
        drain.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Command timeout: " + Arrays.toString(args));
            }
            drain.join(1000);
            String text = output.toString("UTF-8");
            if (process.exitValue() != 0) throw new IOException(Arrays.toString(args) + ": " + text.trim());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            commands.remove(process);
        }
    }

    private void requestExit(int code) {
        if (exitRequested.compareAndSet(false, true)) {
            new Thread(() -> { cleanup(); System.exit(code); }, "vdc-exit").start();
        }
    }

    private void cleanup() {
        synchronized (gate) {
            if (stopping) return;
            stopping = true;
            cancelPending();
        }
        if (stopObserver != null) stopObserver.stopWatching();
        scheduler.shutdownNow();
        for (Process process : commands) process.destroyForcibly();
        for (Process reader : readers) {
            reader.destroy();
            try {
                if (!reader.waitFor(1, TimeUnit.SECONDS)) reader.destroyForcibly();
            } catch (InterruptedException e) { reader.destroyForcibly(); }
        }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try {
            if (instanceLock != null) {
                new File(ROOT, "service.pid").delete();
                instanceLock.release();
            }
            if (lockFile != null) lockFile.close();
        } catch (Exception ignored) { }
    }

    private static void log(String message) {
        System.out.println(message);
        System.out.flush();
    }
}
