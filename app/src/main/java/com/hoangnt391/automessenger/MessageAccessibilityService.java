package com.hoangnt391.automessenger;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageAccessibilityService extends AccessibilityService {
    private static MessageAccessibilityService instance;
    private AccessibilityNodeInfo lastInput;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean replying = false;
    private String lastIncoming = "";
    private String lastSent = "";
    private long lastReplyAt = 0L;
    private static final String DEBUG_CHANNEL = "automessenger_debug";
    private int debugNotificationId = 4102;

    private void ensureDebugChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(DEBUG_CHANNEL) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(DEBUG_CHANNEL, "AutoMessenger - Nhật ký", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }

    private void postDebug(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ensureDebugChannel();
                android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                        ? new android.app.Notification.Builder(this, DEBUG_CHANNEL)
                        : new android.app.Notification.Builder(this);
                b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("AutoMessenger • Trạng thái").setContentText(message).setAutoCancel(true);
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(debugNotificationId++, b.build());
            } catch (Exception ignored) {}
        });
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        ensureDebugChannel();
        postDebug("Trợ năng đã kết nối. Đang chờ tin nhắn mới.");
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static MessageAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isChatAppActive() {
        MessageAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        // Only inspect supported chat apps. This prevents the OCR/accessibility
        // engine from treating unrelated apps as incoming conversations.
        return pkg.equals("com.facebook.orca")
                || pkg.equals("com.zing.zalo")
                || pkg.equals("com.whatsapp")
                || pkg.equals("org.telegram.messenger");
    }

    /** Backward-compatible name for older callers. */
    public static boolean isMessengerActive() {
        return isChatAppActive();
    }

    public static void handleScreenMessage(String text) {
        MessageAccessibilityService s = instance;
        if (s == null || text == null) return;
        s.handleDetectedIncoming(text.trim());
    }

    private void handleDetectedIncoming(String incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;
        if (replying) return;
        if (incoming.equals(lastSent)) return;
        if (incoming.equals(lastIncoming)) return;
        if (System.currentTimeMillis() - lastReplyAt < 1500L) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);
        lastIncoming = incoming;
        postDebug("Đã phát hiện tin nhắn mới. Đang xử lý...");
        generateAndSend(incoming);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // Always refresh the current Messenger composer. This is important because
        // Messenger recreates the composer node when entering/leaving a chat.
        AccessibilityNodeInfo input = findEditable(root);
        if (input != null && input.isVisibleToUser()) {
            replaceLastInput(input);
        }

        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;
        if (replying) return;

        String incoming = extractLatestMessage(root, event);
        if (incoming == null || incoming.trim().isEmpty()) return;
        incoming = incoming.trim();

        if (incoming.equals(lastSent) || incoming.equals(lastIncoming)) return;
        if (System.currentTimeMillis() - lastReplyAt < 1500L) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);

        lastIncoming = incoming;
        generateAndSend(incoming);
    }

    private void generateAndSend(final String incoming) {
        replying = true;
        postDebug("Đang gửi nội dung sang ChatGPT...");
        worker.execute(() -> {
            try {
                android.content.SharedPreferences p =
                        getSharedPreferences("AutoMessenger", 0);
                String key = p.getString("api_key", "");
                String model = p.getString("model", "gemini-3.5-flash-lite");
                String prompt = p.getString("prompt",
                        "Bạn đang tạo NỘI DUNG TIN NHẮN để ứng dụng tự động gửi cho người khác. " +
                        "Chỉ trả về đúng nội dung tin nhắn cần gửi. " +
                        "Không giải thích, không nói bạn là AI, không nói bạn không thể thao tác, " +
                        "không nhắc đến giao diện, ứng dụng, API hay công cụ. " +
                        "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không markdown.");

                String reply = AiClient.reply(key, model, prompt, incoming);
                postDebug("ChatGPT đã trả lời. Đang chuẩn bị gửi...");
                if (reply != null && !reply.trim().isEmpty()) {
                    AutoMessengerService.setLastConversation(incoming, reply.trim());
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (sendMessage(reply.trim())) {
                            postDebug("Đã gửi câu trả lời thành công.");
                            lastSent = reply.trim();
                            lastReplyAt = System.currentTimeMillis();
                        }
                    });
                }
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "Lỗi ChatGPT không xác định" : e.getMessage();
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

    /** Sends the bubble question to the installed ChatGPT app and returns its real reply. */
    public void generateManualReply(final String question, final ManualReplyCallback callback) {
        final String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return;
        worker.execute(() -> {
            try {
                android.content.SharedPreferences p = getSharedPreferences("AutoMessenger", 0);
                String prompt = p.getString("prompt",
                        "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. " +
                        "Chỉ trả về nội dung câu trả lời, không giải thích, không markdown.");
                final String answer = requestChatGptReply(prompt, q).trim();
                if (answer.isEmpty()) throw new Exception("ChatGPT trả về câu trả lời trống.");
                AutoMessengerService.setLastConversation(q, answer);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        callback.onResult(q, answer, null));
            } catch (Exception e) {
                final String message = e.getMessage() == null
                        ? "Không kết nối được với ChatGPT." : shortError(e.getMessage());
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        callback.onResult(q, "", message));
            }
        });
    }

    public interface ManualReplyCallback {
        void onResult(String question, String answer, String error);
    }

    private String shortError(String message) {
        String x = message == null ? "" : message.replace("\\n", " ").trim();
        if (x.length() > 180) x = x.substring(0, 180) + "...";
        return x.isEmpty() ? "kiểm tra API key, model và quyền Trợ năng." : x;
    }


    /**
     * Open the official ChatGPT Android app on the temporary-chat URL, enter the
     * request, press Send, read the assistant answer from the accessibility tree,
     * then return to the chat app that was active before ChatGPT was opened.
     *
     * No screenshots are captured for this path and no image/bitmap is stored.
     */
    public String requestChatGptReply(String instructions, String incoming) throws Exception {
        final String question = buildChatGptQuestion(instructions, incoming);
        if (question.trim().isEmpty()) throw new IllegalArgumentException("Câu hỏi trống.");

        AccessibilityNodeInfo before = getRootInActiveWindow();
        final String returnPackage = before == null || before.getPackageName() == null
                ? "" : before.getPackageName().toString();
        safeRecycle(before);

        postDebug("Đang mở ChatGPT...");
        openInstalledChatGpt();
        AccessibilityNodeInfo chatRoot = waitForChatGptRoot(20000L);
        safeRecycle(chatRoot);
        if (chatRoot == null) {
            throw new Exception("Không mở được ứng dụng ChatGPT. Hãy cài ChatGPT và bật Trợ năng cho AutoMessenger.");
        }

        AccessibilityNodeInfo composer = waitForChatGptComposer(12000L);
        if (composer == null) throw new Exception("Không tìm thấy ô nhập ChatGPT.");

        postDebug("Đã mở ChatGPT. Đang nhập câu hỏi...");
        if (!setNodeTextAndVerify(composer, question, 3000L)) {
            safeRecycle(composer);
            throw new Exception("ChatGPT không nhận được câu hỏi. Ô nhập chưa nhận được nội dung.");
        }

        AccessibilityNodeInfo fresh = getRootInActiveWindow();
        AccessibilityNodeInfo send = findChatGptSendButton(fresh, composer);
        safeRecycle(fresh);
        if (send == null || !clickNodeOrParent(send)) {
            safeRecycle(send);
            safeRecycle(composer);
            throw new Exception("Không tìm thấy nút Gửi của ChatGPT.");
        }
        safeRecycle(send);

        postDebug("Đã gửi câu hỏi. Đang chờ câu trả lời...");
        String answer = waitForChatGptAnswer(question, 90000L);
        safeRecycle(composer);
        if (answer == null || answer.trim().isEmpty()) {
            throw new Exception("Đã gửi câu hỏi nhưng chưa đọc được câu trả lời từ ChatGPT.");
        }

        if (!returnPackage.isEmpty() && !returnPackage.equals("com.openai.chatgpt")) {
            final String pkg = returnPackage;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> returnToPackage(pkg), 200L);
        }
        postDebug("Đã đọc được câu trả lời từ ChatGPT.");
        return answer.trim();
    }

    private void openInstalledChatGpt() throws Exception {
        final android.content.pm.PackageManager pm = getPackageManager();
        try { pm.getPackageInfo("com.openai.chatgpt", 0); }
        catch (Exception e) { throw new Exception("Chưa cài ứng dụng ChatGPT (com.openai.chatgpt)."); }

        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Exception> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        main.post(() -> {
            try {
                android.content.Intent launch = pm.getLaunchIntentForPackage("com.openai.chatgpt");
                if (launch == null) throw new Exception("Không tìm thấy màn hình mở ChatGPT.");
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(launch);
                done.countDown();

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        android.content.Intent url = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://chatgpt.com/?temporary-chat=true"));
                        url.setPackage("com.openai.chatgpt");
                        url.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(url);
                    } catch (Exception ignored) {}
                }, 350L);
            } catch (Exception e) {
                error.set(e);
                done.countDown();
            }
        });
        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw new Exception("Hết thời gian mở ứng dụng ChatGPT.");
        if (error.get() != null) throw error.get();
    }

    private AccessibilityNodeInfo waitForChatGptComposer(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isChatGptWindow(root)) {
                AccessibilityNodeInfo composer = findChatGptComposer(root);
                if (composer != null) {
                    safeRecycle(root);
                    return composer;
                }
            }
            safeRecycle(root);
            Thread.sleep(200L);
        }
        return null;
    }

    private boolean setNodeTextAndVerify(AccessibilityNodeInfo node, String text, long timeoutMs)
            throws InterruptedException {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) {
                Thread.sleep(120L);
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo current = findChatGptComposer(root);
                String actual = current == null ? "" : normalize(value(current.getText()));
                safeRecycle(current);
                safeRecycle(root);
                if (actual.equals(normalize(text))) return true;
            }
            Thread.sleep(150L);
        }
        return false;
    }

    private String buildChatGptQuestion(String instructions, String incoming) {
        String p = instructions == null ? "" : instructions.trim();
        String q = incoming == null ? "" : incoming.trim();
        if (p.isEmpty()) return q;
        if (q.isEmpty()) return p;
        return p + "\n\nTin nhắn/câu hỏi cần xử lý:\n" + q;
    }

    private AccessibilityNodeInfo waitForChatGptRoot(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isChatGptWindow(root)) return root;
            Thread.sleep(250L);
        }
        return null;
    }

    private boolean isChatGptWindow(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        return "com.openai.chatgpt".equals(root.getPackageName().toString());
    }

    private AccessibilityNodeInfo findChatGptComposer(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectEditableCandidates(root, candidates);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        Rect br = new Rect();
        for (AccessibilityNodeInfo n : candidates) {
            if (!n.isVisibleToUser()) { safeRecycle(n); continue; }
            n.getBoundsInScreen(br);
            int score = br.bottom * 4 + br.right;
            String hint = (value(n.getHintText()) + " " + value(n.getContentDescription())).toLowerCase(Locale.ROOT);
            String id = value(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            if (hint.contains("message") || hint.contains("ask") || hint.contains("prompt") ||
                    hint.contains("chat") || id.contains("composer") || id.contains("message")) score += 100000;
            if (br.top < getResources().getDisplayMetrics().heightPixels / 3) score -= 100000;
            if (score > bestScore) {
                safeRecycle(best); best = n; bestScore = score;
            } else safeRecycle(n);
        }
        return best;
    }

    private void collectEditableCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isVisibleToUser()) {
            String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
            if (node.isEditable() || cls.contains("edittext") || cls.contains("textinput")) {
                out.add(AccessibilityNodeInfo.obtain(node));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) collectEditableCandidates(node.getChild(i), out);
    }

    private AccessibilityNodeInfo findChatGptSendButton(AccessibilityNodeInfo root, AccessibilityNodeInfo composer) {
        if (root == null) return null;
        Rect cr = new Rect();
        if (composer != null) composer.getBoundsInScreen(cr);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectClickableNodes(root, nodes);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) { safeRecycle(n); continue; }
            String all = (value(n.getText()) + " " + value(n.getContentDescription()) + " " +
                    value(n.getViewIdResourceName())).toLowerCase(Locale.ROOT);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            int score = 0;
            if (all.contains("send") || all.contains("submit") || all.contains("gửi") || all.contains("send_message")) score += 10000;
            if (all.contains("stop") || all.contains("cancel")) score -= 20000;
            if (r.bottom >= cr.top - 80 && r.top <= cr.bottom + 80) score += 3000;
            if (r.right > screenW * 0.65f) score += 2000;
            score -= Math.abs(r.centerY() - cr.centerY());
            if (score > bestScore) { safeRecycle(best); best=n; bestScore=score; }
            else safeRecycle(n);
        }
        return bestScore > 0 ? best : null;
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.isClickable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i=0;i<node.getChildCount();i++) collectClickableNodes(node.getChild(i), out);
    }

    private void collectVisibleTexts(AccessibilityNodeInfo node, java.util.Set<String> out) {
        if (node == null) return;
        if (node.isVisibleToUser()) {
            String t = normalize(value(node.getText()));
            if (!t.isEmpty() && t.length() <= 12000) out.add(t);
            String d = normalize(value(node.getContentDescription()));
            if (!d.isEmpty() && d.length() <= 500) out.add(d);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectVisibleTexts(node.getChild(i), out);
        }
    }

    private String waitForChatGptAnswer(String question, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        String last = "";
        int stable = 0;
        boolean sentMessageObserved = false;

        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isChatGptWindow(root)) {
                AccessibilityNodeInfo composer = findChatGptComposer(root);
                String composerText = composer == null ? "" : normalize(value(composer.getText()));
                safeRecycle(composer);
                if (composerText.isEmpty()) sentMessageObserved = true;

                List<String> texts = new ArrayList<>();
                collectAnswerTexts(root, texts);
                for (String raw : texts) {
                    String t = normalize(raw);
                    if (t.isEmpty() || t.length() < 2 || t.equals(normalize(question))) continue;
                    if (looksLikeChatGptUi(t) || looksLikeChatGptProgress(t)) continue;
                    String cleaned = stripQuestion(t, question);
                    if (cleaned.length() < 2) continue;
                    if (cleaned.equals(last)) stable++;
                    else if (sentMessageObserved) { last = cleaned; stable = 1; }
                }
                if (sentMessageObserved && stable >= 3 && !last.isEmpty()) {
                    return last;
                }
            }
            safeRecycle(root);
            Thread.sleep(350L);
        }
        return "";
    }

    private String extractChatGptAnswer(AccessibilityNodeInfo root, String question,
                                         java.util.Set<String> baseline) {
        List<String> texts = new ArrayList<>();
        collectAnswerTexts(root, texts);
        String best = "";
        for (String raw : texts) {
            String t = normalize(raw);
            if (t.isEmpty() || t.equals(normalize(question)) || looksLikeChatGptUi(t) || looksLikeChatGptProgress(t)) continue;
            String cleaned = stripQuestion(t, question);
            if (cleaned.length() > best.length()) best = cleaned;
        }
        return best.trim();
    }

    private void collectAnswerTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && !node.isEditable()) {
            String t = normalize(value(node.getText()));
            if (!t.isEmpty() && t.length() <= 12000) out.add(t);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectAnswerTexts(node.getChild(i), out);
    }

    private void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) try { node.recycle(); } catch (Exception ignored) {}
    }

    private String stripQuestion(String text, String question) {
        String t = text == null ? "" : text.trim();
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return t;
        int idx = t.lastIndexOf(q);
        if (idx >= 0 && idx + q.length() < t.length()) {
            String after = t.substring(idx + q.length()).trim();
            if (!after.isEmpty()) return after;
        }
        return t;
    }

    private boolean looksLikeChatGptProgress(String text) {
        String x = text.toLowerCase(Locale.ROOT);
        return x.equals("thinking") || x.equals("generating") ||
                x.contains("stop generating") || x.contains("đang suy nghĩ") ||
                x.contains("đang tạo") || x.equals("cancel");
    }

    private boolean looksLikeChatGptUi(String text) {
        String x = text.toLowerCase(Locale.ROOT).trim();
        return x.equals("chatgpt") || x.equals("send") || x.equals("gửi") ||
                x.equals("new chat") || x.equals("temporary chat") ||
                x.equals("copy") || x.equals("sao chép") ||
                x.equals("regenerate") || x.equals("read aloud") ||
                x.equals("like") || x.equals("dislike");
    }

    private void returnToPackage(String packageName) {
        try {
            android.content.Intent back = getPackageManager().getLaunchIntentForPackage(packageName);
            if (back != null) {
                back.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(back);
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } catch (Exception ignored) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void questionCleanup(AccessibilityNodeInfo composer, AccessibilityNodeInfo root) {
        try { if (composer != null) composer.recycle(); } catch (Exception ignored) {}
        try { if (root != null) root.recycle(); } catch (Exception ignored) {}
    }

    private boolean sendMessage(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) return false;
        replaceLastInput(input);

        // Focus the current chat app composer before changing its text.
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        if (!set) {
            // A few Messenger builds expose the composer as an EditText but do not
            // report isEditable() correctly. Try the same node once more after focus.
            set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        if (!set) return false;

        // Verify the composer really contains the requested reply before sending.
        // Some chat apps accept ACTION_SET_TEXT but update their UI asynchronously.
        boolean verified = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            AccessibilityNodeInfo verifyRoot = getRootInActiveWindow();
            AccessibilityNodeInfo verifyInput = findEditable(verifyRoot);
            if (verifyInput != null && normalize(value(verifyInput.getText()))
                    .equals(normalize(text))) {
                verified = true;
                break;
            }
            if (verifyInput != null) {
                verifyInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                verifyInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            try { Thread.sleep(80L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!verified) {
            android.widget.Toast.makeText(this, "Không xác nhận được ô nhập tin nhắn.", android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }

        // After verifying the reply is in the composer, tap the app's Send button.
        AccessibilityNodeInfo freshRoot = getRootInActiveWindow();
        if (freshRoot == null) return false;

        AccessibilityNodeInfo send = findSendButton(freshRoot);
        if (send == null) {
            android.widget.Toast.makeText(this, "Không tìm thấy nút Gửi của ứng dụng chat.", android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        boolean clicked = clickNodeOrParent(send);

        // Fallback for chat apps whose send icon is exposed to Accessibility
        // but does not dispatch ACTION_CLICK. Try the composer IME send action.
        if (!clicked) {
            AccessibilityNodeInfo currentInput = findEditable(getRootInActiveWindow());
            if (currentInput != null) {
                currentInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                clicked = currentInput.performAction(0x00400000 /* ACTION_IME_ENTER (API 30) */);
            }
        }
        if (!clicked) {
            android.widget.Toast.makeText(this, "Đã dán nhưng không gửi được: ứng dụng không nhận thao tác Gửi.", android.widget.Toast.LENGTH_LONG).show();
        }
        return clicked;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;

        if (node.isVisibleToUser()) {
            String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            String hint = value(node.getHintText());
            String all = (text + " " + desc + " " + hint).toLowerCase(Locale.ROOT);

            if (node.isEditable() ||
                    cls.contains("edittext") ||
                    all.contains("type a message") ||
                    all.contains("write a message") ||
                    all.contains("nhập tin nhắn") ||
                    all.contains("tin nhắn") || all.contains("nhắn tin") ||
                    all.equals("aa") ||
                    all.endsWith(" aa")) {
                if (cls.contains("edittext") || node.isEditable()) return node;
                if (all.contains("message") || all.contains("nhập") || all.contains(" aa")) {
                    AccessibilityNodeInfo child = findEditable(node);
                    if (child != null && child != node) return child;
                }
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

        String text = value(node.getText());
        String desc = value(node.getContentDescription());
        String hint = value(node.getHintText());
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
        String combined = (text + " " + desc + " " + hint + " " + id)
                .toLowerCase(Locale.ROOT);

        boolean looksLikeSend =
                combined.contains("send") ||
                combined.contains("gửi") ||
                combined.contains("gui") ||
                id.contains("message_send") ||
                id.endsWith("_send");

        if (looksLikeSend && node.isVisibleToUser()) return node;

        // Some chat apps expose only an ImageButton with no
        // descriptive text but an ID containing "send".
        if (node.isVisibleToUser() && cls.contains("imagebutton") && id.contains("send")) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findSendButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isVisibleToUser() && current.isClickable()) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private String extractLatestMessage(AccessibilityNodeInfo root, AccessibilityEvent event) {
        List<AccessibilityNodeInfo> sources = new ArrayList<>();
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) sources.add(source);

        // Prefer the event source: on Messenger this is normally the message
        // TextView that changed, which is much safer than scanning the whole page.
        for (AccessibilityNodeInfo n : sources) {
            String candidate = messageTextFromNode(n);
            if (candidate != null) return candidate;
        }

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectMessageNodes(root, candidates);

        String best = "";
        for (AccessibilityNodeInfo n : candidates) {
            String candidate = messageTextFromNode(n);
            if (candidate == null) continue;
            if (candidate.equals(lastSent)) continue;
            if (candidate.equals(lastIncoming)) continue;
            best = candidate;
        }
        return best;
    }

    private void collectMessageNodes(AccessibilityNodeInfo node,
                                      List<AccessibilityNodeInfo> out) {
        if (node == null) return;

        String text = value(node.getText()).trim();
        if (node.isVisibleToUser() && !text.isEmpty() &&
                !node.isEditable() && isMessageLikeNode(node) &&
                !isLikelyOutgoing(node)) {
            out.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectMessageNodes(node.getChild(i), out);
        }
    }

    private String messageTextFromNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || node.isEditable()) return null;

        String text = value(node.getText()).trim();
        if (text.isEmpty() || text.length() > 4000) return null;
        if (isUiText(text)) return null;
        if (!isMessageLikeNode(node)) return null;
        if (isLikelyOutgoing(node)) return null;
        return text;
    }

    private boolean isMessageLikeNode(AccessibilityNodeInfo node) {
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
        String desc = value(node.getContentDescription()).toLowerCase(Locale.ROOT);

        if (id.contains("message") || id.contains("messenger") ||
                desc.contains("message")) return true;

        // Many chat apps expose chat text as TextView without a useful ID.
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

        // In many chat layouts, outgoing bubbles are normally on the right and incoming
        // bubbles are on the left. Use a conservative threshold to avoid replying
        // to our own messages.
        int center = (r.left + r.right) / 2;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return center > (screenWidth * 0.58f);
    }

    private boolean isUiText(String s) {
        String x = s.toLowerCase(Locale.ROOT).trim();
        return x.equals("send") || x.equals("gửi") || x.equals("gui") ||
                x.equals("message") || x.equals("messenger") ||
                x.equals("aa") || x.equals("more") || x.equals("thêm") ||
                x.contains("type a message") || x.contains("write a message") ||
                x.contains("nhập tin nhắn") || x.contains("tin nhắn") || x.contains("nhắn tin");
    }

    private void replaceLastInput(AccessibilityNodeInfo input) {
        if (lastInput != null && lastInput != input) {
            try { lastInput.recycle(); } catch (Exception ignored) {}
        }
        lastInput = input;
    }

    private String value(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace("\u00a0", " ").trim().replaceAll("\\s+", " ");
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        instance = null;
        worker.shutdownNow();
        if (lastInput != null) {
            try { lastInput.recycle(); } catch (Exception ignored) {}
            lastInput = null;
        }
        super.onDestroy();
    }

    public boolean putTextInMessenger(String text) {
        return sendMessage(text);
    }

    /** Put text into the current chat composer without pressing Send. */
    public boolean putTextInComposerOnly(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) return false;
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok) replaceLastInput(input);
        return ok;
    }
}