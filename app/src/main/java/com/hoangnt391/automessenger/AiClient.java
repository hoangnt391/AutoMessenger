package com.hoangnt391.automessenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AiClient {
    private AiClient() {}

    public static String reply(String apiKey, String model, String instructions, String incoming) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalArgumentException("Chưa nhập OpenAI API key");
        String body = new JSONObject()
                .put("model", model == null || model.trim().isEmpty() ? "gpt-5.6-luna" : model.trim())
                .put("instructions", instructions)
                .put("input", incoming)
                .put("max_output_tokens", 300)
                .toString();

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String response = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new Exception("OpenAI HTTP " + code + ": " + response);
        }

        String text = extractOutputText(new JSONObject(response));
        if (text == null || text.trim().isEmpty()) throw new Exception("OpenAI không trả về nội dung");
        return text.trim();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String extractOutputText(JSONObject root) throws Exception {
        JSONArray output = root.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    String type = part.optString("type", "");
                    if ("output_text".equals(type) || part.has("text")) {
                        String t = part.optString("text", "");
                        if (!t.isEmpty()) return t;
                    }
                }
            }
        }
        return root.optString("output_text", "");
    }
}