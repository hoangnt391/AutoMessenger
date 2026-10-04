package com.hoangnt391.automessenger;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
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
    private static AutoMessengerService instance;
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";
    private static final String CHANNEL = "automessenger_running";
    private static final long OCR_INTERVAL_MS = 450L;

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
    private boolean settingsOpen = false;
    private View chatPanel;
    private View closeTarget;
    private static volatile String lastQuestion = "";
    private static volatile String lastAnswer = "";

    public static void setLastConversation(String question, String answer) {
        lastQuestion = question == null ? "" : question;
        lastAnswer = answer == null ? "" : answer;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
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
                    || !MessageAccessibilityService.isChatAppActive()
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

    public static void requestShowBubble() {
        AutoMessengerService s = instance;
        if (s != null) s.handler.post(s::showBubble);
    }

    private void showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)
                || getSharedPreferences("AutoMessenger", 0).getBoolean("bubble_hidden", false)) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        TextView v = new TextView(this);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.rgb(124, 77, 255));
        bg.setStroke(2, Color.WHITE);
        v.setBackground(bg);
        v.setText("AI");

        final int size = (int) (68 * getResources().getDisplayMetrics().density);
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
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        dragged = true;
                        showCloseTarget();
                    }
                    lp.x = Math.max(0, startX + dx);
                    lp.y = Math.max(0, startY + dy);
                    wm.updateViewLayout(v, lp);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (dragged) {
                        boolean overClose = isOverCloseTarget(event.getRawX(), event.getRawY());
                        hideCloseTarget();
                        if (overClose) {
                            getSharedPreferences("AutoMessenger", 0).edit()
                                    .putBoolean("bubble_hidden", true).apply();
                            removeBubbleOnly();
                            Toast.makeText(AutoMessengerService.this,
                                    "Đã đóng bong bóng. Có thể bật lại trong cài đặt.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        openSettings();
                    }
                    return true;
                }
                return true;
            }
        });

        wm.addView(v, lp);
        bubble = v;
    }

    private void showCloseTarget() {
        if (closeTarget != null || wm == null) return;
        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextSize(24);
        x.setGravity(Gravity.CENTER);
        x.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.rgb(220, 53, 69));
        x.setBackground(bg);

        int s = (int)(62 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                s, s,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = (int)(22 * getResources().getDisplayMetrics().density);
        wm.addView(x, p);
        closeTarget = x;
    }

    private boolean isOverCloseTarget(float rawX, float rawY) {
        if (closeTarget == null) return false;
        int[] loc = new int[2];
        closeTarget.getLocationOnScreen(loc);
        float cx = loc[0] + closeTarget.getWidth() / 2f;
        float cy = loc[1] + closeTarget.getHeight() / 2f;
        float dx = rawX - cx, dy = rawY - cy;
        return Math.sqrt(dx * dx + dy * dy) <= closeTarget.getWidth() * 0.75f;
    }

    private void hideCloseTarget() {
        if (closeTarget != null && wm != null) {
            try { wm.removeView(closeTarget); } catch (Exception ignored) {}
            closeTarget = null;
        }
    }

    private void removeBubbleOnly() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
    }

    private void openSettings() {
        if (chatPanel != null) {
            closeChatPanel();
            return;
        }
        showChatPanel();
    }

    private void showChatPanel() {
        if (wm == null || chatPanel != null) return;

        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setPadding(28, 24, 28, 20);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(28);
        bg.setStroke(2, Color.LTGRAY);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("AutoMessenger AI");
        title.setTextSize(19);
        title.setTextColor(Color.DKGRAY);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        TextView status = new TextView(this);
        boolean autoOn = getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false);
        status.setText(autoOn ? "● Đang tự động trả lời" : "○ Đang tắt tự động");
        status.setTextSize(14);
        status.setPadding(0, 10, 0, 8);
        panel.addView(status);

        TextView questionLabel = new TextView(this);
        questionLabel.setText("Câu hỏi gần nhất");
        questionLabel.setTextSize(13);
        questionLabel.setTextColor(Color.DKGRAY);
        questionLabel.setPadding(0, 8, 0, 4);
        panel.addView(questionLabel);

        TextView question = new TextView(this);
        question.setText(lastQuestion.isEmpty() ? "Chưa nhận được câu hỏi." : lastQuestion);
        question.setTextSize(14);
        question.setTextColor(Color.BLACK);
        question.setPadding(12, 10, 12, 10);
        panel.addView(question);

        android.widget.Button copyQuestion = new android.widget.Button(this);
        copyQuestion.setText("Sao chép câu hỏi");
        copyQuestion.setOnClickListener(v -> copyToClipboard("Câu hỏi", lastQuestion));
        panel.addView(copyQuestion);

        TextView answerLabel = new TextView(this);
        answerLabel.setText("Câu trả lời AI");
        answerLabel.setTextSize(13);
        answerLabel.setTextColor(Color.DKGRAY);
        answerLabel.setPadding(0, 8, 0, 4);
        panel.addView(answerLabel);

        TextView answer = new TextView(this);
        answer.setText(lastAnswer.isEmpty() ? "Chưa có câu trả lời." : lastAnswer);
        answer.setTextSize(14);
        answer.setTextColor(Color.BLACK);
        answer.setPadding(12, 10, 12, 10);
        panel.addView(answer);

        android.widget.Button copyAnswer = new android.widget.Button(this);
        copyAnswer.setText("Sao chép câu trả lời");
        copyAnswer.setOnClickListener(v -> copyToClipboard("Câu trả lời", lastAnswer));
        panel.addView(copyAnswer);

        android.widget.Button putAnswer = new android.widget.Button(this);
        putAnswer.setText("Đưa câu trả lời vào ô chat (chưa gửi)");
        putAnswer.setOnClickListener(v -> {
            MessageAccessibilityService s = MessageAccessibilityService.isRunning()
                    ? getAccessibilityServiceInstance() : null;
            if (s != null && s.putTextInComposerOnly(lastAnswer)) {
                Toast.makeText(this, "Đã đưa câu trả lời vào ô chat. Kiểm tra rồi bấm Gửi.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Không tìm thấy ô chat hiện tại.", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(putAnswer);

        TextView promptLabel = new TextView(this);
        promptLabel.setText("Yêu cầu trả lời");
        promptLabel.setTextSize(13);
        promptLabel.setTextColor(Color.DKGRAY);
        promptLabel.setPadding(0, 8, 0, 4);
        panel.addView(promptLabel);

        android.widget.EditText promptInput = new android.widget.EditText(this);
        promptInput.setText(getSharedPreferences("AutoMessenger", 0).getString(
                "prompt",
                "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không nhắc rằng bạn là AI."));
        promptInput.setHint("Nhập cách bot phải trả lời...");
        promptInput.setMinLines(3);
        promptInput.setGravity(Gravity.TOP);
        promptInput.setTextSize(14);
        panel.addView(promptInput, new android.widget.LinearLayout.LayoutParams(
                -1, (int)(92 * getResources().getDisplayMetrics().density)));

        android.widget.Button savePrompt = new android.widget.Button(this);
        savePrompt.setText("Lưu yêu cầu");
        savePrompt.setOnClickListener(v -> {
            String request = promptInput.getText().toString().trim();
            if (request.isEmpty()) {
                request = "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không nhắc rằng bạn là AI.";
            }
            getSharedPreferences("AutoMessenger", 0).edit()
                    .putString("prompt", request)
                    .apply();
            Toast.makeText(this, "Đã lưu yêu cầu trả lời ✓", Toast.LENGTH_SHORT).show();
        });
        panel.addView(savePrompt);

        android.widget.Button toggle = new android.widget.Button(this);
        toggle.setText(autoOn ? "Tắt tự động trả lời" : "Bật tự động trả lời");
        toggle.setOnClickListener(v -> {
            android.content.SharedPreferences p = getSharedPreferences("AutoMessenger", 0);
            boolean next = !p.getBoolean("auto", false);
            p.edit().putBoolean("auto", next).apply();
            if (next && projection != null && imageReader == null) startScreenCapture();
            if (!next) stopScreenCaptureOnly();
            status.setText(next ? "● Đang tự động trả lời" : "○ Đang tắt tự động");
            toggle.setText(next ? "Tắt tự động trả lời" : "Bật tự động trả lời");
        });
        panel.addView(toggle);

        android.widget.Button settings = new android.widget.Button(this);
        settings.setText("Mở cài đặt");
        settings.setOnClickListener(v -> {
            closeChatPanel();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        });
        panel.addView(settings);

        android.widget.Button close = new android.widget.Button(this);
        close.setText("Đóng");
        close.setOnClickListener(v -> closeChatPanel());
        panel.addView(close);

        int width = (int)(340 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 12;
        lp.y = 180;

        panel.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                closeChatPanel();
                return true;
            }
            return false;
        });

        wm.addView(panel, lp);
        chatPanel = panel;
    }


    private MessageAccessibilityService getAccessibilityServiceInstance() {
        return MessageAccessibilityService.getInstance();
    }

    private void copyToClipboard(String label, String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "Chưa có nội dung để sao chép.", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
        Toast.makeText(this, "Đã sao chép " + label.toLowerCase(Locale.ROOT) + " ✓", Toast.LENGTH_SHORT).show();
    }

    private void closeChatPanel() {
        if (chatPanel != null && wm != null) {
            try { wm.removeView(chatPanel); } catch (Exception ignored) {}
            chatPanel = null;
        }
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
        instance = null;
        stopProjectionOnly();
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) {}
            recognizer = null;
        }
        closeChatPanel();
        hideCloseTarget();
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
