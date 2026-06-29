package com.example.worktimetracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "worktime_tracker_pro_android.db";
    private static final int DB_VERSION = 3;

    DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS segments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ms INTEGER NOT NULL," +
                "end_ms INTEGER NOT NULL," +
                "start_ts TEXT NOT NULL," +
                "end_ts TEXT NOT NULL," +
                "greg_date TEXT NOT NULL," +
                "jalali_date TEXT NOT NULL," +
                "app_name TEXT," +
                "package_name TEXT," +
                "window_title TEXT," +
                "domain TEXT," +
                "duration_seconds INTEGER NOT NULL," +
                "tag TEXT," +
                "category TEXT," +
                "color TEXT," +
                "screenshot_path TEXT," +
                "device_name TEXT," +
                "android_user TEXT," +
                "session_id TEXT" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_start ON segments(start_ms)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_date ON segments(greg_date)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
        createScreenshotTables(db);
        seedSettings(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
        createScreenshotTables(db);
        putSettingIfMissing(db, "auto_screenshot", "true");
        putSettingIfMissing(db, "last_screenshot_error", "");
        putSettingIfMissing(db, "realtime_logging", "true");
    }

    private void createScreenshotTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS screenshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "captured_ms INTEGER NOT NULL," +
                "captured_ts TEXT NOT NULL," +
                "greg_date TEXT NOT NULL," +
                "jalali_date TEXT NOT NULL," +
                "app_name TEXT," +
                "package_name TEXT," +
                "path TEXT NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_screenshots_pkg_time ON screenshots(package_name, captured_ms)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_screenshots_time ON screenshots(captured_ms)");
    }

    private void seedSettings(SQLiteDatabase db) {
        putSettingIfMissing(db, "theme", "dark");
        putSettingIfMissing(db, "auto_track", "true");
        putSettingIfMissing(db, "idle_limit_seconds", String.valueOf(TimeUtils.DEFAULT_IDLE_LIMIT_MS / 1000));
        putSettingIfMissing(db, "auto_screenshot", "true");
        putSettingIfMissing(db, "last_screenshot_error", "");
        putSettingIfMissing(db, "realtime_logging", "true");
    }

    String getSetting(String key, String def) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : def;
        }
    }

    void setSetting(String key, String value) {
        putSetting(getWritableDatabase(), key, value);
    }

    private void putSetting(SQLiteDatabase db, String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void putSettingIfMissing(SQLiteDatabase db, String key, String value) {
        try (Cursor c = db.rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            if (c.moveToFirst()) return;
        }
        putSetting(db, key, value);
    }

    synchronized void insertSegment(long startMs, long endMs, String appName, String packageName) {
        if (endMs <= startMs || endMs - startMs < 1500) return;
        SQLiteDatabase db = getWritableDatabase();
        long currentStart = startMs;
        db.beginTransaction();
        try {
            while (currentStart < endMs) {
                long currentEnd = Math.min(endMs, TimeUtils.nextDayStart(currentStart));
                long duration = Math.max(0, (currentEnd - currentStart) / 1000L);
                if (duration > 0) {
                    String safeName = appName == null || appName.isEmpty() ? "Unknown" : appName;
                    ContentValues cv = new ContentValues();
                    cv.put("start_ms", currentStart);
                    cv.put("end_ms", currentEnd);
                    cv.put("start_ts", TimeUtils.toIso(currentStart));
                    cv.put("end_ts", TimeUtils.toIso(currentEnd));
                    cv.put("greg_date", TimeUtils.gregDate(currentStart));
                    cv.put("jalali_date", JalaliUtils.dateString(currentStart));
                    cv.put("app_name", safeName);
                    cv.put("package_name", packageName == null ? "" : packageName);
                    cv.put("window_title", safeName);
                    cv.put("domain", "");
                    cv.put("duration_seconds", duration);
                    cv.put("tag", "");
                    cv.put("category", AppUtil.categoryFor(safeName, packageName));
                    cv.put("color", AppUtil.colorFor(safeName));
                    cv.put("screenshot_path", findScreenshotPath(packageName == null ? "" : packageName, currentStart, currentEnd));
                    cv.put("device_name", android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
                    cv.put("android_user", "Android");
                    cv.put("session_id", "foreground-service");
                    db.insert("segments", null, cv);
                }
                currentStart = currentEnd;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized long upsertLiveSegment(long existingId, long startMs, long endMs, String appName, String packageName) {
        if (endMs <= startMs) endMs = startMs + 1000L;
        SQLiteDatabase db = getWritableDatabase();
        if (existingId > 0) {
            ContentValues cv = liveUpdateValues(startMs, endMs, packageName);
            cv.put("session_id", "foreground-service-live");
            int updated = db.update("segments", cv, "id=?", new String[]{String.valueOf(existingId)});
            if (updated > 0) return existingId;
        }
        String safeName = appName == null || appName.isEmpty() ? "Unknown" : appName;
        ContentValues cv = new ContentValues();
        cv.put("start_ms", startMs);
        cv.put("end_ms", endMs);
        cv.put("start_ts", TimeUtils.toIso(startMs));
        cv.put("end_ts", TimeUtils.toIso(endMs));
        cv.put("greg_date", TimeUtils.gregDate(startMs));
        cv.put("jalali_date", JalaliUtils.dateString(startMs));
        cv.put("app_name", safeName);
        cv.put("package_name", packageName == null ? "" : packageName);
        cv.put("window_title", safeName);
        cv.put("domain", "");
        cv.put("duration_seconds", Math.max(1L, (endMs - startMs) / 1000L));
        cv.put("tag", "");
        cv.put("category", AppUtil.categoryFor(safeName, packageName));
        cv.put("color", AppUtil.colorFor(safeName));
        cv.put("screenshot_path", findScreenshotPath(packageName == null ? "" : packageName, startMs, endMs));
        cv.put("device_name", android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
        cv.put("android_user", "Android");
        cv.put("session_id", "foreground-service-live");
        return db.insert("segments", null, cv);
    }

    synchronized long finalizeLiveSegment(long existingId, long startMs, long endMs, String appName, String packageName) {
        if (endMs <= startMs || endMs - startMs < 800L) {
            if (existingId > 0) {
                try { getWritableDatabase().delete("segments", "id=?", new String[]{String.valueOf(existingId)}); } catch (Exception ignored) { }
            }
            return -1L;
        }
        long id = upsertLiveSegment(existingId, startMs, endMs, appName, packageName);
        ContentValues cv = new ContentValues();
        cv.put("session_id", "foreground-service");
        getWritableDatabase().update("segments", cv, "id=?", new String[]{String.valueOf(id)});
        return id;
    }

    private ContentValues liveUpdateValues(long startMs, long endMs, String packageName) {
        ContentValues cv = new ContentValues();
        cv.put("end_ms", endMs);
        cv.put("end_ts", TimeUtils.toIso(endMs));
        cv.put("duration_seconds", Math.max(1L, (endMs - startMs) / 1000L));
        String shot = findScreenshotPath(packageName == null ? "" : packageName, startMs, endMs);
        if (shot != null && !shot.isEmpty()) cv.put("screenshot_path", shot);
        return cv;
    }


    synchronized void insertScreenshot(long capturedMs, String appName, String packageName, String path) {
        if (path == null || path.trim().isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("captured_ms", capturedMs);
        cv.put("captured_ts", TimeUtils.toIso(capturedMs));
        cv.put("greg_date", TimeUtils.gregDate(capturedMs));
        cv.put("jalali_date", JalaliUtils.dateString(capturedMs));
        cv.put("app_name", appName == null ? "Unknown" : appName);
        cv.put("package_name", packageName == null ? "" : packageName);
        cv.put("path", path);
        db.insert("screenshots", null, cv);
        attachScreenshotToOpenSegments(db, packageName == null ? "" : packageName, capturedMs, path);
        pruneOldScreenshots(db, 1200);
    }

    private void attachScreenshotToOpenSegments(SQLiteDatabase db, String packageName, long capturedMs, String path) {
        ContentValues cv = new ContentValues();
        cv.put("screenshot_path", path);
        db.update("segments", cv,
                "package_name=? AND screenshot_path='' AND start_ms<=? AND end_ms>=?",
                new String[]{packageName, String.valueOf(capturedMs + 3000L), String.valueOf(capturedMs - 3000L)});
    }

    private void pruneOldScreenshots(SQLiteDatabase db, int keep) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM screenshots", null)) {
            if (!c.moveToFirst() || c.getInt(0) <= keep) return;
        } catch (Exception e) { return; }
        try (Cursor c = db.rawQuery("SELECT path FROM screenshots ORDER BY captured_ms DESC LIMIT -1 OFFSET " + keep, null)) {
            while (c.moveToNext()) {
                String p = c.getString(0);
                if (p != null && !p.isEmpty()) {
                    try { new java.io.File(p).delete(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        db.execSQL("DELETE FROM screenshots WHERE id NOT IN (SELECT id FROM screenshots ORDER BY captured_ms DESC LIMIT " + keep + ")");
    }

    private String findScreenshotPath(String packageName, long startMs, long endMs) {
        if (packageName == null) packageName = "";
        long from = Math.max(0, startMs - 3500L);
        long to = endMs + 6000L;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT path FROM screenshots WHERE package_name=? AND captured_ms>=? AND captured_ms<=? ORDER BY ABS(captured_ms-?) ASC LIMIT 1",
                new String[]{packageName, String.valueOf(from), String.valueOf(to), String.valueOf(startMs)})) {
            if (c.moveToFirst()) return c.getString(0) == null ? "" : c.getString(0);
        } catch (Exception ignored) { }
        return "";
    }

    List<ActivitySegment> queryDay(long dayMillis) {
        long start = TimeUtils.dayStart(dayMillis);
        long end = TimeUtils.nextDayStart(dayMillis);
        return queryBetweenMillis(start, end);
    }

    List<ActivitySegment> queryBetweenDays(long fromDayMillis, long toDayMillisInclusive) {
        long start = TimeUtils.dayStart(fromDayMillis);
        long end = TimeUtils.nextDayStart(toDayMillisInclusive);
        return queryBetweenMillis(start, end);
    }

    private List<ActivitySegment> queryBetweenMillis(long start, long end) {
        List<ActivitySegment> rows = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM segments WHERE start_ms>=? AND start_ms<? ORDER BY start_ms ASC",
                new String[]{String.valueOf(start), String.valueOf(end)})) {
            while (c.moveToNext()) rows.add(fromCursor(c));
        }
        return rows;
    }

    long totalBetweenDays(long fromDayMillis, long toDayMillisInclusive) {
        long total = 0;
        for (ActivitySegment s : queryBetweenDays(fromDayMillis, toDayMillisInclusive)) total += s.durationSeconds;
        return total;
    }

    void updateTag(List<Long> ids, String tag) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("tag", tag == null ? "" : tag.trim());
            for (Long id : ids) db.update("segments", cv, "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    ReportData buildRangeReport(List<ActivitySegment> rows) {
        ReportData report = new ReportData();
        Map<String, Long> appTotals = new HashMap<>();
        Map<String, String> appCategories = new HashMap<>();
        Map<Long, ReportData.DailyItem> daily = new LinkedHashMap<>();
        Map<Long, Map<String, Long>> dailyApps = new HashMap<>();

        for (ActivitySegment r : rows) {
            report.totalSeconds += r.durationSeconds;
            String app = r.appName == null ? "Unknown" : r.appName;
            appTotals.put(app, appTotals.containsKey(app) ? appTotals.get(app) + r.durationSeconds : r.durationSeconds);
            appCategories.put(app, r.category == null ? "Other" : r.category);
            long day = TimeUtils.dayStart(r.startMs);
            ReportData.DailyItem item = daily.get(day);
            if (item == null) {
                item = new ReportData.DailyItem();
                item.dayStartMs = day;
                item.jalaliDate = JalaliUtils.dateString(day);
                item.weekday = JalaliUtils.weekdayName(day);
                item.start = TimeUtils.hhmm(r.startMs);
                item.end = TimeUtils.hhmm(r.endMs);
                daily.put(day, item);
                dailyApps.put(day, new HashMap<String, Long>());
            }
            item.total += r.durationSeconds;
            if (r.startMs < parseDayTime(item.dayStartMs, item.start)) item.start = TimeUtils.hhmm(r.startMs);
            if (r.endMs > parseDayTime(item.dayStartMs, item.end)) item.end = TimeUtils.hhmm(r.endMs);
            Map<String, Long> dm = dailyApps.get(day);
            dm.put(app, dm.containsKey(app) ? dm.get(app) + r.durationSeconds : r.durationSeconds);
        }

        List<Map.Entry<String, Long>> entries = new ArrayList<>(appTotals.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            Map.Entry<String, Long> e = entries.get(i);
            ReportData.TopItem t = new ReportData.TopItem();
            t.app = e.getKey();
            t.category = appCategories.containsKey(e.getKey()) ? appCategories.get(e.getKey()) : "Other";
            t.seconds = e.getValue();
            t.percent = report.totalSeconds == 0 ? 0 : (int) ((e.getValue() * 100L) / report.totalSeconds);
            report.topActivities.add(t);
        }

        List<Long> dayKeys = new ArrayList<>(daily.keySet());
        Collections.sort(dayKeys);
        for (Long day : dayKeys) {
            ReportData.DailyItem item = daily.get(day);
            Map<String, Long> dm = dailyApps.get(day);
            String top = "";
            long topSec = -1;
            for (Map.Entry<String, Long> e : dm.entrySet()) {
                if (e.getValue() > topSec) { topSec = e.getValue(); top = e.getKey(); }
            }
            item.topApp = top;
            report.dailyRows.add(item);
        }
        return report;
    }

    private long parseDayTime(long dayStart, String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return dayStart + (Long.parseLong(p[0]) * 3600L + Long.parseLong(p[1]) * 60L) * 1000L;
        } catch (Exception e) { return dayStart; }
    }

    private ActivitySegment fromCursor(Cursor c) {
        ActivitySegment s = new ActivitySegment();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.startMs = c.getLong(c.getColumnIndexOrThrow("start_ms"));
        s.endMs = c.getLong(c.getColumnIndexOrThrow("end_ms"));
        s.gregDate = c.getString(c.getColumnIndexOrThrow("greg_date"));
        s.jalaliDate = c.getString(c.getColumnIndexOrThrow("jalali_date"));
        s.appName = text(c, "app_name", "Unknown");
        s.packageName = text(c, "package_name", "");
        s.title = text(c, "window_title", s.appName);
        s.durationSeconds = c.getLong(c.getColumnIndexOrThrow("duration_seconds"));
        s.tag = text(c, "tag", "");
        s.category = text(c, "category", AppUtil.categoryFor(s.appName, s.packageName));
        s.color = text(c, "color", AppUtil.colorFor(s.appName));
        s.screenshotPath = text(c, "screenshot_path", "");
        s.live = "foreground-service-live".equals(text(c, "session_id", ""));
        if (s.screenshotPath == null || s.screenshotPath.isEmpty()) {
            s.screenshotPath = findScreenshotPath(s.packageName, s.startMs, s.endMs);
        }
        return s;
    }

    private String text(Cursor c, String name, String def) {
        int idx = c.getColumnIndex(name);
        if (idx < 0 || c.isNull(idx)) return def;
        String v = c.getString(idx);
        return v == null ? def : v;
    }
}
