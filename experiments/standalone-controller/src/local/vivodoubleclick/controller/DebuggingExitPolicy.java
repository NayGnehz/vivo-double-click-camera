package local.vivodoubleclick.controller;

import java.io.IOException;

/** Turns off wireless debugging on explicit UI exit only while USB debugging is enabled. */
final class DebuggingExitPolicy {
    interface SettingsAccess {
        int readUsb() throws Exception;
        int readWireless() throws Exception;
        boolean disableWireless() throws Exception;
        void waitForChange() throws Exception;
    }

    private DebuggingExitPolicy() { }

    static void prepareToClose(SettingsAccess settings) throws Exception {
        int usb = settings.readUsb();
        if (usb == 0) return;
        if (usb != 1) throw new IOException("Could not confirm the USB debugging setting");
        int wireless = settings.readWireless();
        if (wireless == 0) return;
        if (wireless != 1) throw new IOException("Could not read the wireless debugging setting");
        // Recheck immediately before the write. Never turn off both debugging transports.
        usb = settings.readUsb();
        if (usb == 0) return;
        if (usb != 1) throw new IOException("Could not confirm USB debugging before disabling wireless debugging");
        if (!settings.disableWireless()) throw new IOException("Wireless debugging setting write was rejected");
        for (int attempt = 0; attempt < 3; attempt++) {
            settings.waitForChange();
            if (settings.readUsb() != 1) throw new IOException("USB debugging is no longer enabled");
            if (settings.readWireless() == 0) return;
        }
        throw new IOException("Wireless debugging did not turn off after readback");
    }
}
