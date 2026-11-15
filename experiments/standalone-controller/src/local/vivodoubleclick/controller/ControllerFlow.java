package local.vivodoubleclick.controller;

/** Ordering is identical for UI actions and unattended restoration. */
final class ControllerFlow {
    interface Progress { void update(String phase); }
    interface Cancellation { void check() throws Exception; }
    interface Gateway<T> {
        void connect() throws Exception;
        void ensureUsb() throws Exception;
        T perform(String action) throws Exception;
        boolean successful(T result);
        boolean running(T result);
        void closeWireless() throws Exception;
        void close();
    }
    static <T> T run(String action, Gateway<T> gateway, Cancellation cancellation) throws Exception {
        return run(action, gateway, cancellation, phase -> { });
    }
    static <T> T run(String action, Gateway<T> gateway, Cancellation cancellation, Progress progress) throws Exception {
        if (!"start".equals(action) && !"stop".equals(action) && !"status".equals(action))
            throw new IllegalArgumentException("Unsupported controller action");
        try {
            cancellation.check();
            progress.update("connect");
            gateway.connect();
            cancellation.check();
            if ("start".equals(action)) { progress.update("usb"); gateway.ensureUsb(); }
            cancellation.check();
            progress.update(action);
            T result = gateway.perform(action);
            cancellation.check();
            if (gateway.successful(result) && gateway.running(result)) {
                progress.update("usb");
                gateway.ensureUsb();
                progress.update("cleanup");
                gateway.closeWireless();
                cancellation.check();
            }
            return result;
        } finally { gateway.close(); }
    }
    static boolean shouldClose(boolean successful, boolean running) { return successful && running; }
    static final long CLOSE_DELAY_MS = 1_000L;
    private ControllerFlow() { }
}
