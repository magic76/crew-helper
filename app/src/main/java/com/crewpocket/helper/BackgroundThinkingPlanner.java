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
    private boolean delivered;
    private boolean stopped;

    private final class Attempt implements GeminiLiveConnection.Listener {
        final long intent;
        final long epoch;
        final String request;
        final GeminiLiveConnection connection;
        final StringBuilder transcript = new StringBuilder();
        ScheduledFuture<?> timeout;
        boolean sentInput;

        Attempt(long intent, long epoch, String request) {
            this.intent = intent;
            this.epoch = epoch;
            this.request = request;
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
                if ("IDLE".equalsIgnoreCase(status.trim())) {
                    List<String> tips =
                            DualEngineTranscriptPolicy.advisorySteps(transcript.toString());
                    if (tips.isEmpty()) {
                        failed(this);
                    } else {
                        JSONArray accepted = new JSONArray();
                        for (String tip : tips) accepted.put(tip);
                        succeeded(this, accepted);
                    }
                }
                // IN_PROGRESS and turnComplete are NOT completion signals.
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
        delivered = false;
        long epoch = ++requestEpoch;
        if (!enabled || stopped) return;
        String eligibleRequest = DualEnginePlanningPolicy.eligiblePrompt(text, goal);
        if (eligibleRequest.isEmpty()) return;

        // Exactly one short-lived background connection for the eligible
        // instruction. No microphone, tool schema or audio playback attached.
        Attempt attempt = new Attempt(newGeneration, epoch, eligibleRequest);
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
            out.put("nonAuthoritative", true);
            out.put("steps", new JSONArray(readySteps.toString()));
            out.put("rule", "Unverified spoken advice only. Runtime goal.next, requiredTool, screen evidence and authorization always win.");
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
                "You are the read-only, behind-the-scenes reasoning assistant for a "
                + "public music playback request. Think through the goal before "
                + "speaking. Speak ONLY two or three short numbered semantic "
                + "steps for another assistant: search for the artist or song, "
                + "inspect fresh results, and tap a verified Play control. "
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
                                "Provide advisory steps only for: " + request)));
        return new JSONObject().put("clientContent", new JSONObject()
                .put("turns", new JSONArray().put(turn))
                .put("turnComplete", true)).toString();
    }
}
