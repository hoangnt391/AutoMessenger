package com.hoangnt391.automessenger;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MessageAccessibilityService extends AccessibilityService {
    private static final String PREF = "AutoMessenger";
    private static final long DEBOUNCE_MS = 900L;
    private static final long COOLDOWN_MS = 6000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRun;
    private String lastFingerprint = "";
    private String lastSent = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        if (getServiceInfo() != null) {
            getServiceInfo().flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(getServiceInfo());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isEnabled()) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED) return;

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::tryAutoReply, DEBOUNCE_MS);
    }

    private boolean isEnabled() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("enabled", false)
                && getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("auto", true);
    }

    private void tryAutoReply() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo input = findEditable(root);
        if (input == null || !input.isVisibleToUser()) return;

        CharSequence currentInput = input.getText();
        if (!TextUtils.isEmpty(currentInput)) return;

        String fingerprint = buildConversationFingerprint(root);
        if (fingerprint.isEmpty() || fingerprint.equals(lastFingerprint)) return;
        if (System.currentTimeMillis() - lastRun < COOLDOWN_MS) return;

        String incoming = findLastMessageText(root, input);
        String reply = chooseReply(incoming);
        if (TextUtils.isEmpty(reply) || reply.equals(lastSent)) return;

        lastFingerprint = fingerprint;
        lastRun = System.currentTimeMillis();

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
        boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) {
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }

        if (set) {
            lastSent = reply;
            handler.postDelayed(() -> clickSend(root), 350L);
        }
    }

    private void clickSend(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo fresh = getRootInActiveWindow();
        if (fresh == null) fresh = root;

        AccessibilityNodeInfo send = findSendButton(fresh);
        if (send != null) {
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable()) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findEditable(child);
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;

        String text = safe(node.getText());
        String desc = safe(node.getContentDescription());
        String id = safe(node.getViewIdResourceName());
        String all = (text + " " + desc + " " + id).toLowerCase(Locale.ROOT);

        boolean looksLikeSend = all.contains("send")
                || all.contains("gửi")
                || all.contains("gui")
                || all.contains("messenger_send");
        if (node.isVisibleToUser() && looksLikeSend
                && node.isClickable()
                && !node.isEditable()) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findSendButton(child);
            if (result != null) return result;
        }
        return null;
    }

    private String findLastMessageText(AccessibilityNodeInfo root, AccessibilityNodeInfo input) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, input, texts);
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = texts.get(i).trim();
            if (s.length() >= 2 && s.length() <= 500) return s;
        }
        return "";
    }

    private void collectTexts(AccessibilityNodeInfo node, AccessibilityNodeInfo input, List<String> out) {
        if (node == null || node == input) return;
        if (node.isVisibleToUser()) {
            String s = safe(node.getText()).trim();
            if (!s.isEmpty()) out.add(s);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectTexts(node.getChild(i), input, out);
        }
    }

    private String buildConversationFingerprint(AccessibilityNodeInfo root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, null, texts);
        int start = Math.max(0, texts.size() - 12);
        StringBuilder b = new StringBuilder();
        for (int i = start; i < texts.size(); i++) {
            b.append(texts.get(i)).append('|');
        }
        return b.toString();
    }

    private String chooseReply(String incoming) {
        String script = getSharedPreferences(PREF, MODE_PRIVATE)
                .getString("script",
                        "*=>Chào bạn! Mình đã nhận được tin nhắn, lát mình phản hồi nhé.");
        String lower = incoming.toLowerCase(Locale.ROOT);

        String fallback = "";
        for (String line : script.split("\\n")) {
            String raw = line.trim();
            if (raw.isEmpty() || raw.startsWith("#")) continue;

            int arrow = raw.indexOf("=>");
            if (arrow < 0) {
                if (fallback.isEmpty()) fallback = raw;
                continue;
            }

            String trigger = raw.substring(0, arrow).trim();
            String response = raw.substring(arrow + 2).trim();
            if (response.isEmpty()) continue;

            if (trigger.equals("*") || trigger.equalsIgnoreCase("default")) {
                fallback = response;
            } else if (!trigger.isEmpty() && lower.contains(trigger.toLowerCase(Locale.ROOT))) {
                return response;
            }
        }
        return fallback;
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    @Override
    public void onInterrupt() {}
}