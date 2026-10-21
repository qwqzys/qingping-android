package com.qingping.clearscreen;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/** Permission helpers shared by the dashboard, guide and guard service. */
public final class PermissionUtils {

    private PermissionUtils() {
    }

    public static boolean isAccessibilityEnabled(Context context) {
        String packageName = context.getPackageName();
        String serviceName = ClearScreenService.class.getName();
        String flat = packageName + "/" + serviceName;
        String shortFlat = packageName + "/.ClearScreenService";

        try {
            AccessibilityManager manager =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null) {
                List<AccessibilityServiceInfo> services =
                        manager.getEnabledAccessibilityServiceList(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (services != null) {
                    for (AccessibilityServiceInfo info : services) {
                        String id = info.getId();
                        if (id == null) {
                            continue;
                        }
                        if (id.equals(flat) || id.equals(flat.replace('/', ':'))) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null
                    && (enabled.contains(flat)
                    || enabled.contains(shortFlat)
                    || enabled.contains(flat.replace('/', ':')));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            addNewTaskIfNeeded(context, intent);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    public static void openAppDetails(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            addNewTaskIfNeeded(context, intent);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
            openAccessibilitySettings(context);
        }
    }

    public static void openBatterySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            addNewTaskIfNeeded(context, intent);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
            openAppDetails(context);
        }
    }

    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (isIgnoringBatteryOptimizations(activity)) {
            openBatterySettings(activity);
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (RuntimeException ignored) {
            openBatterySettings(activity);
        }
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager manager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return manager != null
                    && manager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean notificationsEnabled(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void openNotificationSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            intent.putExtra("app_package", context.getPackageName());
            intent.putExtra("app_uid", context.getApplicationInfo().uid);
            addNewTaskIfNeeded(context, intent);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
            openAppDetails(context);
        }
    }

    private static void addNewTaskIfNeeded(Context context, Intent intent) {
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }
}
