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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bounded semantic advisor for Candidate Arbitration.
 *
 * It can only return Advice. It has no Runtime, WorkingContext, Memory, tap,
 * coordinate, or tool-execution dependency.
 */
final class CandidateArbitrator {
    static final String MODEL = GeminiTaskReflector.MODEL;
    static final int MAX_CANDIDATES = 4;
    static final int MAX_GOAL_CHARS = 320;
    static final int MAX_LABEL_CHARS = 120;
    static final int MAX_ATTR_CHARS = 96;

    static final class Candidate {
        final String candidateId;
        final String label;
        final String role;
        final String viewId;
        final String semanticHint;
        final double confidence;
        final boolean exactViewId;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Candidate(
                String candidateId,
                String label,
                String role,
                String viewId,
                String semanticHint,
                double confidence,
                boolean exactViewId,
                int left,
                int top,
                int right,
                int bottom) {
            this.candidateId = clip(candidateId, MAX_ATTR_CHARS);
            this.label = clip(label, MAX_LABEL_CHARS);
            this.role = clip(role, MAX_ATTR_CHARS);
            this.viewId = clip(viewId, MAX_ATTR_CHARS);
            this.semanticHint = clip(semanticHint, MAX_ATTR_CHARS);
            this.confidence = clamp(confidence);
            this.exactViewId = exactViewId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        String safetyMetadata() {
            return label
                    + " "
                    + role
                    + " "
                    + viewId
                    + " "
                    + semanticHint;
        }
    }

    static final class Request {
        final String taskGoal;
        final String goalIntent;
        final long generation;
        final String packageName;
        final List<Candidate> candidates;

        Request(
                String taskGoal,
                String goalIntent,
                long generation,
                String packageName,
                List<Candidate> candidates) {
            this.taskGoal = clip(taskGoal, MAX_GOAL_CHARS);
            this.goalIntent = clip(goalIntent, MAX_ATTR_CHARS);
            this.generation = generation;
            this.packageName = clip(packageName, MAX_ATTR_CHARS);
            ArrayList<Candidate> bounded = new ArrayList<Candidate>();
            if (candidates != null) {
                for (Candidate candidate : candidates) {
                    if (candidate == null || candidate.candidateId.isEmpty()) continue;
                    bounded.add(candidate);
                    if (bounded.size() >= MAX_CANDIDATES) break;
                }
            }
            this.candidates = java.util.Collections.unmodifiableList(bounded);
        }
    }

    static final class Advice {
        final String selectedCandidateId;
        final double confidence;
        final boolean abstain;
        final String reasonCode;

        Advice(
                String selectedCandidateId,
                double confidence,
                boolean abstain,
                String reasonCode) {
            this.selectedCandidateId =
                    selectedCandidateId == null ? "" : selectedCandidateId.trim();
            this.confidence = clamp(confidence);
            this.abstain = abstain;
            this.reasonCode = safeReason(reasonCode);
        }

        static Advice abstain(String reasonCode) {
            return new Advice("", 0.0d, true, reasonCode);
        }
    }

    private final String apiKey;
    private volatile HttpURLConnection activeConnection;

    CandidateArbitrator(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    void cancelActiveRequest() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try { connection.disconnect(); } catch (Exception ignored) {}
        }
    }

    Advice arbitrate(Request request) throws Exception {
        if (request == null || request.candidates.size() < 2) {
            return Advice.abstain("INSUFFICIENT_CANDIDATES");
        }
        if (apiKey.length() < 20) {
            return Advice.abstain("NO_API_KEY");
        }

        JSONObject body = buildRequestBody(request);
        String raw = postGenerateContent(body);
        JSONObject response = new JSONObject(raw);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return Advice.abstain("EMPTY_MODEL_RESPONSE");
        }

