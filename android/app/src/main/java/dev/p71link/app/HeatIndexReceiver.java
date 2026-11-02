package dev.p71link.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Not exported. Receives the app's own heat index alarm, and the system's package-replaced
 * broadcast to set the alarm again after an update. Boot is not handled: after a restart the alarm
 * is set again when the Weather page is opened.
 */
public final class HeatIndexReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String action = i == null ? null : i.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HeatIndexScheduler.arm(c);
        } else if (HeatIndexScheduler.ACTION_SEND.equals(action)) {
            HeatIndexScheduler.onAlarm(c, i.getLongExtra(HeatIndexScheduler.EXTRA_SLOT, 0), goAsync());
        }
    }
}
