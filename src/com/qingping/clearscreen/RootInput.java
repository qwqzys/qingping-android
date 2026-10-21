package com.qingping.clearscreen;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional fallback for ad SDKs that ignore accessibility-injected clicks. */
public final class RootInput {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private RootInput() {
    }

    public static void tap(Context context, float x, float y) {
        final int px = Math.round(x);
        final int py = Math.round(y);
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                try {
                    Process process = new ProcessBuilder(
                            "su", "-c", "input tap " + px + " " + py)
                            .redirectErrorStream(true)
                            .start();
                    code = process.waitFor();
                } catch (Exception ignored) {
                }
                Diag.log(app, "root tap " + px + "," + py + " exit=" + code);
            }
        });
    }
}
