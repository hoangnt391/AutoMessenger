package com.hoangnt391.automessenger;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;

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
        MediaProjectionManager mpm =
                (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopProjectionOnly(); }
            }, new Handler(Looper.getMainLooper()));
        }
    }

    private void showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return;

        TextView v = new TextView(this);
        v.setText("AI");
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(0xffffffff);
        v.setBackgroundResource(android.R.drawable.btn_default);
        v.setOnClickListener(x -> stopSelf());

        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                64, 64,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 20;
        lp.y = 220;
        wm.addView(v, lp);
        bubble = v;
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
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "AutoMessenger", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("AutoMessenger")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }
}