package com.qingping.clearscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private StatsDb db;
    private Prefs prefs;

    private TextView statusPill;
    private TextView heroLabel;
    private TextView heroCount;
    private TextView heroSub;
    private TextView heroBadge;
    private TextView statSplash;
    private TextView statJump;
    private TextView statApps;
    private TextView statAppsLabel;
    private TextView listHint;
    private TextView btnProtect;
    private TextView filterToday;
    private TextView filterAll;
    private TextView emptyTest;
    private Switch switchSplash;
    private Switch switchJump;
    private LinearLayout listContainer;
    private LinearLayout emptyState;

    private boolean showToday = true;
    private boolean guardStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new StatsDb(this);
        prefs = new Prefs(this);

        statusPill = findViewById(R.id.statusPill);
        heroLabel = findViewById(R.id.heroLabel);
        heroCount = findViewById(R.id.heroCount);
        heroSub = findViewById(R.id.heroSub);
        heroBadge = findViewById(R.id.heroBadge);
        statSplash = findViewById(R.id.statSplash);
        statJump = findViewById(R.id.statJump);
        statApps = findViewById(R.id.statApps);
        statAppsLabel = findViewById(R.id.statAppsLabel);
        listHint = findViewById(R.id.listHint);
        btnProtect = findViewById(R.id.btnProtect);
        filterToday = findViewById(R.id.filterToday);
        filterAll = findViewById(R.id.filterAll);
        emptyTest = findViewById(R.id.emptyTest);
        switchSplash = findViewById(R.id.switchSplash);
        switchJump = findViewById(R.id.switchJump);
        listContainer = findViewById(R.id.listContainer);
        emptyState = findViewById(R.id.emptyState);

        switchSplash.setChecked(prefs.isSplashEnabled());
        switchJump.setChecked(prefs.isJumpEnabled());
        switchSplash.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setSplashEnabled(isChecked);
            Toast.makeText(this,
                    isChecked ? "已开启开屏广告自动跳过" : "已暂停开屏广告跳过",
                    Toast.LENGTH_SHORT).show();
        });
        switchJump.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setJumpEnabled(isChecked);
            Toast.makeText(this,
                    isChecked ? "已开启误触跳转保护" : "已暂停误触跳转保护",
                    Toast.LENGTH_SHORT).show();
        });

        btnProtect.setOnClickListener(v -> {
            if (PermissionUtils.isAccessibilityEnabled(this)) {
                openGuide();
            } else {
                Toast.makeText(this, "请在列表中找到“清屏”并开启", Toast.LENGTH_LONG).show();
                PermissionUtils.openAccessibilitySettings(this);
            }
        });

        filterToday.setOnClickListener(v -> selectFilter(true));
        filterAll.setOnClickListener(v -> selectFilter(false));
        emptyTest.setOnClickListener(v -> startTest());
        findViewById(R.id.cardGuide).setOnClickListener(v -> openGuide());

        selectFilter(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshStats();
        startGuardService();
    }

    private void startGuardService() {
        if (guardStarted) {
            return;
        }
        guardStarted = true;
        try {
            startForegroundService(new Intent(this, GuardService.class));
        } catch (RuntimeException ignored) {
            // Accessibility interception does not depend on the foreground
            // service; the system still owns the accessibility binding.
        }
    }

    private void refreshStatus() {
        boolean enabled = PermissionUtils.isAccessibilityEnabled(this);
        if (enabled) {
            statusPill.setText("防护中");
            statusPill.setBackgroundResource(R.drawable.bg_pill_on);
            statusPill.setTextColor(getColor(R.color.accent));
            btnProtect.setText("防护已开启 · 管理权限");
            btnProtect.setBackgroundResource(R.drawable.bg_button_ghost);
            btnProtect.setTextColor(getColor(R.color.accent));
            heroBadge.setText("防护生效中");
            heroBadge.setTextColor(getColor(R.color.accent));
        } else {
            statusPill.setText("未开启");
            statusPill.setBackgroundResource(R.drawable.bg_pill_off);
            statusPill.setTextColor(getColor(R.color.danger));
            btnProtect.setText("开启无障碍防护");
            btnProtect.setBackgroundResource(R.drawable.bg_button_primary);
            btnProtect.setTextColor(0xFF06231B);
            heroBadge.setText("防护未开启");
            heroBadge.setTextColor(getColor(R.color.warn));
        }
    }

    private void refreshStats() {
        long todayStart = StatsDb.todayStart();
        StatsDb.Summary today = db.summary(todayStart);
        StatsDb.Summary all = db.summary(0L);
        long since = showToday ? todayStart : 0L;
        StatsDb.Summary shown = showToday ? today : all;

        heroLabel.setText(showToday ? "今日已拦截" : "累计已拦截");
        heroCount.setText(String.valueOf(shown.total));
        if (all.total == 0) {
            heroSub.setText("开启后，打开带开屏广告的应用试试");
        } else {
            heroSub.setText("累计 " + all.total + " 次 · 覆盖 " + all.apps + " 个应用");
        }
        statSplash.setText(String.valueOf(shown.splash));
        statJump.setText(String.valueOf(shown.jump));
        statApps.setText(String.valueOf(shown.apps));
        statAppsLabel.setText(showToday ? "今日应用" : "累计应用");

        String hint = (showToday ? "今天" : "全部") + " · " + shown.apps + " 个应用";
        if (shown.total > 0) {
            hint += " · " + shown.total + " 次";
        } else {
            hint += " · 暂无记录";
        }
        listHint.setText(hint);

        List<StatsDb.AppStat> apps = db.perApp(since, 40);
        listContainer.removeAllViews();
        if (apps.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            listContainer.setVisibility(View.GONE);
            return;
        }

        emptyState.setVisibility(View.GONE);
        listContainer.setVisibility(View.VISIBLE);

        int max = 1;
        for (StatsDb.AppStat stat : apps) {
            if (stat.count > max) {
                max = stat.count;
            }
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (StatsDb.AppStat stat : apps) {
            View row = inflater.inflate(R.layout.item_app_stat, listContainer, false);
            bindRow(row, stat, max);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            row.setLayoutParams(params);
            listContainer.addView(row);
        }
    }

    private void bindRow(View row, StatsDb.AppStat stat, int max) {
        ImageView icon = row.findViewById(R.id.appIcon);
        TextView name = row.findViewById(R.id.appName);
        TextView packageName = row.findViewById(R.id.appPackage);
        TextView count = row.findViewById(R.id.appCount);
        TextView last = row.findViewById(R.id.appLast);
        ProgressBar progress = row.findViewById(R.id.appProgress);

        String label = stat.label;
        if (label == null || label.isEmpty()) {
            label = stat.packageName;
        }
        name.setText(label);
        packageName.setText(stat.packageName);
        count.setText(stat.count + " 次");
        last.setText("最近拦截：" + formatTime(stat.lastTs));

        try {
            Drawable appIcon = getPackageManager().getApplicationIcon(stat.packageName);
            icon.setImageDrawable(appIcon);
        } catch (Exception ignored) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        progress.setMax(Math.max(1, max));
        progress.setProgress(Math.min(stat.count, max));
    }

    private void selectFilter(boolean today) {
        showToday = today;
        if (today) {
            filterToday.setBackgroundResource(R.drawable.bg_chip_selected);
            filterToday.setTextColor(getColor(R.color.accent));
            filterToday.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            filterAll.setBackgroundResource(R.drawable.bg_chip);
            filterAll.setTextColor(getColor(R.color.text_sub));
            filterAll.setTypeface(Typeface.DEFAULT);
        } else {
            filterAll.setBackgroundResource(R.drawable.bg_chip_selected);
            filterAll.setTextColor(getColor(R.color.accent));
            filterAll.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            filterToday.setBackgroundResource(R.drawable.bg_chip);
            filterToday.setTextColor(getColor(R.color.text_sub));
            filterToday.setTypeface(Typeface.DEFAULT);
        }
        refreshStats();
    }

    private void startTest() {
        if (!PermissionUtils.isAccessibilityEnabled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("先开启无障碍服务")
                    .setMessage("拦截测试需要无障碍服务才能自动点击“跳过”。开启后再回来运行测试。")
                    .setPositiveButton("去开启", (dialog, which) ->
                            PermissionUtils.openAccessibilitySettings(this))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startActivity(new Intent(this, TestAdActivity.class));
    }

    private void openGuide() {
        startActivity(new Intent(this, GuideActivity.class));
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0) {
            return "--";
        }
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);
        Calendar now = Calendar.getInstance();

        String clock = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(timestamp));
        if (sameDay(target, now)) {
            return "今天 " + clock;
        }

        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, yesterday)) {
            return "昨天 " + clock;
        }
        return (target.get(Calendar.MONTH) + 1) + "月"
                + target.get(Calendar.DAY_OF_MONTH) + "日 " + clock;
    }

    private boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }
}
