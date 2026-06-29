package com.example.worktimetracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private DatabaseHelper db;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean uiTickerActive = false;
    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            if (!uiTickerActive) return;
            refreshAll();
            uiHandler.postDelayed(this, TimeUtils.UI_REFRESH_MS);
        }
    };
    private boolean dark;
    private long currentDayMs;
    private long reportFromMs;
    private long reportToMs;
    private int pageIndex = 0;
    private boolean appUnlocked = false;
    private String pinBuffer = "";
    private TextView pinDisplay;
    private int timelineZoomIndex = 0;
    private TextView timelineZoomLabel;
    private final int[] timelineZoomHours = new int[]{24, 12, 6, 3};

    private FrameLayout content;
    private ScrollView dashboardPage;
    private ScrollView activityPage;
    private ScrollView reportsPage;
    private ScrollView settingsPage;
    private Button navHome, navActivity, navReports, navSettings;
    private LinearLayout rootLayout;
    private View headerView;
    private View bottomNavView;

    private MiniTimelineView timelineView;
    private TextView statusChip;
    private TextView todayTotalValue;
    private TextView todayAppsValue;
    private TextView monthTotalValue;
    private LinearLayout dashboardTopApps;
    private TextView usageStateText;
    private TextView screenshotStateText;

    private TextView activityDateTitle;
    private EditText searchInput;
    private Spinner filterSpinner;
    private LinearLayout activityList;
    private TextView activityCountLabel;
    private final List<ActivitySegment> rows = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();

    private EditText fromInput;
    private EditText toInput;
    private TextView reportTotalValue;
    private TextView reportDaysValue;
    private TextView reportTopValue;
    private LinearLayout reportTopList;
    private LinearLayout reportDailyList;
    private ReportData currentReport = new ReportData();

    private int bg, surface, card, cardAlt, text, muted, border, accent, green, red, blue, orange;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshAll(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DatabaseHelper(this);
        dark = "dark".equals(db.getSetting("theme", "dark"));
        currentDayMs = TimeUtils.dayStart(System.currentTimeMillis());
        applyPalette();
        configureSystemBars();
        requestNotificationPermission();
        startTrackerService();
        buildPinGate();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(UsageTrackerService.ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(refreshReceiver, filter);
        uiTickerActive = true;
        uiHandler.removeCallbacks(uiTicker);
        uiHandler.post(uiTicker);
    }

    @Override protected void onStop() {
        uiTickerActive = false;
        uiHandler.removeCallbacks(uiTicker);
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void configureSystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(surface);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void applyResponsiveInsets() {
        if (rootLayout == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                applySafePadding(top, bottom);
                return insets;
            });
            rootLayout.requestApplyInsets();
        } else {
            applySafePadding(0, 0);
        }
    }

    private void applySafePadding(int topInset, int bottomInset) {
        if (headerView != null) headerView.setPadding(dp(16), dp(10) + topInset, dp(16), dp(8));
        if (bottomNavView != null) bottomNavView.setPadding(dp(10), dp(7), dp(10), dp(8) + bottomInset);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1183);
        }
    }

    private void startTrackerService() {
        Intent service = new Intent(this, UsageTrackerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
    }

    private void applyPalette() {
        accent = Color.parseColor("#00C896");
        green = Color.parseColor("#34D399");
        red = Color.parseColor("#F87171");
        blue = Color.parseColor("#7DD3FC");
        orange = Color.parseColor("#FB923C");
        if (dark) {
            bg = Color.parseColor("#0E1417");
            surface = Color.parseColor("#151D21");
            card = Color.parseColor("#10181C");
            cardAlt = Color.parseColor("#192328");
            text = Color.parseColor("#EEF5F7");
            muted = Color.parseColor("#94A3A9");
            border = Color.parseColor("#2E3A40");
        } else {
            bg = Color.parseColor("#F1F5F9");
            surface = Color.WHITE;
            card = Color.WHITE;
            cardAlt = Color.parseColor("#F8FAFC");
            text = Color.parseColor("#0F172A");
            muted = Color.parseColor("#64748B");
            border = Color.parseColor("#D4DEE8");
        }
    }

    private void buildPinGate() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        root.setBackgroundColor(bg);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(dp(18), dp(24) + top, dp(18), dp(24) + bottom);
                return insets;
            });
            root.requestApplyInsets();
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        rounded(panel, surface, border, 22);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        TextView title = label("Work Time Tracker");
        title.setGravity(Gravity.CENTER);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = smallMuted("قفل عددی برنامه");
        sub.setGravity(Gravity.CENTER);
        panel.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        pinDisplay = label("••••");
        pinDisplay.setGravity(Gravity.CENTER);
        pinDisplay.setTextSize(28);
        pinDisplay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinDisplay.setTextColor(accent);
        pinDisplay.setPadding(dp(10), dp(18), dp(10), dp(18));
        rounded(pinDisplay, cardAlt, border, 18);
        LinearLayout.LayoutParams displayLp = new LinearLayout.LayoutParams(-1, dp(72));
        displayLp.setMargins(0, dp(18), 0, dp(12));
        panel.addView(pinDisplay, displayLp);

        String[][] keys = new String[][]{
                {"7", "8", "9", "⌫"},
                {"4", "5", "6", "C"},
                {"1", "2", "3", "="},
                {"0", "00", ".", "+"}
        };
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                Button b = "=".equals(key) ? primaryButton(key) : secondaryButton(key);
                b.setTextSize(18);
                b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                b.setOnClickListener(v -> handlePinKey(((Button) v).getText().toString()));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(b, lp);
            }
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView hint = smallMuted("برای ورود، رمز را وارد کن و = را بزن.");
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(0, dp(12), 0, 0);
        panel.addView(hint, hintLp);
    }

    private void handlePinKey(String key) {
        if ("C".equals(key)) {
            pinBuffer = "";
        } else if ("⌫".equals(key)) {
            if (!pinBuffer.isEmpty()) pinBuffer = pinBuffer.substring(0, pinBuffer.length() - 1);
        } else if ("=".equals(key)) {
            if ("1183".equals(pinBuffer)) {
                appUnlocked = true;
                pinBuffer = "";
                buildUi();
                refreshAll();
                return;
            }
            pinBuffer = "";
            Toast.makeText(this, "رمز اشتباه است.", Toast.LENGTH_SHORT).show();
        } else if (key.matches("\\d+") && pinBuffer.length() < 12) {
            pinBuffer += key;
        }
        updatePinDisplay();
    }

    private void updatePinDisplay() {
        if (pinDisplay == null) return;
        if (pinBuffer.isEmpty()) pinDisplay.setText("••••");
        else {
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < pinBuffer.length(); i++) masked.append('•');
            pinDisplay.setText(masked.toString());
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        setContentView(root);

        headerView = buildHeader();
        root.addView(headerView, new LinearLayout.LayoutParams(-1, -2));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        dashboardPage = wrapPage(buildDashboardContent());
        activityPage = wrapPage(buildActivityContent());
        reportsPage = wrapPage(buildReportsContent());
        settingsPage = wrapPage(buildSettingsContent());
        content.addView(dashboardPage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(activityPage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(reportsPage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));

        bottomNavView = buildBottomNav();
        root.addView(bottomNavView, new LinearLayout.LayoutParams(-1, -2));
        applyResponsiveInsets();
        showPage(0);
    }

    private View buildHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(8));
        box.setBackgroundColor(bg);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Work Time Tracker");
        title.setTextColor(text);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        statusChip = chip("نسخه شخصی", accent, dark ? Color.parseColor("#12342F") : Color.parseColor("#DCFCE7"));
        row.addView(statusChip, new LinearLayout.LayoutParams(-2, dp(34)));
        box.addView(row);

        TextView sub = smallMuted("طراحی موبایل‌پسند، گزارش‌گیری و ثبت فعالیت خودکار");
        sub.setPadding(0, dp(4), 0, 0);
        box.addView(sub);
        return box;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(10), dp(8), dp(10), dp(10));
        rounded(nav, surface, border, 0);

        navHome = bottomButton("خانه");
        navActivity = bottomButton("فعالیت‌ها");
        navReports = bottomButton("گزارش‌ها");
        navSettings = bottomButton("تنظیمات");
        navHome.setOnClickListener(v -> showPage(0));
        navActivity.setOnClickListener(v -> showPage(1));
        navReports.setOnClickListener(v -> { showPage(2); generateReport(); });
        navSettings.setOnClickListener(v -> showPage(3));

        nav.addView(navHome, new LinearLayout.LayoutParams(0, dp(46), 1));
        nav.addView(navActivity, new LinearLayout.LayoutParams(0, dp(46), 1));
        nav.addView(navReports, new LinearLayout.LayoutParams(0, dp(46), 1));
        nav.addView(navSettings, new LinearLayout.LayoutParams(0, dp(46), 1));
        return nav;
    }

    private LinearLayout buildDashboardContent() {
        LinearLayout page = pageContainer();
        page.addView(sectionHeader("خانه", "خلاصه امروز و روند استفاده"));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        todayTotalValue = statCard(stats, "امروز", "0:00", green);
        todayAppsValue = statCard(stats, "برنامه‌ها", "0", blue);
        page.addView(stats);

        LinearLayout month = cardBox();
        TextView monthTitle = smallMuted("جمع ماه جاری");
        monthTotalValue = bigText("0:00", orange);
        month.addView(monthTitle);
        month.addView(monthTotalValue);
        page.addView(month);

        timelineView = new MiniTimelineView(this);
        LinearLayout tl = cardBox();
        LinearLayout tlHead = new LinearLayout(this);
        tlHead.setGravity(Gravity.CENTER_VERTICAL);
        tlHead.addView(titleText("نمای کلی امروز"), new LinearLayout.LayoutParams(0, -2, 1));
        Button zoomOut = secondaryButton("−");
        Button zoomIn = primaryButton("+");
        timelineZoomLabel = chip("24h", accent, dark ? Color.parseColor("#12342F") : Color.parseColor("#DCFCE7"));
        zoomOut.setTextSize(18); zoomIn.setTextSize(18);
        zoomOut.setOnClickListener(v -> zoomTimeline(-1));
        zoomIn.setOnClickListener(v -> zoomTimeline(1));
        tlHead.addView(zoomOut, new LinearLayout.LayoutParams(dp(46), dp(42)));
        tlHead.addView(timelineZoomLabel, new LinearLayout.LayoutParams(dp(68), dp(36)));
        tlHead.addView(zoomIn, new LinearLayout.LayoutParams(dp(46), dp(42)));
        tl.addView(tlHead);
        tl.addView(timelineView, new LinearLayout.LayoutParams(-1, dp(160)));
        page.addView(tl);
        updateTimelineZoomUi();

        LinearLayout top = cardBox();
        top.addView(titleText("برنامه‌های پرتکرار امروز"));
        dashboardTopApps = new LinearLayout(this);
        dashboardTopApps.setOrientation(LinearLayout.VERTICAL);
        top.addView(dashboardTopApps);
        page.addView(top);
        return page;
    }

    private LinearLayout buildActivityContent() {
        LinearLayout page = pageContainer();
        page.addView(sectionHeader("فعالیت‌ها", "لیست موبایل‌پسند به‌جای جدول شلوغ"));

        LinearLayout dateCard = cardBox();
        activityDateTitle = titleText("");
        dateCard.addView(activityDateTitle);
        LinearLayout dateBtns = new LinearLayout(this);
        dateBtns.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = secondaryButton("روز قبل");
        Button today = secondaryButton("امروز");
        Button next = secondaryButton("روز بعد");
        Button pick = primaryButton("انتخاب تاریخ");
        prev.setOnClickListener(v -> { currentDayMs = TimeUtils.addDays(currentDayMs, -1); refreshAll(); });
        today.setOnClickListener(v -> { currentDayMs = TimeUtils.dayStart(System.currentTimeMillis()); refreshAll(); });
        next.setOnClickListener(v -> { currentDayMs = TimeUtils.addDays(currentDayMs, 1); refreshAll(); });
        pick.setOnClickListener(v -> openDateDialog());
        dateBtns.addView(prev, new LinearLayout.LayoutParams(0, dp(42), 1));
        dateBtns.addView(today, new LinearLayout.LayoutParams(0, dp(42), 1));
        dateBtns.addView(next, new LinearLayout.LayoutParams(0, dp(42), 1));
        dateCard.addView(dateBtns);
        dateCard.addView(pick, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(dateCard);

        LinearLayout filters = cardBox();
        searchInput = editText("جستجو در برنامه، عنوان یا تگ...");
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { fillActivityList(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        filterSpinner = new Spinner(this);
        String[] modes = {"همه", "تگ‌دار", "بدون تگ", "برنامه‌ها", "سندها"};
        filterSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { fillActivityList(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        filters.addView(searchInput, new LinearLayout.LayoutParams(-1, dp(46)));
        filters.addView(filterSpinner, new LinearLayout.LayoutParams(-1, dp(46)));
        page.addView(filters);

        LinearLayout action = new LinearLayout(this);
        action.setOrientation(LinearLayout.HORIZONTAL);
        Button tag = primaryButton("افزودن تگ به انتخاب‌شده‌ها");
        Button clear = secondaryButton("لغو انتخاب");
        tag.setOnClickListener(v -> addTagToSelected());
        clear.setOnClickListener(v -> { selectedIds.clear(); fillActivityList(); });
        action.addView(tag, new LinearLayout.LayoutParams(0, dp(44), 2));
        action.addView(clear, new LinearLayout.LayoutParams(0, dp(44), 1));
        page.addView(action);

        activityCountLabel = smallMuted("0 فعالیت");
        page.addView(activityCountLabel);
        activityList = new LinearLayout(this);
        activityList.setOrientation(LinearLayout.VERTICAL);
        page.addView(activityList);
        return page;
    }

    private LinearLayout buildReportsContent() {
        LinearLayout page = pageContainer();
        page.addView(sectionHeader("گزارش‌ها", "بازه زمانی، خلاصه و خروجی PDF"));

        LinearLayout filters = cardBox();
        fromInput = editText("از: 1405/04/01");
        toInput = editText("تا: 1405/04/08");
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.addView(fromInput, new LinearLayout.LayoutParams(0, dp(46), 1));
        inputRow.addView(toInput, new LinearLayout.LayoutParams(0, dp(46), 1));
        filters.addView(inputRow);
        LinearLayout btnRow = new LinearLayout(this);
        Button current = secondaryButton("ماه جاری");
        Button show = primaryButton("نمایش گزارش");
        Button pdf = accentButton("PDF");
        current.setOnClickListener(v -> { setCurrentMonthReport(); generateReport(); });
        show.setOnClickListener(v -> generateReport());
        pdf.setOnClickListener(v -> exportPdf());
        btnRow.addView(current, new LinearLayout.LayoutParams(0, dp(44), 1));
        btnRow.addView(show, new LinearLayout.LayoutParams(0, dp(44), 1));
        btnRow.addView(pdf, new LinearLayout.LayoutParams(0, dp(44), 1));
        filters.addView(btnRow);
        page.addView(filters);

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        reportTotalValue = statCard(summary, "جمع بازه", "0:00", green);
        reportDaysValue = statCard(summary, "روزها", "0", blue);
        page.addView(summary);

        LinearLayout topSummary = cardBox();
        topSummary.addView(smallMuted("بیشترین زمان"));
        reportTopValue = bigText("-", orange);
        topSummary.addView(reportTopValue);
        page.addView(topSummary);

        LinearLayout top = cardBox();
        top.addView(titleText("۵ برنامه با بیشترین زمان"));
        reportTopList = new LinearLayout(this);
        reportTopList.setOrientation(LinearLayout.VERTICAL);
        top.addView(reportTopList);
        page.addView(top);

        LinearLayout daily = cardBox();
        daily.addView(titleText("گزارش روزانه"));
        reportDailyList = new LinearLayout(this);
        reportDailyList.setOrientation(LinearLayout.VERTICAL);
        daily.addView(reportDailyList);
        page.addView(daily);

        setCurrentMonthReport();
        return page;
    }

    private LinearLayout buildSettingsContent() {
        LinearLayout page = pageContainer();
        page.addView(sectionHeader("تنظیمات", "مجوزها، تم و رفتار ثبت"));

        LinearLayout permission = cardBox();
        permission.addView(titleText("مجوزها"));
        permission.addView(smallMuted("Usage Access زمان برنامه‌ها را ثبت می‌کند؛ Screenshot Accessibility از هر فعالیت جدید یک تصویر کوچک ذخیره می‌کند."));
        usageStateText = smallMuted(UsageTrackerService.hasUsageAccess(this) ? "Usage Access: فعال" : "Usage Access: غیرفعال");
        screenshotStateText = smallMuted(ScreenshotAccessibilityService.isEnabled(this) ? "Screenshot Service: فعال" : "Screenshot Service: غیرفعال");
        Button openUsage = primaryButton("باز کردن Usage Access");
        openUsage.setOnClickListener(v -> UsageTrackerService.openUsageAccessSettings(this));
        Button openShots = secondaryButton("باز کردن Screenshot Accessibility");
        openShots.setOnClickListener(v -> openScreenshotAccessibilityHelp());
        Button checkPerms = secondaryButton("بررسی وضعیت دسترسی‌ها");
        checkPerms.setOnClickListener(v -> { updatePermissionStatus(); Toast.makeText(this, "وضعیت دسترسی‌ها به‌روز شد.", Toast.LENGTH_SHORT).show(); });
        permission.addView(usageStateText);
        permission.addView(screenshotStateText);
        permission.addView(openUsage, new LinearLayout.LayoutParams(-1, dp(44)));
        permission.addView(openShots, new LinearLayout.LayoutParams(-1, dp(44)));
        permission.addView(checkPerms, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(permission);

        LinearLayout theme = cardBox();
        theme.addView(titleText("ظاهر برنامه"));
        TextView themeInfo = smallMuted(dark ? "تم فعلی: تیره" : "تم فعلی: روشن");
        Button toggle = secondaryButton(dark ? "تغییر به تم روشن" : "تغییر به تم تیره");
        toggle.setOnClickListener(v -> toggleTheme());
        theme.addView(themeInfo);
        theme.addView(toggle, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(theme);

        LinearLayout auto = cardBox();
        auto.addView(titleText("ثبت خودکار"));
        auto.addView(smallMuted("سرویس پس‌زمینه فعالیت برنامه‌ها را با UsageStats ثبت می‌کند."));
        Button service = primaryButton("راه‌اندازی دوباره Tracker");
        service.setOnClickListener(v -> { startTrackerService(); Toast.makeText(this, "Tracker راه‌اندازی شد.", Toast.LENGTH_SHORT).show(); });
        Button idle = secondaryButton("تنظیم زمان بیکاری");
        idle.setOnClickListener(v -> openIdleDialog());
        Button autoShot = secondaryButton("اسکرین‌شات خودکار: " + ("true".equals(db.getSetting("auto_screenshot", "true")) ? "روشن" : "خاموش"));
        autoShot.setOnClickListener(v -> toggleAutoScreenshot());
        auto.addView(service, new LinearLayout.LayoutParams(-1, dp(44)));
        auto.addView(idle, new LinearLayout.LayoutParams(-1, dp(44)));
        auto.addView(autoShot, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(auto);

        LinearLayout about = cardBox();
        about.addView(titleText("درباره"));
        about.addView(smallMuted("این نسخه به‌جای کپی رابط دسکتاپ، برای نمایش موبایل بازطراحی شده است. داده‌ها داخل SQLite محلی ذخیره می‌شوند."));
        page.addView(about);
        return page;
    }

    private ScrollView wrapPage(View body) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        sv.setBackgroundColor(bg);
        sv.addView(body);
        return sv;
    }

    private LinearLayout pageContainer() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(2), dp(16), dp(28));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return page;
    }

    private View sectionHeader(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(10));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(text);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView s = smallMuted(subtitle);
        box.addView(t);
        box.addView(s);
        return box;
    }

    private void showPage(int index) {
        pageIndex = index;
        dashboardPage.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        activityPage.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        reportsPage.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        styleBottomNav();
        refreshAll();
        if (index == 2) generateReport();
    }

    private void styleBottomNav() {
        styleBottom(navHome, pageIndex == 0);
        styleBottom(navActivity, pageIndex == 1);
        styleBottom(navReports, pageIndex == 2);
        styleBottom(navSettings, pageIndex == 3);
    }

    private void styleBottom(Button b, boolean selected) {
        b.setTextColor(selected ? accent : muted);
        rounded(b, selected ? (dark ? Color.parseColor("#12342F") : Color.parseColor("#DCFCE7")) : surface,
                selected ? accent : Color.TRANSPARENT, 14);
    }

    private void refreshAll() {
        if (db == null) return;
        rows.clear();
        rows.addAll(db.queryDay(currentDayMs));
        Collections.sort(rows, (a, b) -> Long.compare(a.startMs, b.startMs));
        updatePermissionStatus();
        refreshDashboard();
        if (pageIndex == 1) refreshActivity();
        if (pageIndex == 2) generateReport();
    }

    private void updatePermissionStatus() {
        boolean usageGranted = UsageTrackerService.hasUsageAccess(this);
        boolean screenshotGranted = ScreenshotAccessibilityService.isEnabled(this);
        if (usageStateText != null) {
            usageStateText.setText(usageGranted ? "Usage Access: فعال" : "Usage Access: غیرفعال");
            usageStateText.setTextColor(usageGranted ? green : red);
        }
        if (screenshotStateText != null) {
            screenshotStateText.setText(screenshotGranted ? "Screenshot Service: فعال" : "Screenshot Service: غیرفعال");
            screenshotStateText.setTextColor(screenshotGranted ? green : red);
        }
        if (statusChip != null) {
            statusChip.setText("نسخه شخصی");
            statusChip.setTextColor(accent);
            rounded(statusChip, dark ? Color.parseColor("#12342F") : Color.parseColor("#DCFCE7"), accent, 20);
        }
    }

    private void refreshDashboard() {
        if (todayTotalValue == null) return;
        long total = 0;
        Map<String, Long> totals = new HashMap<>();
        for (ActivitySegment r : rows) {
            total += r.durationSeconds;
            String app = value(r.appName, "Unknown");
            totals.put(app, totals.containsKey(app) ? totals.get(app) + r.durationSeconds : r.durationSeconds);
        }
        todayTotalValue.setText(TimeUtils.fmtHms(total));
        todayAppsValue.setText(String.valueOf(totals.size()));
        long monthStart = JalaliUtils.monthStartMillis(currentDayMs);
        monthTotalValue.setText(TimeUtils.fmtHms(db.totalBetweenDays(monthStart, currentDayMs)) + "  •  " + JalaliUtils.monthLabel(currentDayMs));
        updateTimelineZoomUi();
        timelineView.setData(currentDayMs, rows, dark);
        fillTopAppsList(dashboardTopApps, totals, total, 5);
    }

    private void refreshActivity() {
        if (activityList == null) return;
        activityDateTitle.setText(JalaliUtils.fullDate(currentDayMs));
        fillActivityList();
    }

    private void fillActivityList() {
        if (activityList == null) return;
        activityList.removeAllViews();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        String filter = filterSpinner == null || filterSpinner.getSelectedItem() == null ? "همه" : filterSpinner.getSelectedItem().toString();
        List<ActivitySegment> filtered = new ArrayList<>();
        for (ActivitySegment r : rows) {
            String hay = (value(r.title, "") + " " + value(r.appName, "") + " " + value(r.tag, "") + " " + value(r.category, "")).toLowerCase(Locale.US);
            if (!query.isEmpty() && !hay.contains(query)) continue;
            if ("تگ‌دار".equals(filter) && value(r.tag, "").isEmpty()) continue;
            if ("بدون تگ".equals(filter) && !value(r.tag, "").isEmpty()) continue;
            if ("برنامه‌ها".equals(filter) && value(r.category, "").toLowerCase(Locale.US).contains("document")) continue;
            if ("سندها".equals(filter) && !value(r.category, "").toLowerCase(Locale.US).contains("document")) continue;
            filtered.add(r);
        }
        Collections.sort(filtered, (a, b) -> Long.compare(b.startMs, a.startMs));
        activityCountLabel.setText(filtered.size() + " مورد از " + rows.size() + " فعالیت");
        if (filtered.isEmpty()) {
            activityList.addView(emptyState("هنوز داده‌ای ثبت نشده", "Usage Access را فعال کن و چند دقیقه با گوشی کار کن تا فعالیت‌ها اینجا بیایند."));
            return;
        }
        for (ActivitySegment r : filtered) activityList.addView(activityCard(r));
    }

    private View activityCard(ActivitySegment r) {
        LinearLayout box = cardBox();
        int color = AppUtil.parseColor(r.color, accent);
        boolean selected = selectedIds.contains(r.id);
        if (selected) rounded(box, dark ? Color.parseColor("#12342F") : Color.parseColor("#ECFDF5"), accent, 16);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = dot(color);
        TextView name = titleText(value(r.appName, "Unknown"));
        TextView duration = chip(TimeUtils.fmtHms(r.durationSeconds), green, dark ? Color.parseColor("#073C34") : Color.parseColor("#DCFCE7"));
        top.addView(dot, new LinearLayout.LayoutParams(dp(22), -2));
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (r.live) top.addView(chip("زنده", accent, dark ? Color.parseColor("#12342F") : Color.parseColor("#DCFCE7")), new LinearLayout.LayoutParams(-2, dp(34)));
        top.addView(duration, new LinearLayout.LayoutParams(-2, dp(34)));
        box.addView(top);

        TextView detail = smallMuted(TimeUtils.hhmm(r.startMs) + " تا " + (r.live ? "اکنون" : TimeUtils.hhmm(r.endMs)) + "  •  " + value(r.category, "Other"));
        box.addView(detail);
        View shot = screenshotPreview(r.screenshotPath);
        if (shot != null) box.addView(shot);
        String title = value(r.title, "");
        if (!title.isEmpty() && !title.equals(r.appName)) box.addView(smallMuted(title));
        String tag = value(r.tag, "");
        if (!tag.isEmpty()) box.addView(chip("# " + tag, blue, dark ? Color.parseColor("#11283F") : Color.parseColor("#DBEAFE")));

        box.setOnClickListener(v -> {
            if (r.id > 0) {
                if (selectedIds.contains(r.id)) selectedIds.remove(r.id); else selectedIds.add(r.id);
                fillActivityList();
            }
        });
        box.setOnLongClickListener(v -> { showSegmentDialog(r); return true; });
        return box;
    }

    private void fillTopAppsList(LinearLayout target, Map<String, Long> totals, long total, int max) {
        if (target == null) return;
        target.removeAllViews();
        if (totals.isEmpty()) {
            target.addView(emptyState("هنوز فعالیتی نیست", "بعد از فعال شدن مجوز، برنامه‌های پرمصرف اینجا نمایش داده می‌شوند."));
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(max, entries.size()); i++) target.addView(topAppItem(i + 1, entries.get(i).getKey(), entries.get(i).getValue(), total));
    }

    private View topAppItem(int index, String app, long seconds, long total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        rounded(row, cardAlt, Color.TRANSPARENT, 14);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView rank = chip(String.valueOf(index), accent, dark ? Color.parseColor("#12342F") : Color.parseColor("#DCFCE7"));
        TextView name = label(app);
        TextView dur = smallMuted(TimeUtils.fmtHms(seconds));
        head.addView(rank, new LinearLayout.LayoutParams(dp(34), dp(30)));
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(dur);
        row.addView(head);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(total == 0 ? 0 : (int) ((seconds * 100L) / total));
        row.addView(bar, new LinearLayout.LayoutParams(-1, dp(18)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        row.setLayoutParams(lp);
        return row;
    }

    private void zoomTimeline(int direction) {
        int next = timelineZoomIndex + direction;
        if (next < 0) next = 0;
        if (next >= timelineZoomHours.length) next = timelineZoomHours.length - 1;
        timelineZoomIndex = next;
        updateTimelineZoomUi();
        if (timelineView != null) timelineView.invalidate();
    }

    private void updateTimelineZoomUi() {
        int hours = timelineZoomHours[Math.max(0, Math.min(timelineZoomIndex, timelineZoomHours.length - 1))];
        if (timelineZoomLabel != null) timelineZoomLabel.setText(hours == 24 ? "24h" : hours + "h");
        if (timelineView != null) timelineView.setZoomHours(hours);
    }

    private void setCurrentMonthReport() {
        long today = TimeUtils.dayStart(System.currentTimeMillis());
        if (fromInput != null) fromInput.setText(JalaliUtils.dateString(JalaliUtils.monthStartMillis(today)));
        if (toInput != null) toInput.setText(JalaliUtils.dateString(today));
    }

    private void generateReport() {
        if (fromInput == null || toInput == null) return;
        try {
            reportFromMs = JalaliUtils.parseDateToMillis(fromInput.getText().toString());
            reportToMs = JalaliUtils.parseDateToMillis(toInput.getText().toString());
            if (reportToMs < reportFromMs) throw new IllegalArgumentException("تاریخ پایان نباید قبل از تاریخ شروع باشد.");
            currentReport = db.buildRangeReport(db.queryBetweenDays(reportFromMs, reportToMs));
            fillReports();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void fillReports() {
        if (reportTopList == null) return;
        reportTotalValue.setText(TimeUtils.fmtHms(currentReport.totalSeconds));
        reportDaysValue.setText(String.valueOf(currentReport.dailyRows.size()));
        reportTopValue.setText(currentReport.topActivities.isEmpty() ? "-" : currentReport.topActivities.get(0).app + "  •  " + TimeUtils.fmtHms(currentReport.topActivities.get(0).seconds));

        reportTopList.removeAllViews();
        if (currentReport.topActivities.isEmpty()) reportTopList.addView(emptyState("داده‌ای برای گزارش نیست", "بازه دیگری انتخاب کن یا اول فعالیت‌ها را ثبت کن."));
        int i = 1;
        for (ReportData.TopItem t : currentReport.topActivities) {
            reportTopList.addView(reportRow(i++, t.app, t.category + "  •  " + t.percent + "%", TimeUtils.fmtHms(t.seconds), AppUtil.parseColor(AppUtil.colorFor(t.app), accent)));
        }

        reportDailyList.removeAllViews();
        if (currentReport.dailyRows.isEmpty()) reportDailyList.addView(emptyState("روز فعالی وجود ندارد", "برای این بازه هنوز اطلاعاتی ثبت نشده است."));
        i = 1;
        for (ReportData.DailyItem d : currentReport.dailyRows) {
            reportDailyList.addView(reportRow(i++, d.jalaliDate + "  •  " + d.weekday, d.start + " تا " + d.end + "  •  " + value(d.topApp, "-") , TimeUtils.fmtHms(d.total), blue));
        }
    }

    private View reportRow(int index, String title, String sub, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        rounded(row, cardAlt, Color.TRANSPARENT, 14);
        TextView num = chip(String.valueOf(index), color, Color.TRANSPARENT);
        TextView middle = new TextView(this);
        middle.setText(title + "\n" + sub);
        middle.setTextColor(text);
        middle.setTextSize(13);
        middle.setGravity(Gravity.RIGHT);
        TextView val = chip(value, green, dark ? Color.parseColor("#073C34") : Color.parseColor("#DCFCE7"));
        row.addView(num, new LinearLayout.LayoutParams(dp(34), dp(32)));
        row.addView(middle, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(val, new LinearLayout.LayoutParams(-2, dp(34)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(lp);
        return row;
    }

    private void exportPdf() {
        generateReport();
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            String name = "timesheet_" + JalaliUtils.dateString(reportFromMs).replace('/', '-') + "_to_" + JalaliUtils.dateString(reportToMs).replace('/', '-') + ".pdf";
            File file = new File(dir, name);
            writeReportPdf(file, currentReport);
            Toast.makeText(this, "PDF ساخته شد:\n" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "خطا در ساخت PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeReportPdf(File file, ReportData report) throws Exception {
        PdfDocument pdf = new PdfDocument();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        int pageW = 842, pageH = 595;
        int pageNum = 1;
        PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create());
        Canvas c = page.getCanvas();
        c.drawColor(Color.WHITE);
        p.setColor(Color.parseColor("#0F172A"));
        p.setTextSize(18); p.setFakeBoldText(true); p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("گزارش ساعت کاری", pageW - 36, 42, p);
        p.setTextSize(10); p.setFakeBoldText(false);
        c.drawText("بازه: " + JalaliUtils.dateString(reportFromMs) + " تا " + JalaliUtils.dateString(reportToMs), pageW - 36, 62, p);
        c.drawText("جمع کل: " + TimeUtils.fmtHms(report.totalSeconds), pageW - 36, 80, p);
        int y = 118;
        p.setTextSize(13); p.setFakeBoldText(true);
        c.drawText("۵ برنامه با بیشترین زمان", pageW - 36, y, p);
        y += 22;
        p.setTextSize(10); p.setFakeBoldText(false);
        int idx = 1;
        for (ReportData.TopItem t : report.topActivities) {
            c.drawText(idx++ + ". " + t.app + " | " + t.category + " | " + TimeUtils.fmtHms(t.seconds) + " | " + t.percent + "%", pageW - 36, y, p);
            y += 18;
        }
        if (report.topActivities.isEmpty()) { c.drawText("فعالیتی ثبت نشده است.", pageW - 36, y, p); y += 18; }
        y += 16;
        p.setTextSize(13); p.setFakeBoldText(true);
        c.drawText("گزارش روزانه", pageW - 36, y, p);
        y += 22;
        p.setTextSize(9); p.setFakeBoldText(false);
        idx = 1;
        for (ReportData.DailyItem d : report.dailyRows) {
            if (y > pageH - 40) {
                pdf.finishPage(page);
                page = pdf.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, ++pageNum).create());
                c = page.getCanvas(); c.drawColor(Color.WHITE); y = 40;
            }
            c.drawText(idx++ + ". " + d.jalaliDate + " | " + d.weekday + " | " + d.start + " تا " + d.end + " | " + TimeUtils.fmtHms(d.total) + " | " + d.topApp, pageW - 36, y, p);
            y += 16;
        }
        if (report.dailyRows.isEmpty()) c.drawText("اطلاعاتی وجود ندارد.", pageW - 36, y, p);
        pdf.finishPage(page);
        try (FileOutputStream out = new FileOutputStream(file)) { pdf.writeTo(out); }
        pdf.close();
    }

    private void openDateDialog() {
        final EditText input = editText("1405/04/01");
        input.setText(JalaliUtils.dateString(currentDayMs));
        new AlertDialog.Builder(this)
                .setTitle("انتخاب تاریخ شمسی")
                .setView(input)
                .setPositiveButton("نمایش", (d, w) -> {
                    try { currentDayMs = JalaliUtils.parseDateToMillis(input.getText().toString()); refreshAll(); }
                    catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void openIdleDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), 0);
        final EditText idle = editText("10");
        idle.setInputType(InputType.TYPE_CLASS_NUMBER);
        long minutes = Long.parseLong(db.getSetting("idle_limit_seconds", String.valueOf(TimeUtils.DEFAULT_IDLE_LIMIT_MS / 1000))) / 60L;
        idle.setText(String.valueOf(minutes));
        box.addView(label("بعد از چند دقیقه بیکاری ثبت متوقف شود؟"));
        box.addView(idle, new LinearLayout.LayoutParams(-1, dp(46)));
        new AlertDialog.Builder(this)
                .setTitle("زمان بیکاری")
                .setView(box)
                .setPositiveButton("ذخیره", (d, w) -> {
                    try {
                        long m = Math.max(1, Math.min(180, Long.parseLong(idle.getText().toString())));
                        db.setSetting("idle_limit_seconds", String.valueOf(m * 60L));
                        startTrackerService();
                        Toast.makeText(this, "ذخیره شد.", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(this, "عدد معتبر وارد کن.", Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void addTagToSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "اول یک یا چند فعالیت را انتخاب کن.", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText tagInput = editText("نام پروژه / تگ");
        new AlertDialog.Builder(this)
                .setTitle("افزودن تگ")
                .setView(tagInput)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String tag = tagInput.getText().toString().trim();
                    if (!tag.isEmpty()) {
                        db.updateTag(new ArrayList<>(selectedIds), tag);
                        selectedIds.clear();
                        refreshAll();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showSegmentDialog(ActivitySegment r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(4));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        String msg = "برنامه: " + value(r.appName, "Unknown") + "\n" +
                "پکیج: " + value(r.packageName, "") + "\n" +
                "دسته: " + value(r.category, "Other") + "\n" +
                "شروع: " + TimeUtils.hhmmss(r.startMs) + "\n" +
                "پایان: " + TimeUtils.hhmmss(r.endMs) + "\n" +
                "مدت: " + TimeUtils.fmtHms(r.durationSeconds) + "\n" +
                "تگ: " + value(r.tag, "-");
        box.addView(label(msg));
        View shot = screenshotPreview(r.screenshotPath);
        if (shot != null) box.addView(shot);
        else box.addView(smallMuted("برای نمایش اسکرین‌شات، Screenshot Accessibility را فعال کن. بعضی صفحه‌های امن مثل بانک‌ها اجازه تصویر نمی‌دهند."));

        new AlertDialog.Builder(this).setTitle("جزئیات فعالیت").setView(box).setPositiveButton("بستن", null).show();
    }

    private View screenshotPreview(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File file = new File(path);
        if (!file.exists()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) return null;
            ImageView img = new ImageView(this);
            img.setImageBitmap(bmp);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            rounded(img, cardAlt, border, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(170));
            lp.setMargins(0, dp(8), 0, dp(6));
            img.setLayoutParams(lp);
            img.setOnClickListener(v -> showScreenshotDialog(path));
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    private void showScreenshotDialog(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return;
            ImageView img = new ImageView(this);
            img.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(dp(4), dp(4), dp(4), dp(4));
            new AlertDialog.Builder(this)
                    .setTitle("اسکرین‌شات فعالیت")
                    .setView(img)
                    .setPositiveButton("بستن", null)
                    .show();
        } catch (Exception ignored) { }
    }

    private void openScreenshotAccessibilityHelp() {
        new AlertDialog.Builder(this)
                .setTitle("فعال‌سازی Screenshot Accessibility")
                .setMessage("برای اینکه برنامه از هر فعالیت جدید خودش اسکرین‌شات بگیرد، در صفحه Accessibility سرویس WorkTimeTracker Screenshot را روشن کن. اگر Android دسترسی را به خاطر Restricted Settings رد کرد، از App info برنامه گزینه Allow restricted settings را فعال کن و دوباره برگرد.")
                .setPositiveButton("باز کردن Accessibility", (d, w) -> ScreenshotAccessibilityService.openAccessibilitySettings(this))
                .setNegativeButton("بعداً", null)
                .show();
    }

    private void toggleAutoScreenshot() {
        boolean enabled = "true".equals(db.getSetting("auto_screenshot", "true"));
        db.setSetting("auto_screenshot", enabled ? "false" : "true");
        Toast.makeText(this, enabled ? "اسکرین‌شات خودکار خاموش شد." : "اسکرین‌شات خودکار روشن شد.", Toast.LENGTH_SHORT).show();
        buildUi();
        refreshAll();
    }

    private void showUsageDialog() {
        new AlertDialog.Builder(this)
                .setTitle("فعال‌سازی Usage Access")
                .setMessage("برای ثبت زمان استفاده از برنامه‌ها باید Usage Access فعال باشد. برای ثبت اسکرین‌شات خودکار هم از تنظیمات برنامه، Screenshot Accessibility را فعال کن. اگر Android دسترسی را به خاطر Restricted Settings رد کرد، از صفحه App info گزینه Allow restricted settings را فعال کن.")
                .setPositiveButton("باز کردن تنظیمات", (d, w) -> UsageTrackerService.openUsageAccessSettings(this))
                .setNegativeButton("بعداً", null)
                .show();
    }

    private void toggleTheme() {
        dark = !dark;
        db.setSetting("theme", dark ? "dark" : "light");
        applyPalette();
        buildUi();
        refreshAll();
    }

    private TextView statCard(LinearLayout parent, String title, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        rounded(box, surface, border, 16);
        TextView t = smallMuted(title);
        TextView v = bigText(value, color);
        box.addView(t);
        box.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(92), 1);
        lp.setMargins(dp(4), dp(4), dp(4), dp(8));
        parent.addView(box, lp);
        return v;
    }

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        rounded(box, surface, border, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        box.setLayoutParams(lp);
        return box;
    }

    private View emptyState(String title, String message) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(22), dp(16), dp(22));
        rounded(box, cardAlt, Color.TRANSPARENT, 16);
        TextView t = titleText(title);
        t.setGravity(Gravity.CENTER);
        TextView m = smallMuted(message);
        m.setGravity(Gravity.CENTER);
        box.addView(t);
        box.addView(m);
        return box;
    }

    private Button bottomButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(12);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private Button primaryButton(String label) {
        Button b = baseButton(label);
        b.setTextColor(Color.WHITE);
        rounded(b, accent, accent, 14);
        return b;
    }

    private Button accentButton(String label) {
        Button b = baseButton(label);
        b.setTextColor(Color.WHITE);
        rounded(b, orange, orange, 14);
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = baseButton(label);
        b.setTextColor(text);
        rounded(b, cardAlt, border, 14);
        return b;
    }

    private Button baseButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.setMargins(dp(4), dp(5), dp(4), dp(5));
        b.setLayoutParams(lp);
        return b;
    }

    private EditText editText(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(text);
        e.setHintTextColor(muted);
        e.setSingleLine(true);
        e.setTextSize(13);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        rounded(e, cardAlt, border, 14);
        return e;
    }

    private TextView titleText(String s) {
        TextView v = label(s);
        v.setTextSize(16);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView bigText(String s, int color) {
        TextView v = label(s);
        v.setTextSize(22);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView smallMuted(String s) {
        TextView v = label(s);
        v.setTextColor(muted);
        v.setTextSize(12);
        v.setLineSpacing(dp(2), 1.0f);
        return v;
    }

    private TextView label(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(text);
        v.setTextSize(13);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView chip(String s, int fg, int fill) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(12);
        v.setTextColor(fg);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), 0, dp(10), 0);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        rounded(v, fill, Color.TRANSPARENT, 18);
        return v;
    }

    private TextView dot(int color) {
        TextView d = new TextView(this);
        d.setText("●");
        d.setTextColor(color);
        d.setTextSize(18);
        d.setGravity(Gravity.CENTER);
        return d;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private void rounded(View v, int fill, int stroke, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) gd.setStroke(dp(1), stroke);
        v.setBackground(gd);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String value(String s, String def) {
        return s == null || s.isEmpty() ? def : s;
    }

    public static class MiniTimelineView extends View {
        private final List<ActivitySegment> data = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long day;
        private boolean dark;
        private int visibleHours = 24;

        public MiniTimelineView(Context context) { super(context); setMinimumHeight(140); }

        void setData(long dayMs, List<ActivitySegment> rows, boolean isDark) {
            day = TimeUtils.dayStart(dayMs);
            dark = isDark;
            data.clear();
            data.addAll(rows);
            invalidate();
        }

        void setZoomHours(int hours) {
            visibleHours = Math.max(3, Math.min(24, hours));
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int panel = Color.parseColor(dark ? "#10181C" : "#F8FAFC");
            int grid = Color.parseColor(dark ? "#2E3A40" : "#D4DEE8");
            int text = Color.parseColor(dark ? "#DCE3E5" : "#0F172A");
            int muted = Color.parseColor(dark ? "#94A3A9" : "#64748B");
            p.setColor(panel);
            c.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), dpLocal(14), dpLocal(14), p);
            int left = dpLocal(12), right = getWidth() - dpLocal(12), top = dpLocal(38), bottom = getHeight() - dpLocal(30);
            long dayEnd = TimeUtils.nextDayStart(day);
            long now = System.currentTimeMillis();
            long viewStart = day;
            long viewEnd = dayEnd;
            if (visibleHours < 24) {
                viewEnd = now >= day && now <= dayEnd ? now : dayEnd;
                viewStart = Math.max(day, viewEnd - visibleHours * 60L * 60L * 1000L);
                if (viewEnd <= viewStart) viewEnd = Math.min(dayEnd, viewStart + visibleHours * 60L * 60L * 1000L);
            }

            p.setColor(text); p.setTextSize(dpLocal(12)); p.setFakeBoldText(true); p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(visibleHours == 24 ? "۲۴ ساعت امروز" : "آخرین " + visibleHours + " ساعت", getWidth() - dpLocal(16), dpLocal(24), p);
            p.setFakeBoldText(false);
            p.setColor(grid); p.setStrokeWidth(1);
            for (int i = 0; i <= 4; i++) {
                float x = left + (right - left) * (i / 4f);
                long tick = viewStart + (long) ((viewEnd - viewStart) * (i / 4f));
                c.drawLine(x, top, x, bottom, p);
                p.setColor(muted); p.setTextSize(dpLocal(10)); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(TimeUtils.hhmm(tick), x, bottom + dpLocal(17), p);
                p.setColor(grid);
            }
            int lane = Math.max(dpLocal(18), (bottom - top) / 3);
            int idx = 0;
            long span = Math.max(1L, viewEnd - viewStart);
            for (ActivitySegment r : data) {
                long segStart = Math.max(r.startMs, viewStart);
                long segEnd = Math.min(r.live ? now : r.endMs, viewEnd);
                if (segEnd <= segStart) continue;
                if (idx >= 18) break;
                float x1 = left + (right - left) * ((segStart - viewStart) / (float) span);
                float x2 = left + (right - left) * ((segEnd - viewStart) / (float) span);
                if (x2 < x1 + dpLocal(4)) x2 = x1 + dpLocal(4);
                int y = top + (idx % 3) * lane + dpLocal(3);
                p.setColor(AppUtil.parseColor(r.color, Color.parseColor("#00C896")));
                c.drawRoundRect(new RectF(x1, y, x2, y + lane - dpLocal(7)), dpLocal(5), dpLocal(5), p);
                idx++;
            }
            if (data.isEmpty()) {
                p.setColor(muted); p.setTextSize(dpLocal(12)); p.setTextAlign(Paint.Align.CENTER);
                c.drawText("هنوز فعالیتی ثبت نشده", getWidth() / 2f, getHeight() / 2f, p);
            }
        }

        private int dpLocal(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    }
}
