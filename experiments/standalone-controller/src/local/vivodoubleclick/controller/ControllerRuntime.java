package local.vivodoubleclick.controller;

import android.content.Context;
import android.provider.Settings;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

final class ControllerRuntime {
    private static final ReentrantLock OPERATION = new ReentrantLock();
    static JSONObject execute(Context context, String action, int port, ControllerFlow.Cancellation cancellation) throws Exception {
        return execute(context, action, port, cancellation, phase -> { });
    }
    static JSONObject execute(Context context, String action, int port, ControllerFlow.Cancellation cancellation,
                              ControllerFlow.Progress progress) throws Exception {
        OPERATION.lockInterruptibly();
        try { return ControllerFlow.run(action, new Gateway(context.getApplicationContext(), port, cancellation), cancellation, progress); }
        finally { OPERATION.unlock(); }
    }
    static void closeInterface(Context context) throws Exception {
        OPERATION.lockInterruptibly();
        try { DebuggingSettings.closeWireless(context); }
        finally { OPERATION.unlock(); }
    }
    private static final class Gateway implements ControllerFlow.Gateway<JSONObject> {
        final Context context;
        final int port;
        final ControllerFlow.Cancellation cancellation;
        LocalAdbManager manager;
        AdbShell shell;
        boolean openedWireless;
        boolean cleaned;
        Gateway(Context context, int port, ControllerFlow.Cancellation cancellation) {
            this.context = context; this.port = port; this.cancellation = cancellation;
        }
        public void connect() throws Exception {
            manager = new LocalAdbManager(context);
            WifiApprovalSession.begin(90_000L);
            if (DebuggingSettings.canWrite(context)) {
                openedWireless = DebuggingSettings.wireless(context) != 1;
                if (!Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1))
                    throw new IOException("Wireless debugging write rejected");
            }
            // A network-confirmation dialog can precede mDNS publication. Retry discovery
            // only within this short operation, never as an idle background polling loop.
            boolean connected = false;
            for (int attempt = 0; attempt < 3 && !connected; attempt++) {
                cancellation.check();
                try { connected = manager.connectLocal(port); }
                catch (InterruptedException cancelled) { throw cancelled; }
                catch (Exception failure) { if (attempt == 2) throw failure; }
                if (!connected && attempt < 2) { cancellation.check(); Thread.sleep(1_000L); }
            }
            if (!connected) throw new IOException("No local wireless debugging connection");
            shell = new AdbShell(manager);
            DeviceController.Result identity = shell.run("id -u", 6);
            if (identity.exitCode != 0 || !"2000".equals(identity.output.trim())) throw new IOException("ADB is not shell UID 2000");
            if (!DebuggingSettings.canWrite(context)) {
                DeviceController.Result grant = shell.run("pm grant local.vivodoubleclick.controller android.permission.WRITE_SECURE_SETTINGS", 8);
                if (grant.exitCode != 0 || !DebuggingSettings.canWrite(context)) throw new IOException("Wireless debugging control permission unavailable");
            }
        }
        public void ensureUsb() throws Exception { DebuggingSettings.ensureUsb(context, shell); }
        public JSONObject perform(String action) throws Exception {
            cancellation.check();
            DeviceController controller = new DeviceController(context, shell);
            return "start".equals(action) ? controller.start() : "stop".equals(action) ? controller.stop() : controller.status();
        }
        public boolean successful(JSONObject result) { return result.optBoolean("ok"); }
        public boolean running(JSONObject result) { return "RUNNING".equals(result.optString("state")); }
        public void closeWireless() throws Exception {
            DebuggingSettings.closeWireless(context);
            if (DebuggingSettings.usb(context) != 1 || DebuggingSettings.wireless(context) != 0)
                throw new IOException("Debugging settings not confirmed after startup");
            cleaned = true;
        }
        public void close() {
            WifiApprovalSession.end();
            if (openedWireless && !cleaned && DebuggingSettings.canWrite(context)) {
                try { DebuggingSettings.closeWireless(context); } catch (Exception ignored) { }
            }
            if (manager != null) try { manager.close(); } catch (IOException ignored) { }
        }
    }
    private ControllerRuntime() { }
}
