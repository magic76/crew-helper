package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One-shot post-task reviewer using Gemini Flash.
 *
 * This client is never used inside the phone action loop. A failure, timeout,
 * quota error, or model error simply drops the reflection attempt.
 */
final class GeminiTaskReflector {
    static final String MODEL = "gemini-3.6-flash";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent";

    private final String apiKey;

    GeminiTaskReflector(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    JSONObject reflect(JSONObject episode) throws Exception {
        if (apiKey.length() < 20) throw new IllegalStateException("GEMINI_API_KEY_MISSING");

        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(
                new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(
                                new JSONObject().put("text", buildPrompt(episode))))));

        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("should_remember", new JSONObject().put("type", "boolean"))
                        .put("goal_pattern", new JSONObject().put("type", "string"))
                        .put("lesson", new JSONObject().put("type", "string"))
                        .put("confidence", new JSONObject().put("type", "number")))
                .put("required", new JSONArray()
                        .put("should_remember")
                        .put("goal_pattern")
                        .put("lesson")
                        .put("confidence"));

        body.put("generationConfig", new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 1200)
                .put("thinkingConfig", new JSONObject().put("thinkingLevel", "MEDIUM")));

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(45_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", apiKey);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(payload);
            output.flush();
        } finally {
            output.close();
        }

        int status = connection.getResponseCode();
        String raw = readAll(status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("REFLECTION_HTTP_" + status);
        }

        JSONObject response = new JSONObject(raw);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException("REFLECTION_EMPTY_CANDIDATES");
        }
        JSONObject content = candidates.optJSONObject(0) == null
                ? null : candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) {
            throw new IllegalStateException("REFLECTION_EMPTY_CONTENT");
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String value = part.optString("text", "");
            if (!value.isEmpty()) text.append(value);
        }
        if (text.length() == 0) throw new IllegalStateException("REFLECTION_EMPTY_TEXT");
        return new JSONObject(text.toString());
    }

    private static String buildPrompt(JSONObject episode) {
        return "You are Crew Helper's post-task reflection reviewer. "
                + "You do NOT control the phone and you do NOT authorize actions. "
                + "Review only the sanitized runtime metadata below and decide whether it reveals one reusable operational lesson for this app.\n\n"
                + "Hard rules:\n"
                + "- Learn only reusable UI/navigation/runtime behavior.\n"
                + "- Never learn user identity, names, message text, search values, URLs, numbers, credentials, OTPs, passwords, payment/account actions, deletion, or SEND authorization.\n"
                + "- Never infer missing UI details. If the sanitized trace does not support a concrete reusable lesson, set should_remember=false.\n"
                + "- goal_pattern must be a generic 2-6 word English task category, without personal values.\n"
                + "- lesson must be one concise English operational sentence, <= 180 characters, with no personal values and no authorization language.\n"
                + "- A failed attempt can teach an avoidance/recovery rule only when the trace clearly supports it.\n"
                + "- Existing app playbook guidance may be used as context, but do not merely repeat it unless this task provides new confirmation.\n\n"
                + "Sanitized episode:\n" + (episode == null ? "{}" : episode.toString());
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        } finally {
            reader.close();
        }
        return out.toString();
    }
}