        JSONObject content = candidates.optJSONObject(0) == null
                ? null
                : candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null) text.append(part.optString("text", ""));
            }
        }
        if (text.length() == 0) {
            return Advice.abstain("EMPTY_MODEL_RESPONSE");
        }

        JSONObject answer = new JSONObject(text.toString());
        boolean abstain = answer.optBoolean("abstain", false);
        String selected = answer.optString("selectedCandidateId", "").trim();
        double confidence = clamp(answer.optDouble("confidence", 0.0d));
        String reason = safeReason(answer.optString("reasonCode", "MODEL_DECISION"));

        if (abstain || selected.isEmpty()) {
            return Advice.abstain(reason.isEmpty() ? "MODEL_ABSTAIN" : reason);
        }
        if (!containsCandidate(request.candidates, selected)) {
            return Advice.abstain("INVALID_CANDIDATE_ID");
        }
        return new Advice(selected, confidence, false, reason);
    }

    private String postGenerateContent(JSONObject body) throws Exception {
        String endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + MODEL
                        + ":generateContent";
        HttpURLConnection connection =
                (HttpURLConnection) new URL(endpoint).openConnection();
        activeConnection = connection;
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1700);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8");
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
            String raw = readAll(
                    status >= 200 && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("ARBITRATION_HTTP_" + status);
            }
            return raw;
        } finally {
            try { connection.disconnect(); } catch (Exception ignored) {}
            if (activeConnection == connection) activeConnection = null;
        }
    }

    private static JSONObject buildRequestBody(Request request) throws Exception {
        JSONArray requestCandidates = new JSONArray();
        for (Candidate candidate : request.candidates) {
            requestCandidates.put(new JSONObject()
                    .put("candidateId", candidate.candidateId)
                    .put("label", candidate.label)
                    .put("role", candidate.role)
                    .put("viewId", candidate.viewId)
                    .put("semanticHint", candidate.semanticHint)
                    .put("locatorConfidence", candidate.confidence)
                    .put("bounds", new JSONObject()
                            .put("left", candidate.left)
                            .put("top", candidate.top)
                            .put("right", candidate.right)
                            .put("bottom", candidate.bottom)));
        }

        JSONObject boundedInput = new JSONObject()
                .put("taskGoal", request.taskGoal)
                .put("goalIntent", request.goalIntent)
                .put("generation", request.generation)
                .put("package", request.packageName)
                .put("candidates", requestCandidates);

        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("selectedCandidateId",
                                new JSONObject().put("type", "string"))
                        .put("confidence",
                                new JSONObject().put("type", "number"))
                        .put("abstain",
                                new JSONObject().put("type", "boolean"))
                        .put("reasonCode",
                                new JSONObject().put("type", "string")))
                .put("required", new JSONArray()
                        .put("selectedCandidateId")
                        .put("confidence")
                        .put("abstain")
                        .put("reasonCode"));

        JSONObject config = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 256)
                .put("thinkingConfig",
                        new JSONObject().put("thinkingLevel", "MINIMAL"));

        String prompt =
                "You are a bounded UI candidate arbitrator. "
                + "Choose only among supplied candidateId values when current "
                + "goal and UI semantics make one candidate clearly better. "
                + "Abstain is a normal high-quality result. "
                + "Never output coordinates, actions, tool calls, plans, user "
                + "messages, or any field beyond the required JSON contract. "
                + "Do not invent UI facts.\nBounded input:\n"
                + boundedInput.toString();

        return new JSONObject()
                .put("contents", new JSONArray().put(
                        new JSONObject()
                                .put("role", "user")
                                .put("parts", new JSONArray().put(
                                        new JSONObject().put("text", prompt)))))
                .put("generationConfig", config);
    }

    private static boolean containsCandidate(
            List<Candidate> candidates,
            String selected) {
        for (Candidate candidate : candidates) {
            if (candidate != null
                    && selected.equals(candidate.candidateId)) {
                return true;
            }
        }
        return false;
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

    private static String safeReason(String value) {
        String reason =
                value == null
                        ? ""
                        : value.trim().toUpperCase(Locale.ROOT);
        reason = reason.replaceAll("[^A-Z0-9_]", "_");
        if (reason.length() > 64) reason = reason.substring(0, 64);
        return reason.isEmpty() ? "UNKNOWN" : reason;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0d;
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String clip(String value, int maxChars) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() <= maxChars) return clean;
        return clean.substring(0, maxChars);
    }
}
