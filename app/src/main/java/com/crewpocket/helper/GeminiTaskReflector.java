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
 * One-shot Crew Experience lesson compressor using Gemini Flash with fallback.
 *
 * Runtime supplies already-qualified deterministic evidence-rule identities.
 * Gemini does not decide whether to learn; it only phrases each supplied rule.
 */
final class GeminiTaskReflector {
    private static final String TAG = "CrewExperience";

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

    static final class DiagnosticResult {
        final boolean success;
        final String primaryStatus;
        final String selectedModel;
        final String selectedStatus;
        final boolean fallbackUsed;
        final long latencyMs;

        DiagnosticResult(boolean success,
                         String primaryStatus,
                         String selectedModel,
                         String selectedStatus,
                         boolean fallbackUsed,
                         long latencyMs) {
            this.success = success;
            this.primaryStatus = primaryStatus == null ? "UNKNOWN_ERROR" : primaryStatus;
            this.selectedModel = selectedModel == null ? MODEL : selectedModel;
            this.selectedStatus = selectedStatus == null ? "UNKNOWN_ERROR" : selectedStatus;
            this.fallbackUsed = fallbackUsed;
            this.latencyMs = Math.max(0L, latencyMs);
        }
    }

    private final String apiKey;
    private String lastModel = MODEL;
    private int lastModelIndex = -1;

