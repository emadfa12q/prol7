package com.example.worktimetracker;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class TimeUtils {
    static final long POLL_MS = 1000L;
    static final long UI_REFRESH_MS = 1000L;
    static final long DEFAULT_IDLE_LIMIT_MS = 10L * 60L * 1000L;
    private TimeUtils() {}

    static String toIso(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    static String gregDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    static String hhmmss(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }

    static String hhmm(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
    }

    static String fmtHms(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h + ":" + String.format(Locale.US, "%02d:%02d", m, s);
    }

    static String fmtHmFa(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + " ساعت و " + String.format(Locale.US, "%02d", m) + " دقیقه";
    }

    static long dayStart(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long nextDayStart(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart(millis));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static long addDays(long millis, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    static boolean sameDay(long a, long b) {
        return dayStart(a) == dayStart(b);
    }
}
