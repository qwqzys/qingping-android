package com.qingping.clearscreen;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny on-device diagnostics file. Never leaves the phone unless the user exports it. */
public final class Diag {

    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 64 * 1024L;

    private Diag() {
    }

    public static void log(Context context, String message) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), "diag.log");
                if (file.length() > MAX_BYTES) {
                    File old = new File(context.getFilesDir(), "diag.old.log");
                    if (old.exists()) {
                        old.delete();
                    }
                    file.renameTo(old);
                }
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()) + " " + message + "\n";
                FileWriter writer = new FileWriter(file, true);
                try {
                    writer.append(line);
                } finally {
                    writer.close();
                }
            } catch (RuntimeException | java.io.IOException ignored) {
            }
        }
    }
}
