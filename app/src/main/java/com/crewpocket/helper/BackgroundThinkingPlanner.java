package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Optional SECOND Gemini Live socket used only for read-only planning.
 * Standard Live remains the exclusive microphone, audio playback and tool owner.
 *
 * The Extended Thinking API produces AUDIO, not structured JSON. We discard
 * its audio and collect its outputAudioTranscription. Nothing is handed to
 * Standard until server interactionStatus=IDLE; a spoken turnComplete is not
 * a reasoning-complete boundary.
 */
final class BackgroundThinkingPlanner {
    private static final String MODEL = "gemini-3.8-live-extended-thinking";
    private static final int MAX_RAW_FRAME_CHARS = 1_800_000;
    private static final int MAX_TRANSCRIPT_CHARS = 950;
    private static final long DEADLINE_MS = 18_000L;

    private final boolean enabled;
    private final String apiKey;
    private final ExecutorService connector;
    private final ScheduledExecutorService scheduler;
    private Attempt active;
    private long generation = -1L;
    private long requestEpoch;
    private String goal = "";
    private JSONArray readySteps;
    private String readyPhase = "";
    private String safePublicRequest = "";
    private int repairRequests;
    private boolean delivered;
    private boolean stopped;

    private final class Attempt implements GeminiLiveConnection.Listener {
        final long intent;
        final long epoch;
        final String request;
        final boolean repair;
        final GeminiLiveConnection connection;
        final StringBuilder transcript = new StringBuilder();
        ScheduledFuture<?> timeout;
        boolean sentInput;

        Attempt(long intent, long epoch, String request, boolean repair) {
            this.intent = intent;
            this.epoch = epoch;
            this.request = request;
            this.repair = repair;
            this.connection = new GeminiLiveConnection(apiKey, this);
        }

        @Override public void onOpened() {
            try {
                if (!isCurrent(this) || !connection.send(setup())) {
                    failed(this);
                }
            } catch (Exception ignored) {
                failed(this);
            }
        }

        @Override public void onFrame(String raw, boolean binary) {
            if (!isCurrent(this)) return;
            try {
                if (raw == null || raw.length() > MAX_RAW_FRAME_CHARS) {
                    failed(this);
                    return;
                }
                JSONObject frame = new JSONObject(raw);
                if (frame.has("setupComplete") || frame.has("setup_complete")) {
                    if (sentInput) return;
                    sentInput = true;
                    if (!connection.send(userTurn(request))) failed(this);
                    return;
                }

                // No tool declarations are supplied to this session. Reject
                // unsolicited function calls instead of granting tool access.
                if (frame.has("toolCall") || frame.has("tool_call")) {
                    failed(this);
                    return;
                }
                JSONObject server = frame.optJSONObject("serverContent");
                if (server == null) server = frame.optJSONObject("server_content");
                if (server == null) return;

                JSONObject transcription = server.optJSONObject("outputTranscription");
                if (transcription == null) {
                    transcription = server.optJSONObject("output_transcription");
                }
                if (transcription != null) {
                    String text = transcription.optString("text", "");
                    if (!text.isEmpty()) {
                        if (transcript.length() + text.length() > MAX_TRANSCRIPT_CHARS) {
                            failed(this);
                            return;
                        }
                        if (transcript.length() > 0) transcript.append(' ');
                        transcript.append(text.trim());
                    }
                }
                if (server.optBoolean("interrupted", false)) {
                    failed(this);
                    return;
                }
                String status = server.optString("interactionStatus",
                        server.optString("interaction_status",
                                frame.optString("interactionStatus",
                                        frame.optString("interaction_status", ""))));
                boolean idle = "IDLE".equalsIgnoreCase(status.trim());
                // This background socket declares NO tools and its only output
                // is unplayed audio. A completed speech turn with >=2 safe
                // actionable steps can be used without interrupting foreground
                // Live, even if Extended never emits IDLE.
                boolean speechComplete = server.optBoolean("turnComplete",
                        server.optBoolean("turn_complete", false));
                if (idle || speechComplete) {
                    List<String> tips =
                            DualEngineTranscriptPolicy.advisorySteps(transcript.toString());
                    if (tips.size() >= 2) {
                        JSONArray accepted = new JSONArray();
                        for (String tip : tips) accepted.put(tip);
                        succeeded(this, accepted);
                    } else if (idle) {
                        failed(this);
                    }
                }
            } catch (Exception ignored) {
                failed(this);
            }
        }

