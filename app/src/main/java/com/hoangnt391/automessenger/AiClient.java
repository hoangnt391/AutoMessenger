package com.hoangnt391.automessenger;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * AI compatibility facade.
 * Primary path: installed ChatGPT app through AccessibilityService.
 * Fallback: open Gemini with the same question so the user can continue searching.
 */
public final class AiClient {
    private AiClient() {}

    public static String reply(String ignoredKey, String ignoredModel,
                               String instructions, String incoming) throws Exception {
        MessageAccessibilityService service = MessageAccessibilityService.getInstance();
        if (service == null) {
            throw new IllegalStateException("Chưa bật Trợ năng.");
        }

        try {
            return service.requestChatGptReply(instructions, incoming);
        } catch (Exception chatGptError) {
            openGeminiSearch(instructions, incoming);
            String reason = chatGptError.getMessage();
            if (reason == null || reason.trim().isEmpty()) reason = "ChatGPT không trả lời được.";
            throw new Exception("ChatGPT không dùng được. Đã mở Gemini để tiếp tục tìm kiếm. " + reason);
        }
    }

    public static void validateKey(String ignoredKey, String ignoredModel) {
        // ChatGPT/Gemini app automation does not use an API key.
    }

    private static void openGeminiSearch(String instructions, String incoming) {
        MessageAccessibilityService service = MessageAccessibilityService.getInstance();
        if (service == null) return;

        String q = (incoming == null ? "" : incoming.trim());
        String p = (instructions == null ? "" : instructions.trim());
        String text = p.isEmpty() ? q : (p + "\n\n" + q);
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.toString());
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://gemini.google.com/app?q=" + encoded));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            web.setPackage("com.google.android.apps.bard");
            service.startActivity(web);
            return;
        } catch (Exception ignored) {
            // Fall through to a normal web browser.
        }

        try {
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://gemini.google.com/app"));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            service.startActivity(web);
        } catch (Exception ignored) {}
    }
}
