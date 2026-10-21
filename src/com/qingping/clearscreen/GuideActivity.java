package com.qingping.clearscreen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class GuideActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 2001;

    private TextView guideStatus;
    private TextView guideStatusSub;
    private TextView accStatus;
    private TextView notifStatus;
    private TextView batteryStatus;
    private TextView btnAcc;
    private TextView btnNotif;
    private TextView btnBattery;
    private TextView btnAuto;
    private TextView btnTest;
    private Switch switchRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        guideStatus = findViewById(R.id.guideStatus);
        guideStatusSub = findViewById(R.id.guideStatusSub);
        accStatus = findViewById(R.id.accStatus);
        notifStatus = findViewById(R.id.notifStatus);
        batteryStatus = findViewById(R.id.batteryStatus);
        btnAcc = findViewById(R.id.btnAcc);
        btnNotif = findViewById(R.id.btnNotif);
        btnBattery = findViewById(R.id.btnBattery);
        btnAuto = findViewById(R.id.btnAuto);
        btnTest = findViewById(R.id.btnTest);
        TextView btnClear = findViewById(R.id.btnClear);
        switchRoot = findViewById(R.id.switchRoot);
        Prefs prefs = new Prefs(this);
        switchRoot.setChecked(prefs.isRootTapEnabled());
        switchRoot.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.setRootTapEnabled(checked);
            Toast.makeText(this,
                    checked ? "已开启 Root 点击兜底，首次点击会请求 Root 授权"
                            : "已关闭 Root 点击兜底",
                    Toast.LENGTH_LONG).show();
        });

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> confirmClearStats());
        btnAcc.setOnClickListener(v -> PermissionUtils.openAccessibilitySettings(this));
        btnNotif.setOnClickListener(v -> handleNotificationPermission());
        btnBattery.setOnClickListener(v ->
                PermissionUtils.requestIgnoreBatteryOptimizations(this));
        btnAuto.setOnClickListener(v -> PermissionUtils.openAppDetails(this));
        btnTest.setOnClickListener(v -> startTest());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean accessibility = PermissionUtils.isAccessibilityEnabled(this);
        if (accessibility) {
            guideStatus.setText("防护已开启");
            guideStatusSub.setText("开屏跳过与误触返回正在生效");
            guideStatus.setTextColor(getColor(R.color.accent));
            accStatus.setText("已开启 · 正在保护");
            accStatus.setTextColor(getColor(R.color.accent));
            btnAcc.setText("管理");
            btnAcc.setBackgroundResource(R.drawable.bg_button_ghost);
            btnAcc.setTextColor(getColor(R.color.accent));
        } else {
            guideStatus.setText("防护未开启");
            guideStatusSub.setText("开启无障碍服务后即可自动拦截");
            guideStatus.setTextColor(getColor(R.color.text));
            accStatus.setText("未开启 · 必需");
            accStatus.setTextColor(getColor(R.color.danger));
            btnAcc.setText("去开启");
            btnAcc.setBackgroundResource(R.drawable.bg_button_primary);
            btnAcc.setTextColor(0xFF06231B);
        }

        if (Build.VERSION.SDK_INT < 33) {
            notifStatus.setText("当前系统无需单独授权");
            notifStatus.setTextColor(getColor(R.color.text_dim));
        } else if (PermissionUtils.notificationsEnabled(this)) {
            notifStatus.setText("已授权 · 显示常驻通知");
            notifStatus.setTextColor(getColor(R.color.accent));
        } else {
            notifStatus.setText("未授权 · 不影响拦截");
            notifStatus.setTextColor(getColor(R.color.text_dim));
        }

        if (PermissionUtils.isIgnoringBatteryOptimizations(this)) {
            batteryStatus.setText("已加入白名单");
            batteryStatus.setTextColor(getColor(R.color.accent));
        } else {
            batteryStatus.setText("可能被系统限制后台");
            batteryStatus.setTextColor(getColor(R.color.warn));
        }
    }

    private void handleNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            PermissionUtils.openNotificationSettings(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            refreshStatus();
        }
    }

    private void confirmClearStats() {
        new AlertDialog.Builder(this)
                .setTitle("清除全部统计？")
                .setMessage("会删除本机保存的所有拦截次数，不影响无障碍和防护开关。")
                .setPositiveButton("清除", (dialog, which) -> {
                    StatsDb db = new StatsDb(this);
                    db.clearAll();
                    db.close();
                    Toast.makeText(this, "统计已清除", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                })
                .setNegativeButton("取消", null)
                .show();
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
}
