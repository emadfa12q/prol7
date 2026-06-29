package com.example.worktimetracker;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public class UsageTrackerService extends Service {
    static final String ACTION_REFRESH = "com.example.worktimetracker.ACTION_REFRESH";
    private static final String CHANNEL_ID = "tracker";
    private static final int NOTIFICATION_ID = 1183;

    private DatabaseHelper db;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String currentPackage = "";
    private String currentAppName = "";
    private long currentStart = 0L;
    private long currentSegmentId = -1L;
    private long lastRefreshBroadcastMs = 0L;
    private long lastNotificationMs = 0L;
    private String lastNotificationText = "";
    private boolean screenOn = true;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            try { tick(); } catch (Exception ignored) { }
            handler.postDelayed(this, TimeUtils.POLL_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                closeCurrent(System.currentTimeMillis());
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                screenOn = true;
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        db = new DatabaseHelper(this);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی ثبت فعالیت…"));
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);
        handler.post(poller);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poller);
        closeCurrent(System.currentTimeMillis());
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void tick() {
        if (!"true".equals(db.getSetting("auto_track", "true"))) {
            closeCurrent(System.currentTimeMillis());
            updateNotification("ثبت خودکار خاموش است");
            return;
        }
        if (!hasUsageAccess(this)) {
            closeCurrent(System.currentTimeMillis());
            updateNotification("برای ثبت فعالیت، Usage Access را فعال کنید");
            return;
        }
        if (!screenOn) {
            closeCurrent(System.currentTimeMillis());
            updateNotification("صفحه خاموش است؛ ثبت متوقف شد");
            return;
        }
        String pkg = latestForegroundPackage();
        long now = System.currentTimeMillis();
        if (pkg == null || pkg.trim().isEmpty() || pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) {
            closeCurrent(now);
            updateNotification("در حال انتظار برای فعالیت بعدی…");
            sendRefreshBroadcast(false);
            return;
        }
        if (currentPackage.isEmpty()) {
            openCurrent(pkg, now);
        } else if (!currentPackage.equals(pkg)) {
            closeCurrent(now);
            openCurrent(pkg, now);
        } else if (TimeUtils.dayStart(currentStart) != TimeUtils.dayStart(now)) {
            long midnight = TimeUtils.dayStart(now);
            closeCurrent(midnight);
            openCurrent(pkg, midnight);
        }
        updateCurrent(now);
        updateNotification("ثبت زنده: " + currentAppName + " • " + TimeUtils.fmtHms(Math.max(0, (now - currentStart) / 1000L)));
        sendRefreshBroadcast(true);
    }

    private String latestForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        long from = Math.max(0, now - 90L * 1000L);
        UsageEvents events = usm.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String last = null;
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                last = event.getPackageName();
            } else if ((type == UsageEvents.Event.MOVE_TO_BACKGROUND || type == UsageEvents.Event.ACTIVITY_PAUSED) && event.getPackageName() != null && event.getPackageName().equals(last)) {
                last = null;
            }
        }
        return last != null ? last : currentPackage;
    }

    private void openCurrent(String pkg, long start) {
        currentPackage = pkg;
        currentAppName = AppUtil.appLabel(this, pkg);
        currentStart = start;
        currentSegmentId = db.upsertLiveSegment(-1L, currentStart, Math.max(currentStart + 1000L, System.currentTimeMillis()), currentAppName, currentPackage);
        sendRefreshBroadcast(true);
    }

    private void updateCurrent(long end) {
        if (!currentPackage.isEmpty() && currentStart > 0 && end > currentStart) {
            currentSegmentId = db.upsertLiveSegment(currentSegmentId, currentStart, end, currentAppName, currentPackage);
        }
    }

    private void closeCurrent(long end) {
        if (!currentPackage.isEmpty() && currentStart > 0 && end > currentStart) {
            currentSegmentId = db.finalizeLiveSegment(currentSegmentId, currentStart, end, currentAppName, currentPackage);
            sendRefreshBroadcast(true);
        }
        currentPackage = "";
        currentAppName = "";
        currentStart = 0L;
        currentSegmentId = -1L;
    }

    private void sendRefreshBroadcast(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefreshBroadcastMs < TimeUtils.UI_REFRESH_MS) return;
        lastRefreshBroadcastMs = now;
        sendBroadcast(new Intent(ACTION_REFRESH).setPackage(getPackageName()));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "WorkTimeTracker", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ثبت زمان استفاده از برنامه‌ها");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setContentTitle("WorkTimeTracker Pro")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        return b.build();
    }

    private void updateNotification(String text) {
        long now = System.currentTimeMillis();
        if (text != null && text.equals(lastNotificationText) && now - lastNotificationMs < 3000L) return;
        lastNotificationText = text == null ? "" : text;
        lastNotificationMs = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(lastNotificationText));
    }

    static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            ApplicationInfo ai = context.getApplicationInfo();
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, ai.uid, context.getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, ai.uid, context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static void openUsageAccessSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
