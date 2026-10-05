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

        final float d = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setPadding((int)(16*d), (int)(14*d), (int)(16*d), (int)(12*d));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(26*d);
        bg.setStroke((int)(1*d), 0xFFE3E0E8);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("AutoMessenger • Ashna Web Free");
        title.setTextSize(18);
        title.setTextColor(0xFF241F29);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        TextView status = new TextView(this);
        boolean autoOn = getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false);
        status.setText((MessageAccessibilityService.isRunning() ? "● Trợ năng OK" : "⚠ Chưa bật Trợ năng") + "  •  Ashna Web Free");
        status.setTextSize(12);
        status.setTextColor(0xFF77717D);
        status.setPadding(0, (int)(3*d), 0, (int)(8*d));
        panel.addView(status);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        android.widget.LinearLayout messages = new android.widget.LinearLayout(this);
        messages.setOrientation(android.widget.LinearLayout.VERTICAL);
        messages.setPadding(0, (int)(4*d), 0, (int)(8*d));

        String initialQuestion = lastQuestion;
        String initialAnswer = lastAnswer;
        if (!initialQuestion.isEmpty()) {
            addChatBubble(messages, initialQuestion, true);
        }
        if (!initialAnswer.isEmpty()) {
            addChatBubble(messages, initialAnswer, false);
        }
        if (initialQuestion.isEmpty() && initialAnswer.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nhập câu hỏi bên dưới để bot tạo câu trả lời.");
            empty.setTextSize(13);
            empty.setTextColor(0xFF77717D);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(8, (int)(24*d), 8, (int)(24*d));
            messages.addView(empty);
        }

        scroll.addView(messages);
        panel.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                -1, 0, 1f));

        android.widget.LinearLayout composer = new android.widget.LinearLayout(this);
        composer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(0, (int)(5*d), 0, 0);

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFFF4F1F7);
        inputBg.setCornerRadius(24*d);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Nhắn tin");
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setPadding((int)(16*d), 0, (int)(10*d), 0);
        input.setBackground(inputBg);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(input, new android.widget.LinearLayout.LayoutParams(
                0, (int)(50*d), 1f));

        final TextView send = new TextView(this);
        send.setText("➤");
        send.setTextSize(23);
        send.setGravity(Gravity.CENTER);
        send.setTextColor(Color.WHITE);
        send.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(0xFF7C4DFF);
        send.setBackground(sendBg);
        int sendSize = (int)(46*d);
        android.widget.LinearLayout.LayoutParams sendLp =
                new android.widget.LinearLayout.LayoutParams(sendSize, sendSize);
        sendLp.leftMargin = (int)(8*d);
        composer.addView(send, sendLp);

        final Runnable generate = () -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) return;

            input.setText("");
            addChatBubble(messages, q, true);
            TextView thinking = new TextView(this);
            thinking.setText("Đang tạo câu trả lời trên Ashna Web Free…");
            thinking.setTextSize(13);
            thinking.setTextColor(0xFF77717D);
            thinking.setPadding((int)(14*d), (int)(8*d), (int)(14*d), (int)(8*d));
            messages.addView(thinking);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));

            send.setEnabled(false);
            send.setAlpha(0.5f);

            MessageAccessibilityService service = getAccessibilityServiceInstance();
            if (service == null) {
                messages.removeView(thinking);
                addChatBubble(messages, "Chưa bật Trợ năng.", false);
                send.setEnabled(true);
                send.setAlpha(1f);
                return;
            }

            service.generateManualReply(q, (question, answer, error) -> {
                messages.removeView(thinking);
                if (error != null || answer == null || answer.trim().isEmpty()) {
                    addChatBubble(messages,
                            error == null ? "Không tạo được câu trả lời." : error,
                            false);
                } else {
                    addChatBubble(messages, answer, false);
                }
                send.setEnabled(true);
                send.setAlpha(1f);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        };

        send.setOnClickListener(v -> generate.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                generate.run();
                return true;
            }
            return false;
        });

        composer.setContentDescription("Ô nhập tin nhắn và nút gửi");
        panel.addView(composer);

        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.Button copyAnswer = new android.widget.Button(this);
        copyAnswer.setText("Sao chép câu trả lời");
        copyAnswer.setAllCaps(false);
        copyAnswer.setTextSize(12);
        copyAnswer.setOnClickListener(v -> copyToClipboard("Câu trả lời", lastAnswer));
        actions.addView(copyAnswer, new android.widget.LinearLayout.LayoutParams(
                0, (int)(42*d), 1f));

        android.widget.Button putAnswer = new android.widget.Button(this);
        putAnswer.setText("Đưa vào ô chat");
        putAnswer.setAllCaps(false);
        putAnswer.setTextSize(12);
        putAnswer.setOnClickListener(v -> {
            MessageAccessibilityService service = getAccessibilityServiceInstance();
            if (service != null && service.putTextInComposerOnly(lastAnswer)) {
                Toast.makeText(this,
                        "Đã đưa câu trả lời vào ô chat. Kiểm tra rồi bấm Gửi.",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Không tìm thấy ô chat hiện tại.", Toast.LENGTH_SHORT).show();
            }
        });
        actions.addView(putAnswer, new android.widget.LinearLayout.LayoutParams(
                0, (int)(42*d), 1f));

        panel.addView(actions);

        android.widget.Button copyQuestion = new android.widget.Button(this);
        copyQuestion.setText("Sao chép câu hỏi");
        copyQuestion.setAllCaps(false);
        copyQuestion.setTextSize(12);
        copyQuestion.setOnClickListener(v -> copyToClipboard("Câu hỏi", lastQuestion));
        panel.addView(copyQuestion);

        android.widget.Button fullSettings = new android.widget.Button(this);
        fullSettings.setText("⚙ Cài đặt / Prompt / Trợ năng");
        fullSettings.setAllCaps(false);
        fullSettings.setTextSize(12);
        fullSettings.setOnClickListener(v -> {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            } catch (Exception ignored) {}
        });
        panel.addView(fullSettings);

        android.widget.Button toggle = new android.widget.Button(this);
        toggle.setText(autoOn ? "Tắt tự động trả lời" : "Bật tự động trả lời");
        toggle.setAllCaps(false);
        toggle.setTextSize(12);
        toggle.setOnClickListener(v -> {
            android.content.SharedPreferences p = getSharedPreferences("AutoMessenger", 0);
            boolean next = !p.getBoolean("auto", false);
            p.edit().putBoolean("auto", next).apply();
            if (next && projection != null && imageReader == null) startScreenCapture();
            if (!next) stopScreenCaptureOnly();
            status.setText((MessageAccessibilityService.isRunning() ? "● Trợ năng OK" : "⚠ Chưa bật Trợ năng") + "  •  Ashna Web Free  •  " + (next ? "Tự động ON" : "Tự động OFF"));
            toggle.setText(next ? "Tắt tự động trả lời" : "Bật tự động trả lời");
        });
        panel.addView(toggle);

        int width = (int)(360 * d);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, (int)(560*d),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 10;
        lp.y = 120;

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

    private void addChatBubble(android.widget.LinearLayout container, String text, boolean user) {
        final float d = getResources().getDisplayMetrics().density;

        TextView bubbleText = new TextView(this);
        bubbleText.setText(text == null ? "" : text);
        bubbleText.setTextSize(14);
        bubbleText.setTextColor(user ? Color.WHITE : 0xFF28232D);
        bubbleText.setPadding((int)(14*d), (int)(9*d), (int)(14*d), (int)(9*d));

        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setColor(user ? 0xFF7C4DFF : 0xFFF1EEF4);
        bubbleBg.setCornerRadius(18*d);
        bubbleText.setBackground(bubbleBg);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(0, (int)(3*d), 0, (int)(3*d));

        android.widget.LinearLayout.LayoutParams bubbleLp =
                new android.widget.LinearLayout.LayoutParams(
                        (int)(300*d), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        if (user) bubbleLp.gravity = Gravity.RIGHT;
        else bubbleLp.gravity = Gravity.LEFT;

        row.addView(bubbleText, bubbleLp);
        container.addView(row);

        if (!user) {
            android.widget.Button copy = new android.widget.Button(this);
            copy.setText("Sao chép");
            copy.setAllCaps(false);
            copy.setTextSize(11);
            copy.setOnClickListener(v -> copyToClipboard("Câu trả lời", text));
            android.widget.LinearLayout.LayoutParams copyLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            (int)(34*d));
            copyLp.gravity = Gravity.LEFT;
            container.addView(copy, copyLp);
        }
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
