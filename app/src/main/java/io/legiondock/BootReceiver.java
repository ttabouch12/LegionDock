package io.legiondock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        // After a reboot nothing is docked anymore (wm overrides survive though; the core restores them on next undock)
        if (Modes.prefs(c).getBoolean("start_on_boot", true)) DockService.start(c);
    }
}
