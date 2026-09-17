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
 * Runtime supplies deterministic evidence-rule identities. Gemini can only
 * select from those candidates and write the human-readable lesson.
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

    static final String MODEL = CANDIDATE_MODELS[0];

    private final String apiKey;
    private String lastModel = MODEL;
    private int lastModelIndex = -1;

    GeminiTaskReflector(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    String lastModel() {
        return lastModel;
    }

    boolean hasAttemptedModel() {
        return lastModelIndex >= 0;
    }

    boolean usedFallback() {
        return lastModelIndex > 0;
    }

    JSONObject reflect(JSONObject episode) throws Exception {
        if (apiKey.length() < 20) throw new IllegalStateException("GEMINI_API_KEY_MISSING");

        Exception lastFailure = null;
        for (int i = 0; i < CANDIDATE_MODELS.length; i++) {
            String model = CANDIDATE_MODELS[i];
            lastModel = model;
            lastModelIndex = i;
            try {
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

        JSONObject selectedRule = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("candidate_id", new JSONObject().put("type", "string"))
                        .put("lesson", new JSONObject().put("type", "string"))
                        .put("confidence", new JSONObject().put("type", "number")))
                .put("required", new JSONArray()
                        .put("candidate_id")
                        .put("lesson")
                        .put("confidence"));

        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("rules", new JSONObject()
                                .put("type", "array")
                                .put("maxItems", 2)
                                .put("items", selectedRule)))
                .put("required", new JSONArray().put("rules"));

        JSONObject generationConfig = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 800);
        if (includeThinking) {
            generationConfig.put("thinkingConfig",
                    new JSONObject().put("thinkingLevel", "MEDIUM"));
        }
        body.put("generationConfig", generationConfig);
        return body;
    }

    private static String buildPrompt(JSONObject episode) {
        return "You are Crew Helper's post-task reflection reviewer. "
                + "You do NOT control the phone and you do NOT define rule identity. "
                + "Runtime has already derived deterministic evidence-rule candidates. "
                + "Choose zero, one, or at most two candidates that are clearly supported and reusable.\n\n"
                + "Hard rules:\n"
                + "- You may ONLY return candidate_id values present in evidence_rules. Never invent an id, scope, condition, response, category, or rule key.\n"
                + "- If no candidate is worth remembering, return {\"rules\":[]}.\n"
                + "- Learn only reusable UI/navigation/runtime behavior directly supported by the evidence.\n"
                + "- Never learn user identity, names, message text, search values, URLs, numbers, credentials, OTPs, passwords, payment/account actions, deletion, or SEND authorization.\n"
                + "- Never infer missing UI details or claim that an observation caused recovery when the evidence does not show it.\n"
                + "- A lesson must explain the selected candidate's operational rule, not the user's specific task.\n"
                + "- lesson must be one concise English operational sentence, <= 180 characters, with no personal values and no authorization language.\n"
                + "- confidence must reflect only how strongly the sanitized evidence supports that rule.\n"
                + "- Existing app playbook guidance is context only; do not repeat it unless this episode adds genuine confirmation.\n\n"
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
