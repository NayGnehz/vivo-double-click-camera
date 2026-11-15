package local.vivodoubleclick.controller;

import java.io.IOException;

/** libadb 3.1.1 uses InterruptedException for both cancellation and mDNS timeout. */
final class AdbConnectionRetry {
    static final String LIBRARY_DISCOVERY_TIMEOUT = "Timed out while trying to find a valid host address and port";
    static final int MAX_ATTEMPTS = 3;
    interface Connect { boolean run() throws Exception; }
    interface Pause { void run() throws InterruptedException; }
    static final class DiscoveryTimeout extends IOException {
        DiscoveryTimeout(InterruptedException cause) {
            super("本机无线调试端口发现超时（mDNS）。", cause);
        }
    }
    static Exception classify(Exception error) {
        // Only this exact, pinned-library timeout is recoverable. A real await/
        // sleep interruption may already have cleared the thread flag: preserve it.
        if (error instanceof InterruptedException && !Thread.currentThread().isInterrupted()
                && LIBRARY_DISCOVERY_TIMEOUT.equals(error.getMessage()))
            return new DiscoveryTimeout((InterruptedException) error);
        return error;
    }
    static boolean connect(Connect connection, ControllerFlow.Cancellation cancellation, Pause pause) throws Exception {
        for (int attempt=0; attempt<MAX_ATTEMPTS; attempt++) {
            cancellation.check();
            try {
                if (connection.run()) return true;
            } catch (Exception raw) {
                Exception error = classify(raw);
                if (error instanceof InterruptedException || attempt == MAX_ATTEMPTS-1) throw error;
            }
            if (attempt<MAX_ATTEMPTS-1) { cancellation.check(); pause.run(); }
        }
        return false;
    }
    private AdbConnectionRetry() { }
}
