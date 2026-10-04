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

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
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
        if (incoming.equals(lastSent) || incoming.equals(lastIncoming)) return;
        if (System.currentTimeMillis() - lastReplyAt < 1500L) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);
        lastIncoming = incoming;
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
                if (reply != null && !reply.trim().isEmpty()) {
                    AutoMessengerService.setLastConversation(incoming, reply.trim());
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (sendMessage(reply.trim())) {
                            lastSent = reply.trim();
                            lastReplyAt = System.currentTimeMillis();
                        }
                    });
                }
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "Lỗi Gemini không xác định" : e.getMessage();
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

    /** Generate an answer for the question typed in the AI bubble, without sending it to the chat app. */
    public void generateManualReply(final String question, final ManualReplyCallback callback) {
        final String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return;

        worker.execute(() -> {
            try {
                android.content.SharedPreferences p =
                        getSharedPreferences("AutoMessenger", 0);
                String key = p.getString("api_key", "");
                String model = p.getString("model", "gemini-3.5-flash-lite");
                String prompt = p.getString("prompt",
                        "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. " +
                        "Chỉ trả về nội dung câu trả lời, không giải thích, không markdown.");

                String reply = AiClient.reply(key, model, prompt, q);
                final String answer = reply == null ? "" : reply.trim();
                if (!answer.isEmpty()) {
                    AutoMessengerService.setLastConversation(q, answer);
                }

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        callback.onResult(q, answer, null));
            } catch (Exception e) {
                final String message = e.getMessage() == null
                        ? "Lỗi Gemini không xác định" : shortError(e.getMessage());
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

        final AccessibilityNodeInfo before = getRootInActiveWindow();
        final String returnPackage = before == null || before.getPackageName() == null
                ? "" : before.getPackageName().toString();

        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> openError =
                new java.util.concurrent.atomic.AtomicReference<>("");

        main.post(() -> {
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://chatgpt.com/?temporary-chat=true"));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);

                boolean hasChatGpt = false;
                try {
                    getPackageManager().getPackageInfo("com.openai.chatgpt", 0);
                    hasChatGpt = true;
                } catch (Exception ignored) {}

                if (hasChatGpt) intent.setPackage("com.openai.chatgpt");
                startActivity(intent);
            } catch (Exception e) {
                openError.set(e.getMessage() == null ? "Không mở được ChatGPT." : e.getMessage());
            } finally {
                opened.countDown();
            }
        });
        opened.await(5, java.util.concurrent.TimeUnit.SECONDS);
        if (!openError.get().isEmpty()) throw new Exception(openError.get());

        AccessibilityNodeInfo chatRoot = waitForChatGptRoot(15000L);
        if (chatRoot == null) {
            throw new Exception("Không đọc được giao diện ChatGPT. Hãy mở ChatGPT và cấp Trợ năng cho AutoMessenger.");
        }

        final java.util.Set<String> baseline = new java.util.HashSet<>();
        collectVisibleTexts(chatRoot, baseline);

        AccessibilityNodeInfo composer = findChatGptComposer(chatRoot);
        if (composer == null) throw new Exception("Không tìm thấy ô nhập ChatGPT.");

        composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, question);
        boolean set = composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        if (!set) {
            // A clipboard is deliberately NOT used here: it would expose the prompt
            // to the system clipboard. Accessibility ACTION_SET_TEXT is the preferred path.
            throw new Exception("ChatGPT không nhận được nội dung vào ô nhập.");
        }

        AccessibilityNodeInfo verify = findChatGptComposer(getRootInActiveWindow());
        if (verify == null || !normalize(value(verify.getText())).equals(normalize(question))) {
            throw new Exception("Không xác nhận được nội dung trong ô ChatGPT.");
        }

        AccessibilityNodeInfo send = findChatGptSendButton(getRootInActiveWindow());
        if (send == null || !clickNodeOrParent(send)) {
            throw new Exception("Không tìm thấy nút Gửi của ChatGPT.");
        }

        String answer = waitForChatGptAnswer(question, baseline, 60000L);
        if (answer == null || answer.trim().isEmpty()) {
            throw new Exception("ChatGPT chưa trả về câu trả lời.");
        }

        if (!returnPackage.isEmpty() && !returnPackage.equals("com.openai.chatgpt")) {
            main.postDelayed(() -> returnToPackage(returnPackage), 150L);
        }

        // Clear short-lived local references before returning. Nothing is persisted.
        questionCleanup(composer, chatRoot);
        return answer.trim();
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

    private AccessibilityNodeInfo findChatGptComposer(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser()) {
            String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
            String all = (value(node.getText()) + " " +
                    value(node.getContentDescription()) + " " +
                    value(node.getHintText())).toLowerCase(Locale.ROOT);
            if (node.isEditable() || cls.contains("edittext")) return node;
            if ((all.contains("message") || all.contains("ask") ||
                    all.contains("prompt") || all.contains("chat")) &&
                    (node.isFocusable() || node.isClickable())) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = findChatGptComposer(node.getChild(i));
                    if (child != null) return child;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findChatGptComposer(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findChatGptSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String all = (value(node.getText()) + " " +
                value(node.getContentDescription()) + " " +
                value(node.getViewIdResourceName())).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);

        boolean send = (all.contains("send") || all.contains("gửi") ||
                all.contains("submit")) &&
                !all.contains("stop") && !all.contains("cancel");
        if (node.isVisibleToUser() && send &&
                (node.isClickable() || cls.contains("button") || cls.contains("imagebutton"))) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findChatGptSendButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
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

    private String waitForChatGptAnswer(String question, java.util.Set<String> baseline,
                                        long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        String last = "";
        int stable = 0;

        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isChatGptWindow(root)) {
                String candidate = extractChatGptAnswer(root, question, baseline);
                if (!candidate.isEmpty()) {
                    if (candidate.equals(last)) stable++;
                    else {
                        last = candidate;
                        stable = 1;
                    }
                    // Require several identical accessibility snapshots so we don't
                    // return a partial streaming answer.
                    if (stable >= 3 && !looksLikeChatGptProgress(candidate)) {
                        return candidate;
                    }
                }
            }
            Thread.sleep(400L);
        }
        return "";
    }

    private String extractChatGptAnswer(AccessibilityNodeInfo root, String question,
                                         java.util.Set<String> baseline) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        collectAnswerTexts(root, texts);

        String best = "";
        for (String raw : texts) {
            String t = normalize(raw);
            if (t.isEmpty() || t.length() < 2 || t.equals(normalize(question))) continue;
            if (looksLikeChatGptUi(t) || looksLikeChatGptProgress(t)) continue;

            String cleaned = stripQuestion(t, question);
            if (cleaned.length() > best.length()) best = cleaned;
        }

        // If the answer is exposed as a single combined conversation node, strip the
        // newly submitted question and keep the text after it.
        return best.trim();
    }

    private void collectAnswerTexts(AccessibilityNodeInfo node, java.util.List<String> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && !node.isEditable()) {
            String t = normalize(value(node.getText()));
            if (!t.isEmpty() && t.length() <= 12000) out.add(t);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectAnswerTexts(node.getChild(i), out);
        }
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
