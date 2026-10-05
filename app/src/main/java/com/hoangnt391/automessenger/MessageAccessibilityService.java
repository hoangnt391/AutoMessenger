package com.hoangnt391.automessenger;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MessageAccessibilityService extends AccessibilityService {
    private static final String POE_PACKAGE = "com.poe.android";
    private static final String DEBUG_CHANNEL = "automessenger_debug";
    private static MessageAccessibilityService instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AccessibilityNodeInfo lastInput;
    private volatile boolean replying = false;
    private volatile boolean poeBusy = false;
    private volatile boolean poeStarted = false;
    private String lastIncoming = "";
    private String lastSent = "";
    private long lastReplyAt = 0L;

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
                        .setContentTitle("AutoMessenger • Poe")
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
        if (replying || poeBusy) return;
        if (incoming.equals(lastSent) || incoming.equals(lastIncoming)) return;
        if (System.currentTimeMillis() - lastReplyAt < 1500L) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);
        lastIncoming = incoming;
        generateAndSend(incoming);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;

        // Never inspect Settings, Poe, launcher, permission screens, etc.
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
        if (replying || poeBusy) {
            safeRecycle(root);
            return;
        }

        String incoming = extractLatestMessage(root, event);
        safeRecycle(root);
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
        postDebug("Đang xử lý bằng Poe...");
        worker.execute(() -> {
            try {
                android.content.SharedPreferences p =
                        getSharedPreferences("AutoMessenger", 0);
                String prompt = p.getString("prompt",
                        "Bạn đang tạo NỘI DUNG TIN NHẮN để ứng dụng tự động gửi cho người khác. " +
                        "Chỉ trả về đúng nội dung tin nhắn cần gửi. Không giải thích, không nói bạn là AI. " +
                        "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không markdown.");

                String reply = AiClient.reply("", "poe", prompt, incoming);
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
                String message = e.getMessage() == null ? "Lỗi Poe không xác định" : e.getMessage();
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

    /**
     * Poe is opened programmatically only when needed. The same Poe activity is
     * brought to the foreground with REORDER_TO_FRONT; no new Poe activity/session
     * is spawned for every message.
     */
    public String requestPoeReply(String instructions, String incoming) throws Exception {
        if (poeBusy) throw new Exception("Poe đang xử lý yêu cầu trước.");
        poeBusy = true;
        try {
            String question = buildPoeQuestion(instructions, incoming);
            if (question.isEmpty()) throw new IllegalArgumentException("Câu hỏi trống.");

            String returnPackage = currentPackage();
            ensurePoeForeground();

            AccessibilityNodeInfo composer = waitForPoeComposer(15000L);
            if (composer == null) throw new Exception("Không tìm thấy ô nhập Poe.");

            try {
                if (!setNodeTextAndVerifyPoe(composer, question, 4000L)) {
                    throw new Exception("Poe không nhận được câu hỏi.");
                }

                AccessibilityNodeInfo fresh = getRootInActiveWindow();
                AccessibilityNodeInfo send = findPoeSendButton(fresh, composer);
                safeRecycle(fresh);

                if (send == null || !clickNodeOrParent(send)) {
                    safeRecycle(send);
                    throw new Exception("Không tìm thấy nút Gửi của Poe.");
                }
                safeRecycle(send);

                postDebug("Đã gửi sang Poe. Đang chờ câu trả lời...");
                String answer = waitForPoeAnswer(question, 90000L);
                if (answer == null || answer.trim().isEmpty()) {
                    throw new Exception("Poe chưa trả về câu trả lời.");
                }

                if (!returnPackage.isEmpty() && isSupportedChatPackage(returnPackage)) {
                    final String pkg = returnPackage;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> returnToPackage(pkg), 150L);
                }
                return answer.trim();
            } finally {
                safeRecycle(composer);
            }
        } finally {
            poeBusy = false;
        }
    }

    private String currentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null
                ? "" : root.getPackageName().toString();
        safeRecycle(root);
        return pkg;
    }

    private void ensurePoeForeground() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isPoeWindow(root)) {
            safeRecycle(root);
            poeStarted = true;
            return;
        }
        safeRecycle(root);

        android.content.pm.PackageManager pm = getPackageManager();
        try {
            pm.getPackageInfo(POE_PACKAGE, 0);
        } catch (Exception e) {
            throw new Exception("Chưa cài ứng dụng Poe.");
        }

        android.content.Intent launch = pm.getLaunchIntentForPackage(POE_PACKAGE);
        if (launch == null) throw new Exception("Không tìm thấy màn hình mở Poe.");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(launch);
            } catch (Exception e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        });

        if (!done.await(5, TimeUnit.SECONDS)) throw new Exception("Hết thời gian mở Poe.");
        if (error.get() != null) throw error.get();
        poeStarted = true;
    }

    private String buildPoeQuestion(String instructions, String incoming) {
        String p = instructions == null ? "" : instructions.trim();
        String q = incoming == null ? "" : incoming.trim();
        if (p.isEmpty()) return q;
        if (q.isEmpty()) return p;
        return p + "\n\nTin nhắn/câu hỏi cần xử lý:\n" + q;
    }

    private boolean isPoeWindow(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null
                && POE_PACKAGE.equals(root.getPackageName().toString());
    }

    private AccessibilityNodeInfo waitForPoeComposer(long timeout) throws InterruptedException {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isPoeWindow(root)) {
                AccessibilityNodeInfo e = findPoeComposer(root);
                if (e != null) {
                    safeRecycle(root);
                    return e;
                }
            }
            safeRecycle(root);
            Thread.sleep(250L);
        }
        return null;
    }

    private AccessibilityNodeInfo findPoeComposer(AccessibilityNodeInfo root) {
        if (root == null) return null;
        return findEditable(root);
    }

    private boolean setNodeTextAndVerifyPoe(AccessibilityNodeInfo node, String text, long timeout)
            throws InterruptedException {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Thread.sleep(150L);
                String actual = normalize(value(node.getText()));
                if (actual.equals(normalize(text))) return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    private AccessibilityNodeInfo findPoeSendButton(
            AccessibilityNodeInfo root, AccessibilityNodeInfo composer) {
        if (root == null) return null;

        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectClickableNodes(root, nodes);
        Rect cr = new Rect();
        if (composer != null) composer.getBoundsInScreen(cr);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            String all = (value(n.getText()) + " " + value(n.getContentDescription()) + " "
                    + value(n.getViewIdResourceName())).toLowerCase(Locale.ROOT);
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int score = 0;
            if (all.contains("send") || all.contains("gửi") || all.contains("submit")) score += 10000;
            if (all.contains("ask")) score += 7000;
            if (all.contains("stop") || all.contains("cancel")) score -= 20000;
            if (r.bottom >= cr.top - 100 && r.top <= cr.bottom + 100) score += 3000;
            score -= Math.abs(r.centerY() - cr.centerY());

            if (score > bestScore) {
                safeRecycle(best);
                best = n;
                bestScore = score;
            } else {
                safeRecycle(n);
            }
        }
        return bestScore > 0 ? best : null;
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.isClickable()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickableNodes(node.getChild(i), out);
        }
    }

    private String waitForPoeAnswer(String question, long timeout) throws InterruptedException {
        String previous = "";
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isPoeWindow(root)) {
                String answer = extractPoeAnswer(root, question);
                if (!answer.isEmpty() && !normalize(answer).equals(normalize(question))) {
                    if (answer.equals(previous)) {
                        safeRecycle(root);
                        return answer;
                    }
                    previous = answer;
                }
            }
            safeRecycle(root);
            Thread.sleep(600L);
        }
        return previous;
    }

    private String extractPoeAnswer(AccessibilityNodeInfo root, String question) {
        List<String> texts = new ArrayList<>();
        collectPoeText(root, texts);

        String best = "";
        String nq = normalize(question);
        for (String raw : texts) {
            String x = raw == null ? "" : raw.trim();
            String nx = normalize(x);
            if (nx.length() < 2 || nx.equals(nq)) continue;
            if (isPoeUiText(nx)) continue;
            if (x.length() > best.length()) best = x;
        }
        return best.trim();
    }

    private void collectPoeText(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && !node.isEditable()) {
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) out.add(t.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectPoeText(node.getChild(i), out);
        }
    }

    private boolean isPoeUiText(String text) {
        String x = text.toLowerCase(Locale.ROOT).trim();
        return x.equals("send") || x.equals("gửi") || x.equals("ask")
                || x.equals("stop") || x.equals("cancel")
                || x.equals("poe") || x.equals("new chat")
                || x.equals("copy") || x.equals("sao chép");
    }

    private void returnToPackage(String packageName) {
        try {
            android.content.Intent back =
                    getPackageManager().getLaunchIntentForPackage(packageName);
            if (back != null) {
                back.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(back);
            }
        } catch (Exception ignored) {}
    }

    private boolean sendMessage(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            safeRecycle(root);
            return false;
        }
        String pkg = root.getPackageName().toString();
        if (!isSupportedChatPackage(pkg)) {
            safeRecycle(root);
            return false;
        }

        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) {
            safeRecycle(root);
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
        return clicked;
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
        safeRecycle(lastInput);
        lastInput = null;
        super.onDestroy();
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
