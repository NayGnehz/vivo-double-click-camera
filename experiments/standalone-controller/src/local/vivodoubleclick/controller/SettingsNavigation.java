package local.vivodoubleclick.controller;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.TileService;

/** Open the same settings entry used by Android's wireless-debugging quick-settings tile. */
public final class SettingsNavigation {
    private SettingsNavigation() { }

    public static boolean openWireless(Context context) {
        Intent tile = new Intent(TileService.ACTION_QS_TILE_PREFERENCES)
                .setPackage("com.android.settings")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName("com.android.settings", Build.VERSION.SDK_INT >= 37
                        ? "com.android.settings.development.qstile.AdbWirelessDebuggingDevelopmentTile"
                        : "com.android.settings.development.qstile.DevelopmentTiles$WirelessDebugging"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(tile); return true; }
        catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            Bundle highlight = new Bundle();
            highlight.putString(":settings:fragment_args_key", "toggle_adb_wireless");
            Intent developer = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:show_fragment_args", highlight)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(developer); return true; }
            catch (android.content.ActivityNotFoundException | SecurityException ignored) { return false; }
        }
    }
}