    GeminiTaskReflector(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    String lastModel() { return lastModel; }
    boolean hasAttemptedModel() { return lastModelIndex >= 0; }
    boolean usedFallback() { return lastModelIndex > 0; }

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
                Log.w(TAG, "experience model failed: " + model
                        + " code=" + safeFailureCode(error));
            }
        }
        throw new IllegalStateException("REFLECTION_ALL_MODELS_FAILED", lastFailure);
    }

    /** Tiny structured-output request to verify model/API availability. */
    DiagnosticResult diagnose() {
        final long startedAt = System.currentTimeMillis();
        if (apiKey.length() < 20) {
            return new DiagnosticResult(
                    false, "NO_API_KEY", MODEL, "NO_API_KEY", false,
                    System.currentTimeMillis() - startedAt);
        }

        String primaryStatus = "NOT_TESTED";
        String lastStatus = "UNKNOWN_ERROR";
        String selectedModel = MODEL;
        boolean fallbackUsed = false;

        for (int i = 0; i < CANDIDATE_MODELS.length; i++) {
            String model = CANDIDATE_MODELS[i];
            selectedModel = model;
            fallbackUsed = i > 0;
            lastModel = model;
            lastModelIndex = i;
            try {
                diagnoseWithModel(model);
                if (i == 0) primaryStatus = "SUCCESS";
                return new DiagnosticResult(
                        true, primaryStatus, model, "SUCCESS", fallbackUsed,
                        System.currentTimeMillis() - startedAt);
            } catch (Exception error) {
                lastStatus = safeFailureCode(error);
                if (i == 0) primaryStatus = lastStatus;
                Log.w(TAG, "experience diagnostic model failed: " + model
                        + " code=" + lastStatus);
            }
        }

        return new DiagnosticResult(
                false, primaryStatus, selectedModel, lastStatus, fallbackUsed,
                System.currentTimeMillis() - startedAt);
    }

    private JSONObject reflectWithModel(String model,
                                        JSONObject episode,
                                        boolean includeThinking) throws Exception {
        JSONObject body = buildRequestBody(episode, includeThinking);
        String raw = postGenerateContent(model, body);

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

    private void diagnoseWithModel(String model) throws Exception {
        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("ok", new JSONObject().put("type", "boolean")))
                .put("required", new JSONArray().put("ok"));

        JSONObject generationConfig = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 256);
        if (supportsThinkingLevel(model)) {
            generationConfig.put("thinkingConfig",
                    new JSONObject().put("thinkingLevel", "MINIMAL"));
        }

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(
                        new JSONObject().put("role", "user")
                                .put("parts", new JSONArray().put(
                                        new JSONObject().put(
                                                "text",
                                                "Connectivity diagnostic only. Return JSON with ok=true.")))))
                .put("generationConfig", generationConfig);

        String raw = postGenerateContent(model, body);
        JSONObject response = new JSONObject(raw);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException("REFLECTION_DIAGNOSTIC_EMPTY_CANDIDATES");
        }
        JSONObject content = candidates.optJSONObject(0) == null
                ? null : candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        String text = "";
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null) text += part.optString("text", "");
            }
        }
        if (text.trim().isEmpty()) {
            throw new IllegalStateException("REFLECTION_DIAGNOSTIC_EMPTY_TEXT");
        }
        JSONObject diagnostic = new JSONObject(text);
        if (!diagnostic.optBoolean("ok", false)) {
            throw new IllegalStateException("REFLECTION_DIAGNOSTIC_BAD_RESPONSE");
        }
    }

    private String postGenerateContent(String model, JSONObject body) throws Exception {
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
        return raw;
    }

    private static JSONObject buildRequestBody(JSONObject episode,
                                               boolean includeThinking) throws Exception {
        JSONArray evidenceRules = episode == null ? null : episode.optJSONArray("evidence_rules");
        int ruleCount = evidenceRules == null ? 0 : Math.min(2, evidenceRules.length());
        if (ruleCount <= 0) throw new IllegalStateException("REFLECTION_NO_QUALIFIED_RULES");

        JSONArray allowedIds = new JSONArray();
        for (int i = 0; i < ruleCount; i++) {
            JSONObject rule = evidenceRules.optJSONObject(i);
            if (rule != null) allowedIds.put(rule.optString("id", ""));
        }

        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(
                new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(
                                new JSONObject().put("text", buildPrompt(episode))))));

        JSONObject selectedRule = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("candidate_id", new JSONObject()
                                .put("type", "string")
                                .put("enum", allowedIds))
                        .put("lesson", new JSONObject().put("type", "string")))
                .put("required", new JSONArray()
                        .put("candidate_id")
                        .put("lesson"));

        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("rules", new JSONObject()
                                .put("type", "array")
                                .put("minItems", ruleCount)
                                .put("maxItems", ruleCount)
                                .put("items", selectedRule)))
                .put("required", new JSONArray().put("rules"));

        JSONObject generationConfig = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 1536);
        if (includeThinking) {
            generationConfig.put("thinkingConfig",
                    new JSONObject().put("thinkingLevel", "LOW"));
        }
        body.put("generationConfig", generationConfig);
        return body;
    }

    private static boolean supportsThinkingLevel(String model) {
        if (model == null) return false;
        return model.startsWith("gemini-3.6-")
                || model.startsWith("gemini-3.5-")
                || model.startsWith("gemini-3-");
    }

    private static String buildPrompt(JSONObject episode) {
        return "You are Crew Helper's Experience lesson compressor. "
                + "Runtime has ALREADY decided that every evidence_rules item qualifies for learning. "
                + "You do NOT decide whether to remember, rank, filter, or reject rules. "
                + "Return exactly one lesson for every supplied candidate_id.\n\n"
                + "Hard rules:\n"
                + "- Return every candidate_id from evidence_rules exactly once. Never invent, omit, replace, or reorder ids.\n"
                + "- Do not invent a scope, condition, response, category, or rule key. Runtime owns all rule identity.\n"
                + "- Your only job is to compress each selected Runtime rule into one concise human-readable operational lesson.\n"
                + "- Never include user identity, names, message text, search values, URLs, numbers, credentials, OTPs, passwords, payment/account actions, deletion, or SEND authorization.\n"
                + "- Never infer missing UI details or claim that an observation caused recovery when the evidence does not show it.\n"
                + "- A lesson must explain the candidate's reusable operational behavior, not the user's specific task.\n"
                + "- lesson must be one concise English operational sentence, <= 180 characters, with no personal values and no authorization language.\n"
                + "- Existing app playbook guidance is context for wording only. It must not cause you to omit a Runtime-qualified candidate.\n\n"
                + "Sanitized episode:\n" + (episode == null ? "{}" : episode.toString());
    }

    static String safeFailureCode(Exception error) {
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
