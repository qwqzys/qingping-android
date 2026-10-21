package com.qingping.clearscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * A low-priority foreground service. It does not perform interception itself;
 * it keeps the process warm and makes the enabled state visible to the user.
 */
public class GuardService extends Service {

    private static final String CHANNEL_ID = "qingping_guard";
    private static final int NOTIFICATION_ID = 4101;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startGuardNotification();
        return START_STICKY;
    }

    private void startGuardNotification() {
        boolean enabled = PermissionUtils.isAccessibilityEnabled(this);
        String text;
        if (!enabled) {
            text = "无障碍服务未开启，点击完成设置";
        } else {
            StatsDb db = new StatsDb(this);
            StatsDb.Summary today = db.summary(StatsDb.todayStart());
            db.close();
            text = today.total > 0
                    ? "防护中 · 今日已拦截 " + today.total + " 次"
                    : "防护中 · 等待第一条拦截记录";
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setColor(0xFF55E6B8)
                .setContentTitle("清屏")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE);

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (RuntimeException ignored) {
            // If the platform refuses the foreground service, the accessibility
            // service can still work; never crash the UI process over this.
            stopSelf();
        }
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "防护状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示清屏是否正在保护本机");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
