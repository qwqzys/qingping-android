package com.qingping.clearscreen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the foreground guard after reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            Intent service = new Intent(context, GuardService.class);
            context.startForegroundService(service);
        } catch (RuntimeException ignored) {
            // BOOT_COMPLETED is normally an allowed foreground-service start
            // exemption. If an OEM blocks it, the accessibility service is
            // still restored by the system on its own.
        }
    }
}
