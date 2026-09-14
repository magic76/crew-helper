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
 * Thin client over Crew Pocket's existing /api/chat provider backend.
 *
 * 0130 deliberately reuses the existing Codex authentication/provider stack.
 * Crew Pocket is only the brain here: it never receives Crew Helper's private
 * Runtime bridge capability token and never executes Android actions directly.
 */
final class CrewPocketPlannerClient {
    static final String DEFAULT_BASE_URL = "http://127.0.0.1:8000";
    static final String DEFAULT_MODEL = "gpt-5.6-terra";
    static final String FALLBACK_MODEL = "gpt-5.6-luna";
    static final String DEFAULT_EFFORT = "medium";

    interface Listener {
        void onPlannerStatus(String text);
    }

    static final class Result {
        final SmartPlannerDecision decision;
        final String conversationId;
        final String model;
        final boolean degraded;

        Result(SmartPlannerDecision decision, String conversationId,
               String model, boolean degraded) {
            this.decision = decision;
            this.conversationId = conversationId == null ? "" : conversationId;
            this.model = model == null ? "" : model;
            this.degraded = degraded;
        }
    }

    private final String baseUrl;
    private final Listener listener;

    CrewPocketPlannerClient(Listener listener) {
        this(DEFAULT_BASE_URL, listener);
    }

    CrewPocketPlannerClient(String baseUrl, Listener listener) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = clean.isEmpty() ? DEFAULT_BASE_URL : clean.replaceAll("/+$", "");
        this.listener = listener;
    }

    Result next(String goal, JSONObject screen, JSONArray recent,
                String conversationId, int plannerCall, int mutations,
                long elapsedMs) throws Exception {
        String prompt = buildPrompt(goal, screen, recent, plannerCall, mutations, elapsedMs);
        try {
            return request(prompt, conversationId, DEFAULT_MODEL, DEFAULT_EFFORT, false);
        } catch (Exception primary) {
            if (!isFallbackEligible(primary)) throw primary;
            if (listener != null) listener.onPlannerStatus(
                    "Terra 暫時不可用，Planner 降級使用 Luna");
            // Start a fresh fallback planner thread so a failed primary turn does
            // not leave an ambiguous provider conversation state.
            return request(prompt, "", FALLBACK_MODEL, "medium", true);
        }
    }

    private Result request(String prompt, String conversationId, String model,
                           String effort, boolean degraded) throws Exception {
        JSONObject body = new JSONObject()
                .put("provider", "codex")
                .put("model", model)
                .put("effort", effort)
                .put("role", "phone_planner")
                .put("prompt", prompt);
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            body.put("conversation_id", conversationId.trim());
        }

        HttpURLConnection connection = (HttpURLConnection)
                new URL(baseUrl + "/api/chat").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(90_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "text/event-stream");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(payload.length));
        OutputStream out = connection.getOutputStream();
        try { out.write(payload); out.flush(); }
        finally { out.close(); }

        int httpStatus = connection.getResponseCode();
        if (httpStatus < 200 || httpStatus >= 300) {
            String error = readAll(connection.getErrorStream());
            connection.disconnect();
            throw new IllegalStateException("CREW_POCKET_HTTP_" + httpStatus + ":" + error);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8));
        String event = "";
        String resolvedConversation = conversationId == null ? "" : conversationId;
        String response = "";
        String error = "";
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Planner task cancelled");
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JSONObject json;
                try { json = new JSONObject(data); }
                catch (Exception ignored) { continue; }
                if ("init".equals(event)) {
                    String id = json.optString("conversation_id", "");
                    if (!id.isEmpty()) resolvedConversation = id;
                } else if ("done".equals(event)) {
                    String id = json.optString("conversation_id", "");
                    if (!id.isEmpty()) resolvedConversation = id;
                    response = json.optString("response", "");
                    error = json.optString("error", "");
                    break;
                }
            }
        } finally {
            reader.close();
            connection.disconnect();
        }

        if (!error.isEmpty()) throw new IllegalStateException("PLANNER_PROVIDER_ERROR:" + error);
        if (response.trim().isEmpty()) throw new IllegalStateException("PLANNER_EMPTY_RESPONSE");
        SmartPlannerDecision decision = SmartPlannerDecision.parse(response);
        return new Result(decision, resolvedConversation, model, degraded);
    }

    private static String buildPrompt(String goal, JSONObject screen, JSONArray recent,
                                      int plannerCall, int mutations, long elapsedMs) {
        JSONObject context = new JSONObject();
        try {
            context.put("goal", goal == null ? "" : goal.trim())
                    .put("screen", screen == null ? new JSONObject() : screen)
                    .put("recent", recent == null ? new JSONArray() : recent)
                    .put("budget", new JSONObject()
                            .put("plannerCall", plannerCall)
                            .put("plannerCallMax", SmartPlannerPolicy.MAX_PLANNER_CALLS)
                            .put("mutations", mutations)
                            .put("mutationMax", SmartPlannerPolicy.MAX_MUTATIONS)
                            .put("elapsedMs", elapsedMs));
        } catch (Exception ignored) {}

        return "You are Crew Pocket Smart Planner for an Android phone.\n"
                + "You are a PLANNER ONLY. Do not use shell, filesystem, web, browser, or any provider tool. "
                + "Do not execute anything yourself. Return exactly ONE JSON object and no markdown.\n\n"
                + "Crew Helper Runtime owns execution, selectors, verification, authorization and safety. "
                + "You may choose only the next semantic action. Never output coordinates. Never choose SEND, payment, purchase, OTP, password or credential actions. TYPE only fills the currently visible editable field and NEVER submits.\n\n"
                + "Allowed decisions:\n"
                + "{\"decision\":\"ACTION\",\"action\":\"OPEN_APP|TAP|TYPE|SEARCH|COMMIT_SEARCH|SCROLL|BACK|HOME\","
                + "\"target\":\"\",\"elementId\":\"\",\"text\":\"\",\"direction\":\"forward|backward|left|right\",\"distance\":\"short|normal|long|page\",\"message\":\"short reason\"}\n"
                + "{\"decision\":\"OBSERVE\",\"message\":\"why another observation is needed\"}\n"
                + "{\"decision\":\"NEED_USER\",\"message\":\"one concise question or required manual step\"}\n"
                + "{\"decision\":\"DONE\",\"message\":\"concise result\"}\n"
                + "{\"decision\":\"FAILED\",\"message\":\"concise blocker\"}\n\n"
                + "Rules: TAP must use an element id that exists in current screen.elements. OPEN_APP uses target. SEARCH uses text and is a Runtime search transaction. COMMIT_SEARCH only submits a current search field, never a chat message. SCROLL direction is content direction: forward means later/below, backward means earlier/above. If the goal is already satisfied, return DONE. If the current screen is insufficient, choose OBSERVE. If a safe next step cannot be determined, return NEED_USER rather than guessing.\n\n"
                + "Current task context:\n" + context.toString();
    }

    private static boolean isFallbackEligible(Exception error) {
        String text = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase();
        return text.contains("429")
                || text.contains("rate")
                || text.contains("limit")
                || text.contains("quota")
                || text.contains("unavailable")
                || text.contains("model")
                || text.contains("capacity");
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        try { while ((line = reader.readLine()) != null) out.append(line); }
        finally { reader.close(); }
        return out.toString();
    }
}
