package com.example.worktimetracker;

import java.util.ArrayList;
import java.util.List;

class ReportData {
    long totalSeconds;
    final List<TopItem> topActivities = new ArrayList<>();
    final List<DailyItem> dailyRows = new ArrayList<>();

    static class TopItem {
        String app;
        String category;
        long seconds;
        int percent;
    }

    static class DailyItem {
        long dayStartMs;
        String jalaliDate;
        String weekday;
        String start;
        String end;
        long total;
        String topApp;
    }
}
