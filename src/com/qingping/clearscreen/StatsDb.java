package com.qingping.clearscreen;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Local-only statistics. No network permission is declared by the app. */
public final class StatsDb extends SQLiteOpenHelper {

    public static final int KIND_SPLASH = 1;
    public static final int KIND_JUMP = 2;

    private static final String DB_NAME = "qingping_stats.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "events";

    public StatsDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "package_name TEXT NOT NULL,"
                + "label TEXT,"
                + "kind INTEGER NOT NULL,"
                + "ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_events_ts ON " + TABLE + "(ts)");
        db.execSQL("CREATE INDEX idx_events_pkg ON " + TABLE + "(package_name, ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the initial schema. Keep data for future migrations.
    }

    public synchronized void record(String packageName, String label, int kind) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("label", label);
        values.put("kind", kind);
        values.put("ts", System.currentTimeMillis());
        try {
            getWritableDatabase().insert(TABLE, null, values);
        } catch (RuntimeException ignored) {
            // A failed statistics write must never break ad interception.
        }
    }

    public static long todayStart() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public synchronized Summary summary(long since) {
        Summary result = new Summary();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*),"
                            + " SUM(CASE WHEN kind=" + KIND_SPLASH + " THEN 1 ELSE 0 END),"
                            + " SUM(CASE WHEN kind=" + KIND_JUMP + " THEN 1 ELSE 0 END),"
                            + " COUNT(DISTINCT package_name)"
                            + " FROM " + TABLE + " WHERE ts>=?",
                    new String[]{String.valueOf(since)});
            if (cursor.moveToFirst()) {
                result.total = cursor.getInt(0);
                result.splash = cursor.getInt(1);
                result.jump = cursor.getInt(2);
                result.apps = cursor.getInt(3);
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public synchronized List<AppStat> perApp(long since, int limit) {
        List<AppStat> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT package_name, MAX(label), COUNT(*), MAX(ts),"
                            + " SUM(CASE WHEN kind=" + KIND_SPLASH + " THEN 1 ELSE 0 END),"
                            + " SUM(CASE WHEN kind=" + KIND_JUMP + " THEN 1 ELSE 0 END)"
                            + " FROM " + TABLE + " WHERE ts>=?"
                            + " GROUP BY package_name"
                            + " ORDER BY COUNT(*) DESC, MAX(ts) DESC LIMIT ?",
                    new String[]{String.valueOf(since), String.valueOf(Math.max(1, limit))});
            while (cursor.moveToNext()) {
                AppStat stat = new AppStat();
                stat.packageName = cursor.getString(0);
                stat.label = cursor.getString(1);
                stat.count = cursor.getInt(2);
                stat.lastTs = cursor.getLong(3);
                stat.splash = cursor.getInt(4);
                stat.jump = cursor.getInt(5);
                result.add(stat);
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public synchronized void clearAll() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (RuntimeException ignored) {
        }
    }

    public static final class Summary {
        public int total;
        public int splash;
        public int jump;
        public int apps;
    }

    public static final class AppStat {
        public String packageName;
        public String label;
        public int count;
        public int splash;
        public int jump;
        public long lastTs;
    }
}
