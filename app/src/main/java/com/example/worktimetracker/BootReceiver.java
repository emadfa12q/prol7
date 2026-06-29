package com.example.worktimetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        DatabaseHelper db = new DatabaseHelper(context);
        if (!"true".equals(db.getSetting("auto_track", "true"))) return;
        Intent service = new Intent(context, UsageTrackerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
        else context.startService(service);
    }
}
