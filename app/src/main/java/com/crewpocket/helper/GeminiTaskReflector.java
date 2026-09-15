package com.crewpocket.helper;

import android.util.Log;

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
 * One-shot post-task reviewer using Gemini Flash with model fallback.
 *
 * This client is never used inside the phone action loop. Reflection is
 * best-effort: each candidate model is tried in order and a total failure
 * simply drops the reflection attempt.
 */
final class GeminiTaskReflector {
    private static final String TAG = "CrewReflection";

    static final String[] CANDIDATE_MODELS = {
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.0-flash",
            "gemini-3-flash",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
    };

    // Kept for compatibility with reflection history/default reporting.
    static final String MODEL = CANDIDATE_MODELS[0];

    private final String apiKey;
    private String lastModel = MODEL;

    GeminiTaskReflector(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    String lastModel() {
        return lastModel;
    }

    JSONObject reflect(JSONObject episode) throws Exception {
        if (apiKey.length() < 20) throw new IllegalStateException("GEMINI_API_KEY_MISSING");

        Exception lastFailure = null;
        for (int i = 0; i < CANDIDATE_MODELS.length; i++) {
            String model = CANDIDATE_MODELS[i];
            lastModel = model;
            try {
                // Preserve the existing MEDIUM thinking behavior for the primary
                // reviewer. Fallback models use a simpler generationConfig so older
                // endpoints do not fail only because they reject thinkingConfig.
                return reflectWithModel(model, episode, i == 0);
            } catch (Exception error) {
                lastFailure = error;
                Log.w(TAG, "reflection model failed: " + model
                        + " code=" + safeFailureCode(error));
            }
        }

        throw new IllegalStateException("REFLECTION_ALL_MODELS_FAILED", lastFailure);
    }

    private JSONObject reflectWithModel(String model,
                                        JSONObject episode,
                                        boolean includeThinking) throws Exception {
        JSONObject body = buildRequestBody(episode, includeThinking);
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent";

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
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

    private static JSONObject buildRequestBody(JSONObject episode,
                                               boolean includeThinking) throws Exception {
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

        JSONObject generationConfig = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 1200);
        if (includeThinking) {
            generationConfig.put("thinkingConfig",
                    new JSONObject().put("thinkingLevel", "MEDIUM"));
        }
        body.put("generationConfig", generationConfig);
        return body;
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

    private static String safeFailureCode(Exception error) {
        if (error == null) return "UNKNOWN_ERROR";
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        if (message.startsWith("REFLECTION_")) {
            return message.replaceAll("[^A-Za-z0-9_]", "_");
        }
        return error.getClass().getSimpleName();
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
