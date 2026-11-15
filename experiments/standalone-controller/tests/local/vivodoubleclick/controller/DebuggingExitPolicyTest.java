package local.vivodoubleclick.controller;

/** Regression checks for exit ordering and the conditions that preserve wireless debugging. */
public final class DebuggingExitPolicyTest {
    private static int assertions;
    private static final class Settings implements DebuggingExitPolicy.SettingsAccess {
        int usb = 1, wireless = 1, usbReads, wirelessReads, writes, waits;
        int flipUsbAt = Integer.MAX_VALUE, settleAt = 1;
        boolean accept = true, denied;
        public int readUsb() { return ++usbReads >= flipUsbAt ? 0 : usb; }
        public int readWireless() { wirelessReads++; return wireless; }
        public boolean disableWireless() {
            writes++;
            if (denied) throw new SecurityException("denied");
            return accept;
        }
        public void waitForChange() { if (++waits >= settleAt) wireless = 0; }
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(Settings settings, Class<?> expected) throws Exception {
        try { DebuggingExitPolicy.prepareToClose(settings); }
        catch (Exception error) { check(expected.isInstance(error), "wrong failure type"); return; }
        throw new AssertionError("exit should not complete on an unconfirmed setting");
    }
    public static void main(String[] args) throws Exception {
        Settings s = new Settings();
        DebuggingExitPolicy.prepareToClose(s);
        check(s.writes == 1 && s.wireless == 0 && s.usb == 1, "USB retained, wireless off");
        check(s.usbReads == 3 && s.waits == 1, "USB rechecked before write and after it");
        s = new Settings(); s.usb = 0;
        DebuggingExitPolicy.prepareToClose(s);
        check(s.writes == 0 && s.wirelessReads == 0, "USB off leaves wireless untouched");
        s = new Settings(); s.wireless = 0;
        DebuggingExitPolicy.prepareToClose(s);
        check(s.writes == 0 && s.waits == 0, "wireless already off is a no-op");
        s = new Settings(); s.flipUsbAt = 2;
        DebuggingExitPolicy.prepareToClose(s);
        check(s.writes == 0, "USB turned off before write preserves wireless");
        s = new Settings(); s.usb = -1;
        fails(s, java.io.IOException.class);
        check(s.writes == 0, "unknown USB setting is not changed");
        s = new Settings(); s.wireless = -1;
        fails(s, java.io.IOException.class);
        check(s.writes == 0, "unknown wireless setting is not changed");
        s = new Settings(); s.accept = false;
        fails(s, java.io.IOException.class);
        check(s.writes == 1 && s.waits == 0, "rejected write prevents exit");
        s = new Settings(); s.denied = true;
        fails(s, SecurityException.class);
        s = new Settings(); s.settleAt = 3;
        DebuggingExitPolicy.prepareToClose(s);
        check(s.writes == 1 && s.waits == 3, "delayed settings observer is awaited");
        s = new Settings(); s.settleAt = 99;
        fails(s, java.io.IOException.class);
        check(s.waits == 3 && s.writes == 1, "bounded readback, no repeated writes");
        s = new Settings(); s.flipUsbAt = 3;
        fails(s, java.io.IOException.class);
        System.out.println("DebuggingExitPolicyTest: " + assertions + " assertions passed");
    }
}
