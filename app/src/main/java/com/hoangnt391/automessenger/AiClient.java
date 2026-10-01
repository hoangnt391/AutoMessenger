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
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Chưa nhập Gemini API key");
        }

        String modelName = model == null || model.trim().isEmpty()
                ? "gemini-flash-latest"
                : model.trim();

        JSONObject body = new JSONObject();

        if (instructions != null && !instructions.trim().isEmpty()) {
            JSONObject systemInstruction = new JSONObject()
                    .put("parts", new JSONArray()
                            .put(new JSONObject().put("text", instructions.trim())));
            body.put("system_instruction", systemInstruction);
        }

        body.put("contents", new JSONArray().put(
                new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray()
                                .put(new JSONObject().put("text", incoming == null ? "" : incoming)))
        ));

        body.put("generationConfig", new JSONObject()
                .put("temperature", 0.7)
                .put("maxOutputTokens", 300));

        URL url = new URL(
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + modelName + ":generateContent");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("x-goog-api-key", apiKey.trim());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? c.getInputStream()
                : c.getErrorStream();

        String response = readAll(stream);

        if (code < 200 || code >= 300) {
            throw new Exception("Gemini HTTP " + code + ": " + response);
        }

        String text = extractText(new JSONObject(response));
        if (text == null || text.trim().isEmpty()) {
            throw new Exception("Gemini không trả về nội dung");
        }

        return text.trim();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line);
            }
        }

        return out.toString();
    }

    private static String extractText(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null) return "";

        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;

            JSONObject content = candidate.optJSONObject("content");
            if (content == null) continue;

            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) continue;

            StringBuilder result = new StringBuilder();

            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null) continue;

                String text = part.optString("text", "");
                if (!text.isEmpty()) {
                    if (result.length() > 0) result.append("\n");
                    result.append(text);
                }
            }

            if (result.length() > 0) return result.toString();
        }

        return "";
    }
}