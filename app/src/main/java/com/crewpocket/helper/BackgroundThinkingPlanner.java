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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Optional second engine: a bounded one-shot Gemini generateContent planner.
 * Standard Live owns all microphone/audio/tools. Planner never executes tools,
 * sends Live clientContent, saves notes or touches the screen.
 */
final class BackgroundThinkingPlanner {
    private static final String MODEL = "gemini-3.6-flash";
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_CHARS = 12_000;

    private final boolean enabled;
    private final String apiKey;
    private final ExecutorService worker;
    private Future<?> current;
    private HttpURLConnection activeConnection;
    private long generation = -1L;
    private long requestEpoch;
    private String goal = "";
    private JSONArray readySteps;
    private boolean delivered;
    private boolean stopped;

    BackgroundThinkingPlanner(String apiKey, boolean enabled) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.enabled = enabled && this.apiKey.length() >= 20;
        this.worker = this.enabled
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "crew-background-thinking");
                    t.setDaemon(true);
                    return t;
                }) : null;
    }

    synchronized void newIntent(long newGeneration, String text, String newGoal) {
        cancelCurrentLocked();
        final long epoch = ++requestEpoch;
        generation = newGeneration;
        goal = newGoal == null ? "" : newGoal;
        readySteps = null;
        delivered = false;
        if (!enabled || stopped) return;
        final String task = DualEnginePlanningPolicy.eligiblePrompt(text, goal);
        if (task.isEmpty()) return;
        final String purpose = goal;
        LivePipelineDiagnostic.onPlannerStarted();
        current = worker.submit(() -> {
            try {
                JSONArray steps = plan(task, purpose);
                synchronized (BackgroundThinkingPlanner.this) {
                    if (stopped || generation != newGeneration
                            || requestEpoch != epoch
                            || !goal.equals(purpose)) return;
                    readySteps = steps;
                    LivePipelineDiagnostic.onPlannerReady();
                }
            } catch (Exception ignored) {
                synchronized (BackgroundThinkingPlanner.this) {
                    if (!stopped && generation == newGeneration
                            && requestEpoch == epoch) {
                        LivePipelineDiagnostic.onPlannerFailed();
                    }
                }
            }
        });
    }

    synchronized JSONObject peekIfReady(long requestGeneration, String currentGoal) {
        if (!enabled || stopped || delivered || readySteps == null
                || generation != requestGeneration
                || !goal.equals(currentGoal)) return null;
        JSONObject out = new JSONObject();
        try {
            out.put("source", "background_thinking");
            out.put("nonAuthoritative", true);
            out.put("steps", new JSONArray(readySteps.toString()));
            out.put("rule", "Advisory only: obey Runtime goal.next and requiredTool; never infer completion, permissions or screen evidence.");
        } catch (Exception ignored) { return null; }
        return out;
    }

    synchronized void markDelivered(long requestGeneration) {
        if (generation != requestGeneration || readySteps == null || delivered) return;
        delivered = true;
        LivePipelineDiagnostic.onPlannerDelivered();
    }

    synchronized void invalidate() {
        requestEpoch++;
        cancelCurrentLocked();
        readySteps = null;
        delivered = true;
    }

    synchronized void stop() {
        if (stopped) return;
        stopped = true;
        invalidate();
        if (worker != null) worker.shutdownNow();
    }

    private void cancelCurrentLocked() {
        if (current != null) current.cancel(true);
        current = null;
        if (activeConnection != null) {
            activeConnection.disconnect();
            activeConnection = null;
        }
    }

    private JSONArray plan(String task, String purpose) throws Exception {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("steps", new JSONObject()
                                .put("type", "array")
                                .put("minItems", 1)
                                .put("maxItems", 3)
                                .put("items", new JSONObject()
                                        .put("type", "string"))))
                .put("required", new JSONArray().put("steps"));
        JSONObject config = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", 1536)
                .put("thinkingConfig", new JSONObject().put("thinkingLevel", "LOW"));
        String instruction = "You are a read-only planning assistant for an Android music playback goal. "
                + "Return at most three short, ordered semantic steps as JSON. "
                + "You have NO screen evidence, tool permissions, contacts, or success evidence. "
                + "Do not claim actions completed. Prefer search result -> tap verified song -> verified Play. "
                + "Never suggest SEND, payments, deletion, credential access, or bypassing Runtime checks. "
                + "Do not invent screen labels, coordinates, tool IDs, or execution permissions.";
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(
                                new JSONObject().put("text", instruction))))
                .put("contents", new JSONArray().put(
                        new JSONObject().put("role", "user")
                                .put("parts", new JSONArray().put(
                                        new JSONObject().put("text",
                                                "Goal category: " + purpose + "\nUser request: " + task)))))
                .put("generationConfig", config);
        JSONObject response = new JSONObject(post(body));
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0)
            throw new IllegalStateException("PLANNER_NO_CANDIDATE");
        JSONObject candidate = candidates.optJSONObject(0);
        JSONObject result = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = result == null ? null : result.optJSONArray("parts");
        if (parts == null) throw new IllegalStateException("PLANNER_NO_PARTS");
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null || part.optBoolean("thought", false)) continue;
            String piece = part.optString("text", "");
            if (raw.length() + piece.length() > 4096)
                throw new IllegalStateException("PLANNER_OUTPUT_LIMIT");
            raw.append(piece);
        }
        JSONArray proposed = new JSONObject(raw.toString()).optJSONArray("steps");
        if (proposed == null || proposed.length() == 0)
            throw new IllegalStateException("PLANNER_EMPTY");
        JSONArray accepted = new JSONArray();
        for (int i = 0; i < Math.min(3, proposed.length()); i++) {
            String item = proposed.optString(i, "").trim();
            if (DualEnginePlanningPolicy.safeAdvice(item)) accepted.put(item);
        }
        if (accepted.length() == 0) throw new IllegalStateException("PLANNER_UNSAFE");
        return accepted;
    }

    private String post(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + MODEL + ":generateContent").openConnection();
        synchronized (this) {
            if (stopped || Thread.currentThread().isInterrupted()) {
                connection.disconnect();
                throw new InterruptedException("PLANNER_CANCELLED");
            }
            activeConnection = connection;
        }
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", apiKey);
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("PLANNER_HTTP_" + status);
            }
            try (InputStream stream = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[1024];
                int n;
                while ((n = reader.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("PLANNER_CANCELLED");
                    }
                    if (text.length() + n > MAX_RESPONSE_CHARS) {
                        throw new IllegalStateException("PLANNER_RESPONSE_LIMIT");
                    }
                    text.append(buffer, 0, n);
                }
                return text.toString();
            }
        } finally {
            connection.disconnect();
            synchronized (this) {
                if (activeConnection == connection) activeConnection = null;
            }
        }
    }
}
