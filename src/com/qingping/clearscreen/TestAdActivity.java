package com.qingping.clearscreen;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A local demo page. It contains nothing but a fake splash ad so the user can
 * verify that the accessibility service really clicks the skip control.
 */
public class TestAdActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView skipButton;
    private TextView countdownView;
    private int secondsLeft = 5;
    private boolean closed;

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (closed) {
                return;
            }
            if (secondsLeft <= 0) {
                close(false);
                return;
            }
            skipButton.setText("跳过 " + secondsLeft);
            countdownView.setText(secondsLeft + " 秒后自动关闭");
            secondsLeft--;
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_test_ad);

        skipButton = findViewById(R.id.skipBtn);
        countdownView = findViewById(R.id.countdown);
        skipButton.setOnClickListener(v -> close(true));

        handler.post(countdown);
    }

    private void close(boolean byUser) {
        if (closed) {
            return;
        }
        closed = true;
        handler.removeCallbacks(countdown);
        if (byUser) {
            Toast.makeText(this, "已跳过测试广告", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
