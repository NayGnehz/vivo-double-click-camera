package local.vivodoubleclick.controller;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import java.io.IOException;

final class DebuggingSettings {
    static boolean canWrite(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    static int usb(Context context) { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, -1); }
    static int wireless(Context context) { return Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", -1); }
    static void ensureUsb(Context context, AdbShell shell) throws Exception {
        if (readUsb(context, shell)) return;
        if (canWrite(context)) {
            try { Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1); }
            catch (SecurityException ignored) { }
            if (awaitUsb(context, shell)) return;
        }
        if (readUsb(context, shell)) return;
        DeviceController.Result write = shell.run("settings put global adb_enabled 1", 6);
        if (write.exitCode != 0 || !awaitUsb(context, shell)) throw new IOException("Could not confirm USB debugging is enabled");
    }
    private static boolean readUsb(Context context, AdbShell shell) throws Exception {
        try {
            int value = usb(context);
            if (value == 0 || value == 1) return value == 1;
        } catch (SecurityException ignored) { }
        DeviceController.Result result = shell.run("settings get global adb_enabled", 4);
        String value = result.output.trim();
        if (result.exitCode != 0 || !("0".equals(value) || "1".equals(value))) throw new IOException("USB debugging read failed");
        return "1".equals(value);
    }
    private static boolean awaitUsb(Context context, AdbShell shell) throws Exception {
        int confirmed = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            Thread.sleep(250);
            confirmed = readUsb(context, shell) ? confirmed + 1 : 0;
            if (confirmed == 2) return true;
        }
        return false;
    }
    static void closeWireless(Context context) throws Exception {
        DebuggingExitPolicy.prepareToClose(new DebuggingExitPolicy.SettingsAccess() {
            public int readUsb() { return usb(context); }
            public int readWireless() { return wireless(context); }
            public boolean disableWireless() { return Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 0); }
            public void waitForChange() throws InterruptedException { Thread.sleep(250); }
        });
    }
    private DebuggingSettings() { }
}
