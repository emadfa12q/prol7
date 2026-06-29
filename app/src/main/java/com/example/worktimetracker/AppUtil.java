package com.example.worktimetracker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;

import java.security.MessageDigest;
import java.util.Locale;

final class AppUtil {
    private static final String[] PALETTE = {
            "#3b82f6", "#f97316", "#a855f7", "#22c55e", "#eab308",
            "#06b6d4", "#ef4444", "#14b8a6", "#8b5cf6", "#f59e0b",
            "#0ea5e9", "#84cc16", "#ec4899", "#10b981", "#f43f5e"
    };
    private AppUtil() {}

    static String appLabel(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return "Unknown";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label == null ? packageName : label.toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    static String categoryFor(String appName, String packageName) {
        String a = ((appName == null ? "" : appName) + " " + (packageName == null ? "" : packageName)).toLowerCase(Locale.US);
        if (containsAny(a, "chrome", "firefox", "browser", "opera", "brave", "edge")) return "Web Browser";
        if (containsAny(a, "gmail", "mail", "outlook")) return "Email";
        if (containsAny(a, "telegram", "whatsapp", "slack", "discord", "teams", "messenger")) return "Chat";
        if (containsAny(a, "docs", "sheets", "word", "excel", "pdf", "office", "drive")) return "Documents";
        if (containsAny(a, "studio", "code", "termux", "terminal", "github")) return "Code Editor";
        if (containsAny(a, "photos", "gallery", "figma", "canva", "adobe", "sketch")) return "Design Tool";
        if (containsAny(a, "youtube", "vlc", "video", "player", "netflix", "spotify")) return "Media";
        if (containsAny(a, "settings", "launcher", "systemui")) return "System";
        return "Other";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    static String colorFor(String appName) {
        String key = appName == null ? "unknown" : appName.toLowerCase(Locale.US);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            int v = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            if (v < 0) v = -v;
            return PALETTE[v % PALETTE.length];
        } catch (Exception e) {
            return PALETTE[Math.abs(key.hashCode()) % PALETTE.length];
        }
    }

    static int parseColor(String color, int fallback) {
        try { return Color.parseColor(color); } catch (Exception e) { return fallback; }
    }
}
