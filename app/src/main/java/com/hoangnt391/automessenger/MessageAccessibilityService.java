package com.hoangnt391.automessenger;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class MessageAccessibilityService extends AccessibilityService {
    private static final String DEBUG_CHANNEL = "automessenger_debug";
    private static MessageAccessibilityService instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object pendingLock = new Object();
    private final List<String> pendingMessages = new ArrayList<>();
    private ScheduledFuture<?> pendingFlush;
    private String lastQueuedText = "";
    private long lastQueuedAt = 0L;
    private AccessibilityNodeInfo lastInput;
    private volatile boolean replying = false;
    private String lastIncoming = "";
    private String lastSent = "";
    private long lastReplyAt = 0L;
    private volatile boolean ashnaWebBusy = false;
    private String ashnaWebTargetPackage = "";
    private String ashnaWebQuestion = "";

    private void ensureDebugChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm =
                    getSystemService(android.app.NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(DEBUG_CHANNEL) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        DEBUG_CHANNEL, "AutoMessenger - Nhật ký",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }

    private void postDebug(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ensureDebugChannel();
                android.app.Notification.Builder b =
                        android.os.Build.VERSION.SDK_INT >= 26
                                ? new android.app.Notification.Builder(this, DEBUG_CHANNEL)
                                : new android.app.Notification.Builder(this);
                b.setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("AutoMessenger • AshnaAI")
                        .setContentText(message)
                        .setAutoCancel(true);
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(4102, b.build());
            } catch (Exception ignored) {}
        });
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        ensureDebugChannel();
        postDebug("Trợ năng đã kết nối. Chờ tin nhắn mới.");
    }

    public static boolean isRunning() { return instance != null; }

    public static MessageAccessibilityService getInstance() { return instance; }

    public static boolean isChatAppActive() {
        MessageAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        return isSupportedChatPackage(root.getPackageName().toString());
    }

    public static boolean isMessengerActive() { return isChatAppActive(); }

    private static boolean isSupportedChatPackage(String pkg) {
        return "com.facebook.orca".equals(pkg)
                || "com.zing.zalo".equals(pkg)
                || "com.whatsapp".equals(pkg)
                || "org.telegram.messenger".equals(pkg);
    }

    public static void handleScreenMessage(String text) {
        MessageAccessibilityService s = instance;
        if (s != null && text != null) s.handleDetectedIncoming(text.trim());
    }

    private void handleDetectedIncoming(String incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;
        if (incoming.equals(lastSent)) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);
        queueIncomingMessage(incoming);
    }

    /**
     * Smart debounce:
     * - One isolated message: process after 5 seconds.
     * - If another message arrives during that 5-second window, switch to a
     *   10-second quiet period and keep extending it while messages continue.
     * - All messages in the burst are sent to AI as one request.
     */
    private void queueIncomingMessage(String incoming) {
        final String text = incoming == null ? "" : incoming.trim();
        if (text.isEmpty()) return;

        synchronized (pendingLock) {
            long now = System.currentTimeMillis();

            // Accessibility can emit the same message several times. Treat
            // identical text within 1.2s as the same event.
            if (text.equals(lastQueuedText) && now - lastQueuedAt < 1200L) return;
            lastQueuedText = text;
            lastQueuedAt = now;

            boolean wasEmpty = pendingMessages.isEmpty();
            pendingMessages.add(text);

            if (pendingFlush != null) pendingFlush.cancel(false);

            long delay = wasEmpty ? 5L : 10L;
            postDebug(wasEmpty
                    ? "Có tin mới. Chờ 5s để xác định có nhắn tiếp..."
                    : "Đang gom tin nhắn. Sẽ xử lý sau 10s im lặng...");

            pendingFlush = debounceScheduler.schedule(
                    this::flushPendingMessages, delay, TimeUnit.SECONDS);
        }
    }

    private void flushPendingMessages() {
        final String batch;
        synchronized (pendingLock) {
            if (pendingMessages.isEmpty()) {
                pendingFlush = null;
                return;
            }
            batch = joinPendingMessages(pendingMessages);
            pendingMessages.clear();
            pendingFlush = null;
        }

        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;

        lastIncoming = batch;
        postDebug("Đã gom " + countMessages(batch) + " tin. Đang xử lý 1 lần...");
        generateAndSend(batch);
    }

    private String joinPendingMessages(List<String> messages) {
        StringBuilder b = new StringBuilder();
        for (String message : messages) {
            if (message == null || message.trim().isEmpty()) continue;
            if (b.length() > 0) b.append("\n");
            b.append(message.trim());
        }
        String result = b.toString();
        return result.length() > 8000 ? result.substring(result.length() - 8000) : result;
    }

    private int countMessages(String batch) {
        if (batch == null || batch.isEmpty()) return 0;
        return batch.split("\\n").length;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;

        // Never inspect Settings, launcher, permission screens, etc.
        // This is the main guard against the old "nhảy loạn tap" behaviour.
        String pkg = root.getPackageName().toString();
        if (!isSupportedChatPackage(pkg)) {
            safeRecycle(root);
            return;
        }

        AccessibilityNodeInfo input = findEditable(root);
        if (input != null && input.isVisibleToUser()) replaceLastInput(input);

        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) {
            safeRecycle(root);
            return;
        }
        String incoming = extractLatestMessage(root, event);
        safeRecycle(root);
        if (incoming == null || incoming.trim().isEmpty()) return;
        incoming = incoming.trim();

        if (incoming.equals(lastSent)) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);

        queueIncomingMessage(incoming);
    }

    private void generateAndSend(final String incoming) {
        replying = true;
        android.content.SharedPreferences p = getSharedPreferences("AutoMessenger", 0);
        String mode = p.getString("ai_mode", "web");
        if ("web".equals(mode)) {
            AccessibilityNodeInfo current = getRootInActiveWindow();
            ashnaWebTargetPackage = value(current == null ? null : current.getPackageName());
            safeRecycle(current);
            ashnaWebQuestion = incoming;
            postDebug("Ashna Web Free: mở GPT-6 Sol...");
            openAshnaWebAndAsk(incoming);
            return;
        }
        postDebug("Đang xử lý bằng AshnaAI API...");
        worker.execute(() -> {
            try {
                String prompt = p.getString("prompt",
                        "Bạn đang tạo NỘI DUNG TIN NHẮN để ứng dụng tự động gửi cho người khác. " +
                        "Chỉ trả về đúng nội dung tin nhắn cần gửi. Không giải thích, không nói bạn là AI. " +
                        "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không markdown.");
                String reply = AiClient.reply(p.getString("ashna_api_key", ""),
                        p.getString("ashna_model", "gpt-6-sol"), prompt, incoming);
                if (reply != null && !reply.trim().isEmpty()) {
                    final String answer = reply.trim();
                    AutoMessengerService.setLastConversation(incoming, answer);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (sendMessage(answer)) {
                            lastSent = answer;
                            lastReplyAt = System.currentTimeMillis();
                            postDebug("Đã gửi câu trả lời.");
                        }
                    });
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Lỗi AshnaAI không xác định" : e.getMessage();
                postDebug("LỖI: " + shortError(message));
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(this,
                                "Không trả lời được: " + shortError(message),
                                android.widget.Toast.LENGTH_LONG).show());
            } finally {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> replying = false, 1000L);
            }
        });
    }

    private void openAshnaWebAndAsk(final String question) {
        ashnaWebBusy = true;
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://app.ashna.ai/chat?agent=gpt-6-sol"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            ashnaWebBusy = false;
            replying = false;
            reportError("Không mở được Ashna Web: " + e.getMessage());
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> driveAshnaWebInput(question, 0), 4000L);
    }

    private void driveAshnaWebInput(final String question, final int attempt) {
        if (!ashnaWebBusy) return;
        if (attempt > 35) {
            ashnaWebBusy = false; replying = false;
            reportError("Ashna Web không tìm thấy ô nhập. Hãy đăng nhập AshnaAI trên trình duyệt trước.");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = value(root == null ? null : root.getPackageName());
        if (!isBrowserPackage(pkg)) {
            safeRecycle(root);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> driveAshnaWebInput(question, attempt + 1), 1000L);
            return;
        }
        AccessibilityNodeInfo input = findWebChatInput(root);
        if (input == null) {
            safeRecycle(root);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> driveAshnaWebInput(question, attempt + 1), 1000L);
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, question);
        boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_FOCUS) && input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        safeRecycle(input);
        if (!ok) {
            safeRecycle(root);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> driveAshnaWebInput(question, attempt + 1), 700L);
            return;
        }
        AccessibilityNodeInfo fresh = getRootInActiveWindow();
        AccessibilityNodeInfo send = findWebSendButton(fresh);
        boolean clicked = send != null && clickNodeOrParent(send);
        safeRecycle(send); safeRecycle(fresh); safeRecycle(root);
        if (!clicked) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> driveAshnaWebInput(question, attempt + 1), 900L);
            return;
        }
        postDebug("Ashna Web: đã gửi câu hỏi, chờ câu trả lời...");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> pollAshnaWebAnswer(question, 0, ""), 2000L);
    }

    private void pollAshnaWebAnswer(final String question, final int attempt, final String previous) {
        if (!ashnaWebBusy) return;
        if (attempt > 50) {
            ashnaWebBusy = false; replying = false;
            reportError("Ashna Web không đọc được câu trả lời sau 50 lần kiểm tra.");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = value(root == null ? null : root.getPackageName());
        if (!isBrowserPackage(pkg)) {
            safeRecycle(root);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> pollAshnaWebAnswer(question, attempt + 1, previous), 800L);
            return;
        }
        String answer = extractAshnaWebAnswer(root, question);
        safeRecycle(root);
        if (answer != null && answer.length() >= 2 && !answer.equals(previous) && !answer.equals(question)) {
            ashnaWebBusy = false; replying = false;
            final String clean = answer.trim();
            AutoMessengerService.setLastConversation(question, clean);
            postDebug("Ashna Web: đã nhận câu trả lời.");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.content.Intent back = getPackageManager().getLaunchIntentForPackage(ashnaWebTargetPackage);
                    if (back != null) { back.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(back); }
                } catch (Exception ignored) {}
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (sendMessage(clean)) { lastSent = clean; lastReplyAt = System.currentTimeMillis(); postDebug("Đã gửi câu trả lời qua Ashna Web Free."); }
                }, 1500L);
            }, 500L);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> pollAshnaWebAnswer(question, attempt + 1, answer == null ? previous : answer), 900L);
    }

    private boolean isBrowserPackage(String pkg) {
        return "com.android.chrome".equals(pkg) || "com.microsoft.emmx".equals(pkg) ||
                "com.brave.browser".equals(pkg) || "org.mozilla.firefox".equals(pkg) ||
                "com.sec.android.app.sbrowser".equals(pkg);
    }

    private AccessibilityNodeInfo findWebChatInput(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable()) {
            String all = (value(node.getHintText()) + " " + value(node.getContentDescription()) + " " + value(node.getText())).toLowerCase(Locale.ROOT);
            Rect r = new Rect(); node.getBoundsInScreen(r);
            boolean notAddressBar = r.top > 140;
            boolean looksLikeChat = all.contains("message") || all.contains("ask") || all.contains("chat") || all.contains("prompt") || all.contains("nhập") || all.contains("type");
            if (notAddressBar && looksLikeChat) return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo result = findWebChatInput(node.getChild(i)); if (result != null) return result; }
        return null;
    }

    private AccessibilityNodeInfo findWebSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String all = (value(node.getText()) + " " + value(node.getContentDescription()) + " " + value(node.getHintText()) + " " + value(node.getViewIdResourceName())).toLowerCase(Locale.ROOT);
        if (node.isVisibleToUser() && (all.contains("send") || all.contains("gửi") || all.contains("submit"))) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo result = findWebSendButton(node.getChild(i)); if (result != null) return result; }
        return null;
    }

    private String extractAshnaWebAnswer(AccessibilityNodeInfo root, String question) {
        ArrayList<String> texts = new ArrayList<>(); collectWebTexts(root, texts);
        String best = "";
        for (String x : texts) {
            String t = x == null ? "" : x.trim();
            if (t.length() < 2 || t.length() > 3500) continue;
            if (t.equals(question) || t.equals(lastSent) || isWebUiText(t)) continue;
            if (t.length() > best.length()) best = t;
        }
        return best;
    }

    private void collectWebTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && !node.isEditable()) { String t = value(node.getText()).trim(); if (!t.isEmpty()) out.add(t); }
        for (int i = 0; i < node.getChildCount(); i++) collectWebTexts(node.getChild(i), out);
    }

    private boolean isWebUiText(String text) {
        String x = text.toLowerCase(Locale.ROOT).trim();
        return x.equals("send") || x.equals("gửi") || x.equals("new chat") || x.equals("chat") ||
                x.equals("settings") || x.equals("sign in") || x.equals("log in") || x.equals("try again") ||
                x.startsWith("model:") || x.startsWith("agent:");
    }
    private boolean sendMessage(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            safeRecycle(root);
            reportError("Không lấy được màn hình chat hiện tại.");
            return false;
        }
        String pkg = root.getPackageName().toString();
        if (!isSupportedChatPackage(pkg)) {
            safeRecycle(root);
            reportError("Ứng dụng chat hiện tại không được hỗ trợ: " + pkg);
            return false;
        }

        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) {
            safeRecycle(root);
            reportError("Không tìm thấy ô nhập tin nhắn.");
            return false;
        }
        replaceLastInput(input);
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) {
            safeRecycle(root);
            reportError("Không thể điền câu trả lời vào ô nhập.");
            return false;
        }

        boolean verified = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            AccessibilityNodeInfo verifyRoot = getRootInActiveWindow();
            AccessibilityNodeInfo verifyInput = findEditable(verifyRoot);
            if (verifyInput != null
                    && normalize(value(verifyInput.getText())).equals(normalize(text))) {
                verified = true;
                safeRecycle(verifyInput);
                safeRecycle(verifyRoot);
                break;
            }
            safeRecycle(verifyInput);
            safeRecycle(verifyRoot);
            try { Thread.sleep(80L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!verified) {
            safeRecycle(root);
            reportError("Đã điền nhưng không xác nhận được nội dung trong ô nhập.");
            return false;
        }

        AccessibilityNodeInfo freshRoot = getRootInActiveWindow();
        AccessibilityNodeInfo send = findSendButton(freshRoot);
        boolean clicked = send != null && clickNodeOrParent(send);
        safeRecycle(send);
        safeRecycle(freshRoot);
        safeRecycle(root);

        if (!clicked) {
            AccessibilityNodeInfo currentInput = getRootInActiveWindow();
            AccessibilityNodeInfo edit = findEditable(currentInput);
            if (edit != null) {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                clicked = edit.performAction(0x00400000);
            }
            safeRecycle(edit);
            safeRecycle(currentInput);
        }
        if (!clicked) reportError("Không tìm thấy hoặc không bấm được nút Gửi.");
        return clicked;
    }

    private void reportError(String message) {
        String msg = shortError(message == null ? "Lỗi không xác định." : message);
        postDebug("LỖI: " + msg);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(this, "AutoMessenger: " + msg,
                        android.widget.Toast.LENGTH_LONG).show());
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser()) {
            String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
            String all = (value(node.getText()) + " " + value(node.getContentDescription())
                    + " " + value(node.getHintText())).toLowerCase(Locale.ROOT);
            if (node.isEditable() || cls.contains("edittext")
                    || all.contains("type a message") || all.contains("write a message")
                    || all.contains("nhập tin nhắn") || all.contains("tin nhắn")
                    || all.equals("aa") || all.endsWith(" aa")) {
                if (node.isEditable() || cls.contains("edittext")) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findEditable(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String all = (value(node.getText()) + " " + value(node.getContentDescription()) + " "
                + value(node.getHintText()) + " " + value(node.getViewIdResourceName()))
                .toLowerCase(Locale.ROOT);
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);

        if (node.isVisibleToUser()
                && (all.contains("send") || all.contains("gửi") || all.contains("gui")
                || id.contains("message_send") || id.endsWith("_send")
                || (cls.contains("imagebutton") && id.contains("send")))) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findSendButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isVisibleToUser() && current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return false;
    }

    private String extractLatestMessage(AccessibilityNodeInfo root, AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String candidate = messageTextFromNode(source);
            safeRecycle(source);
            if (candidate != null) return candidate;
        }

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectMessageNodes(root, candidates);
        String best = "";
        for (AccessibilityNodeInfo n : candidates) {
            String candidate = messageTextFromNode(n);
            if (candidate != null && !candidate.equals(lastSent)
                    && !candidate.equals(lastIncoming)) best = candidate;
            safeRecycle(n);
        }
        return best;
    }

    private void collectMessageNodes(AccessibilityNodeInfo node,
                                      List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        String text = value(node.getText()).trim();
        if (node.isVisibleToUser() && !text.isEmpty() && !node.isEditable()
                && isMessageLikeNode(node) && !isLikelyOutgoing(node)) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectMessageNodes(node.getChild(i), out);
    }

    private String messageTextFromNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || node.isEditable()) return null;
        String text = value(node.getText()).trim();
        if (text.isEmpty() || text.length() > 4000 || isUiText(text)) return null;
        if (!isMessageLikeNode(node) || isLikelyOutgoing(node)) return null;
        return text;
    }

    private boolean isMessageLikeNode(AccessibilityNodeInfo node) {
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
        String desc = value(node.getContentDescription()).toLowerCase(Locale.ROOT);
        if (id.contains("message") || id.contains("messenger") || desc.contains("message")) return true;
        if (cls.contains("textview")) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            return r.top > 120 && r.bottom > r.top;
        }
        return false;
    }

    private boolean isLikelyOutgoing(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.right <= r.left) return false;
        int center = (r.left + r.right) / 2;
        return center > getResources().getDisplayMetrics().widthPixels * 0.58f;
    }

    private boolean isUiText(String s) {
        String x = s.toLowerCase(Locale.ROOT).trim();
        return x.equals("send") || x.equals("gửi") || x.equals("gui")
                || x.equals("message") || x.equals("messenger") || x.equals("aa")
                || x.equals("more") || x.equals("thêm")
                || x.contains("type a message") || x.contains("write a message")
                || x.contains("nhập tin nhắn") || x.contains("tin nhắn");
    }

    private void replaceLastInput(AccessibilityNodeInfo input) {
        if (lastInput != null && lastInput != input) safeRecycle(lastInput);
        lastInput = input;
    }

    private String value(CharSequence s) { return s == null ? "" : s.toString(); }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace("\u00a0", " ").trim().replaceAll("\\s+", " ");
    }

    private String shortError(String message) {
        String x = message == null ? "Lỗi không xác định" : message.trim();
        return x.length() > 180 ? x.substring(0, 180) : x;
    }

    private void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) try { node.recycle(); } catch (Exception ignored) {}
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        instance = null;
        worker.shutdownNow();
        debounceScheduler.shutdownNow();
        safeRecycle(lastInput);
        lastInput = null;
        if (ashnaHiddenWebView != null) {
            try {
                if (ashnaWebWindowManager != null) ashnaWebWindowManager.removeView(ashnaHiddenWebView);
            } catch (Exception ignored) {}
            try { ashnaHiddenWebView.stopLoading(); } catch (Exception ignored) {}
            try { ashnaHiddenWebView.destroy(); } catch (Exception ignored) {}
            ashnaHiddenWebView = null;
        }
        ashnaWebCallback = null;
        super.onDestroy();
    }



    public interface ReplyCallback {
        void onReply(String question, String answer, String error);
    }

    /** Manual bubble request: uses the direct AshnaAI API. */
    public void generateManualReply(final String question, final ReplyCallback callback) {
        if (callback == null || question == null || question.trim().isEmpty()) return;
        worker.execute(() -> {
            String answer = null;
            String error = null;
            try {
                android.content.SharedPreferences p =
                        getSharedPreferences("AutoMessenger", 0);
                String prompt = p.getString("prompt",
                        "Trả lời tự nhiên bằng tiếng Việt, ngắn gọn, thân thiện. Chỉ trả về nội dung cần gửi.");
                answer = AiClient.reply(p.getString("ashna_api_key", ""), p.getString("ashna_model", "gpt-4o-mini"), prompt, question.trim());
                AutoMessengerService.setLastConversation(question.trim(), answer);
            } catch (Exception e) {
                error = shortError(e.getMessage() == null ? "Lỗi AshnaAI không xác định" : e.getMessage());
            }
            final String a = answer;
            final String err = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> callback.onReply(question.trim(), a, err));
        });
    }

    public boolean putTextInMessenger(String text) { return sendMessage(text); }

    public boolean putTextInComposerOnly(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isSupportedChatPackage(value(root.getPackageName()))) {
            safeRecycle(root);
            return false;
        }
        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) {
            safeRecycle(root);
            return false;
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok) replaceLastInput(input); else safeRecycle(input);
        safeRecycle(root);
        return ok;
    }
}
