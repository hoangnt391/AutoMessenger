package com.hoangnt391.automessenger;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;

public class AutoMessengerService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";
    private static final String CHANNEL = "automessenger_running";
    private MediaProjection projection;
    private WindowManager wm;
    private View bubble;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1001, notification("AutoMessenger đang chạy"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DATA)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            startProjection(resultCode, data);
        }
        showBubble();
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        if (projection != null || data == null) return;
        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection != null) projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopProjectionOnly(); }
        }, new Handler(Looper.getMainLooper()));
    }

    private void showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return;
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        TextView v = new TextView(this);
        v.setTextSize(11);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.rgb(35, 105, 210));
        bg.setStroke(2, Color.WHITE);
        v.setBackground(bg);
        refreshBubbleLabel(v);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                64, 64,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 20;
        lp.y = 220;

        v.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = lp.x; startY = lp.y;
                    downX = event.getRawX(); downY = event.getRawY();
                    dragged = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = (int)(downX - event.getRawX());
                    int dy = (int)(event.getRawY() - downY);
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) dragged = true;
                    lp.x = Math.max(0, startX + dx);
                    lp.y = Math.max(0, startY + dy);
                    wm.updateViewLayout(v, lp);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragged) {
                        android.content.SharedPreferences p = getSharedPreferences("AutoMessenger", 0);
                        boolean enabled = !p.getBoolean("auto", false);
                        p.edit().putBoolean("auto", enabled).apply();
                        refreshBubbleLabel(v);
                        Toast.makeText(AutoMessengerService.this,
                                enabled ? "Đã bật tự trả lời" : "Đã tạm dừng tự trả lời",
                                Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return true;
            }
        });
        wm.addView(v, lp);
        bubble = v;
    }

    private void refreshBubbleLabel(TextView v) {
        v.setText(getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false) ? "AI ON" : "AI OFF");
    }

    private void stopProjectionOnly() {
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
    }

    @Override public void onDestroy() {
        stopProjectionOnly();
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        getSharedPreferences("AutoMessenger", 0).edit().putBoolean("enabled", false).apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "AutoMessenger", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("AutoMessenger").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build();
    }
}