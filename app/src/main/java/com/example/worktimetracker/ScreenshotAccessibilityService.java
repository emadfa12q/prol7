package com.example.worktimetracker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Personal-use screenshot capture service.
 * The system starts this service only after the user enables it in Accessibility settings.
 */
public class ScreenshotAccessibilityService extends AccessibilityService {
    private static final long CAPTURE_DELAY_MS = 350L;
    private static final long MIN_CAPTURE_INTERVAL_MS = 1200L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastCaptureByPackage = new HashMap<>();
    private DatabaseHelper db;
    private String pendingPackage = "";
    private long pendingAt = 0L;

    private final Runnable captureRunnable = new Runnable() {
        @Override public void run() {
            capturePending();
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        db = new DatabaseHelper(this);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (db == null) db = new DatabaseHelper(this);
        if (!"true".equals(db.getSetting("auto_screenshot", "true"))) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        CharSequence pkgSeq = event.getPackageName();
        if (pkgSeq == null) return;
        String pkg = pkgSeq.toString();
        if (pkg.trim().isEmpty() || pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return;

        long now = System.currentTimeMillis();
        Long last = lastCaptureByPackage.get(pkg);
        if (last != null && now - last < MIN_CAPTURE_INTERVAL_MS) return;

        pendingPackage = pkg;
        pendingAt = now;
        handler.removeCallbacks(captureRunnable);
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS);
    }

    @Override public void onInterrupt() { }

    private void capturePending() {
        final String pkg = pendingPackage;
        final long eventMs = pendingAt <= 0 ? System.currentTimeMillis() : pendingAt;
        if (pkg == null || pkg.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        lastCaptureByPackage.put(pkg, System.currentTimeMillis());

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                HardwareBuffer buffer = null;
                try {
                    buffer = result.getHardwareBuffer();
                    ColorSpace colorSpace = result.getColorSpace();
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                    if (bitmap == null) return;
                    Bitmap scaled = scaleForStorage(bitmap);
                    String path = saveBitmap(scaled, pkg, eventMs);
                    if (path != null && !path.isEmpty()) {
                        if (db == null) db = new DatabaseHelper(ScreenshotAccessibilityService.this);
                        db.insertScreenshot(eventMs, AppUtil.appLabel(ScreenshotAccessibilityService.this, pkg), pkg, path);
                        sendBroadcast(new Intent(UsageTrackerService.ACTION_REFRESH).setPackage(getPackageName()));
                    }
                    if (scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
                } catch (Exception ignored) {
                } finally {
                    if (buffer != null) buffer.close();
                }
            }

            @Override public void onFailure(int errorCode) {
                if (db == null) db = new DatabaseHelper(ScreenshotAccessibilityService.this);
                db.setSetting("last_screenshot_error", String.valueOf(errorCode));
            }
        });
    }

    private Bitmap scaleForStorage(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int maxW = 720;
        if (w <= maxW) return src.copy(Bitmap.Config.ARGB_8888, false);
        int newH = Math.max(1, (int) ((h * (float) maxW) / w));
        return Bitmap.createScaledBitmap(src, maxW, newH, true);
    }

    private String saveBitmap(Bitmap bitmap, String pkg, long capturedMs) {
        try {
            File dir = new File(getFilesDir(), "screenshots");
            if (!dir.exists() && !dir.mkdirs()) return "";
            String safePkg = pkg == null ? "unknown" : pkg.replaceAll("[^a-zA-Z0-9._-]", "_");
            String name = String.format(Locale.US, "%d_%s.jpg", capturedMs, safePkg);
            File file = new File(dir, name);
            FileOutputStream out = new FileOutputStream(file);
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out);
                out.flush();
            } finally {
                out.close();
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String service = new ComponentName(context, ScreenshotAccessibilityService.class).flattenToString();
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                if (service.equalsIgnoreCase(splitter.next())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static void openAccessibilitySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
