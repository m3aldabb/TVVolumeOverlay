package com.volumeoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * On boot, the Accessibility Service needs to be re-enabled manually by the user
 * (Android security restriction). This receiver does nothing active — the service
 * auto-restarts if it was enabled before reboot on most Android TV firmware.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Accessibility services typically survive reboots automatically.
            // Nothing extra needed here. If the service stops persisting,
            // you can add a notification to remind the user to re-enable it.
        }
    }
}