        @Override public void onClosed(int code, String reason) {
            failed(this);
        }

        @Override public void onFailure(String detail, Throwable error) {
            failed(this);
        }
    }

    BackgroundThinkingPlanner(String apiKey, boolean enabled) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.enabled = enabled && this.apiKey.length() >= 20;
        this.connector = this.enabled
                ? Executors.newSingleThreadExecutor(r -> daemon(r, "crew-extended-planner"))
                : null;
        this.scheduler = this.enabled
                ? Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "crew-extended-deadline"))
                : null;
    }

    private static Thread daemon(Runnable job, String name) {
        Thread t = new Thread(job, name);
        t.setDaemon(true);
        return t;
    }

    synchronized void newIntent(long newGeneration, String text, String newGoal) {
        discardActiveLocked();
        generation = newGeneration;
        goal = newGoal == null ? "" : newGoal;
        readySteps = null;
        readyPhase = "";
        delivered = false;
        repairRequests = 0;
        safePublicRequest = enabled && !stopped
                ? DualEnginePlanningPolicy.safePublicMediaPrompt(text, goal)
                : "";
        long epoch = ++requestEpoch;
        if (!enabled || stopped) return;
        String eligibleRequest = DualEnginePlanningPolicy.eligiblePrompt(text, goal);
        LivePipelineDiagnostic.onSupervisorIntent(eligibleRequest.isEmpty());
        if (eligibleRequest.isEmpty()) return;

        // Exactly one short-lived background connection for the eligible
        // instruction. No microphone, tool schema or audio playback attached.
        launchAttempt(new Attempt(newGeneration, epoch, eligibleRequest, false));
    }

    /**
     * Only one safe public media failure per intent may request fresh planning.
     * This never holds up Standard Live, never changes goal or authorization
     * and cannot send arbitrary Android Runtime data to the second model.
     */
    synchronized void onToolOutcome(long intent, String currentGoal,
            String tool, JSONObject result) {
        if (!enabled || stopped || generation != intent
                || !goal.equals(currentGoal) || result == null) return;
        String taskState = result.optString("taskState", "");
        if (DualEngineSupervisorPolicy.isTerminalState(taskState)) {
            discardActiveLocked();
            readySteps = null;
            return;
        }
        boolean success = result.optBoolean("success", false)
                && !"STEP_FAILED".equals(result.optString("stepResult", ""));
        String code = result.optString("verificationCode", "");
        if (DualEngineSupervisorPolicy.safeFailureCode(code).isEmpty()) {
            code = result.optString("error", "");
        }
        if (DualEngineSupervisorPolicy.safeFailureCode(code).isEmpty()
                && "STEP_FAILED".equals(result.optString("stepResult", ""))) {
            code = "STEP_FAILED";
        }
        boolean blocked = result.optBoolean("blockedByRuntime", false)
                || result.optBoolean("agentStopped", false)
                || "BLOCKED".equals(taskState);
        if (!DualEngineSupervisorPolicy.shouldRequestRepair(
                currentGoal, !safePublicRequest.isEmpty(), tool, success,
                result.optBoolean("needsUser", false), blocked,
                result.optBoolean("cancelled", false), repairRequests, code)) {
            return;
        }
        String safeCode = DualEngineSupervisorPolicy.safeFailureCode(code);
        repairRequests++;
        discardActiveLocked();
        readySteps = null;
        readyPhase = "";
        delivered = false;
        long epoch = ++requestEpoch;
        LivePipelineDiagnostic.onSupervisorRepairRequested();
        String request = safePublicRequest
                + ". PREVIOUS STEP FAILED (" + safeCode
                + "). Suggest a DIFFERENT safe semantic route, using fresh "
                + "UI evidence and never retrying the same failed tap.";
        launchAttempt(new Attempt(intent, epoch, request, true));
    }

    private void launchAttempt(Attempt attempt) {
        active = attempt;
        LivePipelineDiagnostic.onPlannerStarted();
        attempt.timeout = scheduler.schedule(() -> failed(attempt),
                DEADLINE_MS, TimeUnit.MILLISECONDS);
        connector.execute(() -> {
            if (!isCurrent(attempt)) return;
            try {
                attempt.connection.start();
            } catch (Exception ignored) {
                failed(attempt);
            }
        });
    }

    synchronized JSONObject peekIfReady(long requestGeneration, String currentGoal) {
        if (!enabled || stopped || delivered || readySteps == null
                || generation != requestGeneration
                || !goal.equals(currentGoal)) return null;
        JSONObject out = new JSONObject();
        try {
            out.put("source", "3.8_extended_live_transcript");
            out.put("mode", readyPhase);
            out.put("nonAuthoritative", true);
            out.put("goal", goal);
            out.put("completionRequirement",
                    DualEngineSupervisorPolicy.completionRequirement(goal));
            out.put("steps", new JSONArray(readySteps.toString()));
            out.put("rule", "Supervisor advice is non-executable. Live chooses tools from the fresh screen; Runtime permissions and verified goal completion always win.");
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized void markDelivered(long requestGeneration) {
        if (generation != requestGeneration || readySteps == null || delivered) return;
        delivered = true;
        LivePipelineDiagnostic.onPlannerDelivered();
    }

    synchronized void invalidate() {
        ++requestEpoch;
        discardActiveLocked();
        readySteps = null;
        safePublicRequest = "";
        readyPhase = "";
        delivered = true;
    }

    synchronized void stop() {
        if (stopped) return;
        stopped = true;
        invalidate();
        if (connector != null) connector.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
    }

    private synchronized boolean isCurrent(Attempt candidate) {
        return !stopped && active == candidate
                && candidate.epoch == requestEpoch
                && candidate.intent == generation;
    }

    private void succeeded(Attempt attempt, JSONArray tips) {
        synchronized (this) {
            if (!isCurrent(attempt)) return;
            active = null;
            if (attempt.timeout != null) attempt.timeout.cancel(false);
            readySteps = tips;
            readyPhase = attempt.repair ? "RECOVERY" : "PLAN";
            LivePipelineDiagnostic.onPlannerReady();
        }
        attempt.connection.stop();
    }

    private void failed(Attempt attempt) {
        synchronized (this) {
            if (!isCurrent(attempt)) return;
            active = null;
            if (attempt.timeout != null) attempt.timeout.cancel(false);
            LivePipelineDiagnostic.onPlannerFailed();
        }
        attempt.connection.stop();
    }

    private void discardActiveLocked() {
        Attempt old = active;
        active = null;
        if (old != null) {
            if (old.timeout != null) old.timeout.cancel(false);
            old.connection.stop();
        }
    }

    private static String setup() throws Exception {
        String instructions =
                "You are the read-only task supervisor for a "
                + "public music playback request. Think through the goal before "
                + "speaking. Speak ONLY two or three short numbered semantic "
                + "steps for a separate Live Executor. On a failed step, "
                + "propose an ALTERNATIVE and a fresh verification approach. "
                + "For initial plans, search the song, inspect results, and "
                + "verify playback rather than assuming taps succeeded. "
                + "You have no phone access and must NOT claim any action has "
                + "happened. Never invent screen labels, coordinates, tool "
                + "results, authorization, or send messages. Do not ask a question. "
                + "No conversation fillers. Keep the spoken response under 100 words.";
        JSONObject generation = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("thinkingConfig", new JSONObject()
                        .put("thinkingLevel", "MEDIUM"));
        JSONObject payload = new JSONObject().put("setup", new JSONObject()
                .put("model", "models/" + MODEL)
                .put("generationConfig", generation)
                .put("outputAudioTranscription", new JSONObject())
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(
                                new JSONObject().put("text", instructions)))));
        return payload.toString();
    }

    private static String userTurn(String request) throws Exception {
        JSONObject turn = new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(
                        new JSONObject().put("text",
                                "Provide semantic supervisor plan steps only. "
                                + "No coordinates, no claims of success. Task: "
                                + request)));
        return new JSONObject().put("clientContent", new JSONObject()
                .put("turns", new JSONArray().put(turn))
                .put("turnComplete", true)).toString();
    }
}
