package com.hoangnt391.automessenger;

import android.accessibilityservice.AccessibilityService;
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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo input = findEditable(root);
        if (input != null && input.isVisibleToUser()) {
            if (lastInput != null && lastInput != input) lastInput.recycle();
            lastInput = input;
        }

        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;
        if (replying) return;

        String incoming = extractLatestMessage(root, event);
        if (incoming == null || incoming.trim().isEmpty()) return;
        incoming = incoming.trim();

        // Ignore our own last reply and duplicate accessibility events.
        if (incoming.equals(lastSent) || incoming.equals(lastIncoming)) return;
        if (System.currentTimeMillis() - lastReplyAt < 7000L) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);

        lastIncoming = incoming;
        generateAndSend(incoming);
    }

    private void generateAndSend(final String incoming) {
        replying = true;
        worker.execute(() -> {
            try {
                android.content.SharedPreferences p = getSharedPreferences("AutoMessenger", 0);
                String key = p.getString("api_key", "");
                String model = p.getString("model", "gemini-flash-latest");
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
            } catch (Exception ignored) {
                // Keep the accessibility service alive; the user can inspect the in-app status/log.
            } finally {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> replying = false, 1200L);
            }
        });
    }

    private boolean sendMessage(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) return false;
        lastInput = input;

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) return false;

        AccessibilityNodeInfo send = findSendButton(root);
        if (send == null) return false;
        return clickNodeOrParent(send);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable()) return node;
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
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String combined = (text + " " + desc + " " + id).toLowerCase(Locale.ROOT);

        if (combined.matches(".*\\b(send|gửi|gui)\\b.*") ||
                id.contains("send") || id.contains("message_send")) {
            if (node.isVisibleToUser() && node.isClickable()) return node;
            if (node.isVisibleToUser()) return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findSendButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isVisibleToUser() && current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return false;
    }

    private String extractLatestMessage(AccessibilityNodeInfo root, AccessibilityEvent event) {
        List<String> candidates = new ArrayList<>();
        if (event.getText() != null) {
            for (CharSequence s : event.getText()) {
                if (!TextUtils.isEmpty(s)) candidates.add(s.toString());
            }
        }
        collectMessageTexts(root, candidates);

        String best = "";
        for (String s : candidates) {
            s = s == null ? "" : s.trim();
            if (s.isEmpty() || s.length() > 4000) continue;
            if (isUiText(s)) continue;
            best = s;
        }
        return best;
    }

    private void collectMessageTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String text = value(node.getText()).trim();
        if (node.isVisibleToUser() && !text.isEmpty() &&
                (id.contains("message") || id.contains("messenger"))) {
            out.add(text);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMessageTexts(node.getChild(i), out);
        }
    }

    private boolean isUiText(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        return x.equals("send") || x.equals("gửi") || x.equals("gui") ||
                x.equals("message") || x.contains("type a message");
    }

    private String value(CharSequence s) {
        return s == null ? "" : s.toString();
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