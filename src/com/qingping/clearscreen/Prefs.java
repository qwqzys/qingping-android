package com.qingping.clearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** Small, synchronous preference store. */
public final class Prefs {

    private static final String FILE = "qingping_prefs";
    private static final String KEY_SPLASH = "splash_enabled";
    private static final String KEY_JUMP = "jump_enabled";
    private static final String KEY_NOTIFIED = "first_run_done";
    private static final String KEY_ROOT_TAP = "root_tap_enabled";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean isSplashEnabled() {
        return sp.getBoolean(KEY_SPLASH, true);
    }

    public void setSplashEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_SPLASH, enabled).apply();
    }

    public boolean isJumpEnabled() {
        return sp.getBoolean(KEY_JUMP, true);
    }

    public void setJumpEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_JUMP, enabled).apply();
    }

    public boolean isRootTapEnabled() {
        return sp.getBoolean(KEY_ROOT_TAP, false);
    }

    public void setRootTapEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ROOT_TAP, enabled).apply();
    }

    public boolean isFirstRunDone() {
        return sp.getBoolean(KEY_NOTIFIED, false);
    }

    public void markFirstRunDone() {
        sp.edit().putBoolean(KEY_NOTIFIED, true).apply();
    }
}
