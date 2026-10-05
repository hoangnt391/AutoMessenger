package com.hoangnt391.automessenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Direct AshnaAI HTTP client. No web UI, tab switching, or provider fallback. */
public final class AiClient {
    private static final String ENDPOINT =
            "https://api.ashna.ai/v1/api/chat/completions";

    private AiClient() {}

    public static String reply(String apiKey, String model, String instructions, String incoming)
            throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Chưa nhập AshnaAI API key.");
        }
        String chosenModel = model == null || model.trim().isEmpty()
                ? "gpt-6-sol" : model.trim();

        String system = instructions == null ? "" : instructions.trim();
        String user = incoming == null ? "" : incoming.trim();
        if (user.isEmpty()) throw new IllegalArgumentException("Nội dung cần xử lý đang trống.");

        JSONObject body = new JSONObject();
        body.put("model", chosenModel);
        body.put("temperature", 0.7);
        body.put("max_tokens", 500);

        JSONArray messages = new JSONArray();
        if (!system.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", system));
        }
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(bytes);

            int code = c.getResponseCode();
            java.io.InputStream stream = code >= 200 && code < 300
                    ? c.getInputStream() : c.getErrorStream();
            String response = readAll(stream);
            JSONObject json;
            try {
                json = new JSONObject(response == null ? "{}" : response);
            } catch (Exception parse) {
                throw new IOException("AshnaAI trả về dữ liệu không hợp lệ (HTTP " + code + ").");
            }

            if (code < 200 || code >= 300) {
                JSONObject err = json.optJSONObject("error");
                String msg = err == null ? "" : err.optString("message", "");
                if (msg.isEmpty()) msg = "AshnaAI lỗi HTTP " + code;
                throw new IOException(msg);
            }

            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IOException("AshnaAI không trả về choices.");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String answer = message == null ? "" : message.optString("content", "");
            if (answer.trim().isEmpty()) throw new IOException("AshnaAI trả về câu trả lời trống.");
            return answer.trim();
        } finally {
            c.disconnect();
        }
    }

    public static void validateKey(String apiKey, String model) throws Exception {
        reply(apiKey, model, "Trả lời đúng một từ: OK.", "ping");
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        if (in == null) return "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        }
    }
}
