package com.hoangnt391.automessenger;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;

public class AutoMessengerService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";
    private static final String CHANNEL = "automessenger_running";
    private static final long OCR_INTERVAL_MS = 900L;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    private TextRecognizer recognizer;
    private WindowManager wm;
    private View bubble;
    private long lastFrameAt = 0L;
    private String lastStableText = "";
    private String pendingText = "";
    private int pendingCount = 0;
    private boolean ocrBusy = false;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
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
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) return;

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopScreenCaptureOnly();
                projection = null;
            }
        }, handler);

        if (getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) {
            startScreenCapture();
        }
    }

    private void startScreenCapture() {
        if (projection == null || imageReader != null) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.max(1, dm.widthPixels);
        int height = Math.max(1, dm.heightPixels);
        int density = Math.max(1, dm.densityDpi);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < OCR_INTERVAL_MS) {
                Image old = reader.acquireLatestImage();
                if (old != null) old.close();
                return;
            }
            if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)
                    || !MessageAccessibilityService.isMessengerActive()
                    || ocrBusy) {
                Image old = reader.acquireLatestImage();
                if (old != null) old.close();
                return;
            }

            Image image = reader.acquireLatestImage();
            if (image == null) return;
            lastFrameAt = now;
            Bitmap bitmap = null;
            try {
                bitmap = imageToBitmap(image);
            } catch (Exception ignored) {
            } finally {
                image.close();
            }
            if (bitmap == null) return;

            ocrBusy = true;
            Bitmap finalBitmap = bitmap;
            InputImage input = InputImage.fromBitmap(finalBitmap, 0);
            recognizer.process(input)
                    .addOnSuccessListener(result -> handleOcrResult(result, finalBitmap))
                    .addOnFailureListener(e -> finishOcr(finalBitmap));
        }, handler);

        virtualDisplay = projection.createVirtualDisplay(
                "AutoMessengerScreen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap padded = Bitmap.createBitmap(
                width + Math.max(0, rowPadding / Math.max(1, pixelStride)),
                height,
                Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);

        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private void handleOcrResult(com.google.mlkit.vision.text.Text result, Bitmap bitmap) {
        String candidate = "";
        int bestBottom = -1;
        int screenWidth = bitmap.getWidth();
        int screenHeight = bitmap.getHeight();

        for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
            for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r == null) continue;
                String text = line.getText() == null ? "" : line.getText().trim();
                if (text.length() < 2 || text.length() > 120) continue;

                int centerX = (r.left + r.right) / 2;
                if (centerX > screenWidth * 0.62f) continue;
                if (r.top < screenHeight * 0.12f || r.bottom > screenHeight * 0.88f) continue;
                if (isLikelyUiText(text)) continue;

                // Prefer the lowest incoming-looking line in the conversation area.
                if (r.bottom > bestBottom) {
                    bestBottom = r.bottom;
                    candidate = text;
                }
            }
        }

        if (!candidate.isEmpty()) {
            if (candidate.equals(pendingText)) {
                pendingCount++;
            } else {
                pendingText = candidate;
                pendingCount = 1;
            }

            // Require the same OCR result twice to avoid reacting to a transient frame.
            if (pendingCount >= 2 && !candidate.equals(lastStableText)) {
                lastStableText = candidate;
                MessageAccessibilityService.handleScreenMessage(candidate);
            }
        }

        finishOcr(bitmap);
    }

    private void finishOcr(Bitmap bitmap) {
        try { bitmap.recycle(); } catch (Exception ignored) {}
        ocrBusy = false;
    }

    private boolean isLikelyUiText(String value) {
        String x = value.toLowerCase(Locale.ROOT).trim();
        return x.equals("messenger") || x.equals("send") || x.equals("gửi")
                || x.equals("gui") || x.equals("more") || x.equals("thêm")
                || x.equals("back") || x.equals("quay lại")
                || x.equals("camera") || x.equals("máy ảnh")
                || x.equals("search") || x.equals("tìm kiếm")
                || x.contains("type a message") || x.contains("write a message")
                || x.contains("nhập tin nhắn");
    }

    private void stopScreenCaptureOnly() {
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        pendingText = "";
        lastStableText = "";
        pendingCount = 0;
        ocrBusy = false;
    }

    private void showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        TextView v = new TextView(this);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        v.setTypeface(null, android.graphics.Typeface.BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.rgb(35, 105, 210));
        bg.setStroke(2, Color.WHITE);
        v.setBackground(bg);

        refreshBubbleLabel(v);

        final int size = (int) (76 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 12;
        lp.y = 220;

        v.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean dragged;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = lp.x;
                    startY = lp.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragged = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = (int) (downX - event.getRawX());
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) dragged = true;
                    lp.x = Math.max(0, startX + dx);
                    lp.y = Math.max(0, startY + dy);
                    wm.updateViewLayout(v, lp);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragged) {
                        android.content.SharedPreferences p =
                                getSharedPreferences("AutoMessenger", 0);
                        boolean enabled = !p.getBoolean("auto", false);
                        p.edit().putBoolean("auto", enabled).apply();

                        if (enabled) {
                            startScreenCapture();
                        } else {
                            stopScreenCaptureOnly();
                        }

                        refreshBubbleLabel(v);
                        Toast.makeText(
                                AutoMessengerService.this,
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
        v.setText("AI");
    }

    private void stopProjectionOnly() {
        stopScreenCaptureOnly();
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
    }

    @Override public void onDestroy() {
        stopProjectionOnly();
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) {}
            recognizer = null;
        }
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
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
