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

    public static boolean isChatAppActive() {
        MessageAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        // Never act on AutoMessenger itself or Android system/settings screens.
        return !pkg.equals("com.hoangnt391.automessenger")
                && !pkg.equals("com.android.settings")
                && !pkg.equals("com.android.systemui");
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
                        "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. " +
                        "Không nhắc rằng bạn là AI. Không dùng markdown.");

                String reply = AiClient.reply(key, model, prompt, incoming);
                if (reply != null && !reply.trim().isEmpty()) {
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

    private String shortError(String message) {
        String x = message == null ? "" : message.replace("\\n", " ").trim();
        if (x.length() > 180) x = x.substring(0, 180) + "...";
        return x.isEmpty() ? "kiểm tra API key, model và quyền Trợ năng." : x;
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
                x.contains("nhập tin nhắn");
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
}
