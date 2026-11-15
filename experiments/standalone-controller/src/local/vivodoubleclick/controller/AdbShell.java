package local.vivodoubleclick.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import io.github.muntashirakon.adb.AdbStream;

/** Bounded, non-interactive ADB shell requests. Closing a request never signals the listener. */
public final class AdbShell implements DeviceController.Shell {
    private final LocalAdbManager manager;
    public AdbShell(LocalAdbManager manager) { this.manager = manager; }

    @Override public DeviceController.Result run(String command, int timeoutSeconds) throws Exception {
        String marker = "__VDC_EXIT_" + UUID.randomUUID().toString().replace("-", "") + "=";
        // A child shell keeps a command's `exit` or `set -e` from suppressing the exit marker.
        String wrapped = "sh -c " + quote(command) + "; rc=$?; printf '\\n" + marker + "%s\\n' \"$rc\"";
        AtomicReference<AdbStream> current = new AtomicReference<>();
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vivo-adb-command"); t.setDaemon(true); return t;
        });
        Future<String> pending = reader.submit(() -> {
            AdbStream stream = manager.openStream("shell:" + wrapped);
            try {
                current.set(stream);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (true) {
                    int count = stream.read(buffer, 0, buffer.length);
                    if (count < 0) throw new IOException("ADB connection ended without a command result");
                    if (bytes.size() + count > 131072) throw new IOException("Command output exceeded 128 KiB");
                    bytes.write(buffer, 0, count);
                    String result = new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                    // libadb 3.1.1 may throw or wait indefinitely at remote EOF. The child
                    // has already exited when the complete, random marker line arrives.
                    if (hasCompleteResult(result, marker)) return result;
                }
            } finally {
                try { stream.close(); } catch (IOException ignored) { }
                current.compareAndSet(stream, null);
            }
        });
        try {
            String result = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            int at = result.lastIndexOf("\n" + marker);
            if (at < 0) throw new IOException("ADB connection ended without a command result");
            String code = result.substring(at + 1 + marker.length()).trim();
            if (!code.matches("[0-9]{1,3}")) throw new IOException("Invalid command result marker");
            return new DeviceController.Result(Integer.parseInt(code), result.substring(0, at).trim());
        } catch (TimeoutException timeout) {
            try { manager.disconnect(); } catch (IOException ignored) { }
            throw new IOException("ADB operation timed out; refresh to check the actual listener state", timeout);
        } finally {
            AdbStream stream = current.get();
            if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            pending.cancel(true);
            reader.shutdownNow();
        }
    }

    private static boolean hasCompleteResult(String result, String marker) {
        int at = result.lastIndexOf("\n" + marker);
        return at >= 0 && result.endsWith("\n")
                && result.substring(at + 1 + marker.length(), result.length() - 1).matches("[0-9]{1,3}");
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
