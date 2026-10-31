package com.crewpocket.helper;

/**
 * Process-local, privacy-safe Live protocol counters. Unlike AgentInspector's
 * persisted task snapshot, this works even when the model never calls a tool.
 * No transcript text, targets, arguments, call IDs, screen data or replies.
 */
final class LivePipelineDiagnostic {
    private static long sessionStartedAtMs;
    private static long activeIntentGeneration = -1L;
    private static String goal = "NONE";
    private static String interaction = "UNKNOWN";
    private static String lastTool = "NONE";
    private static String lastAction = "NONE";
    private static int finalizedIntents;
    private static int incomingToolCalls;
    private static int executedToolCalls;
    private static int toolResponses;
    private static int cancelledQueuedCalls;
    private static int modelOutputFrames;
    private static int idleWithoutTool;
    private static int incompleteUtterances;
    private static int interactionIdleFrames;
    private static int toolCallsForIntent;
    private static int responsesForIntent;

    private LivePipelineDiagnostic() {}

    static synchronized void startSession(long nowMs) {
        sessionStartedAtMs = nowMs;
        activeIntentGeneration = -1L;
        goal = "NONE";
        interaction = "UNKNOWN";
        lastTool = "NONE";
        lastAction = "NONE";
        finalizedIntents = 0;
        incomingToolCalls = 0;
        executedToolCalls = 0;
        toolResponses = 0;
        cancelledQueuedCalls = 0;
        modelOutputFrames = 0;
        idleWithoutTool = 0;
        incompleteUtterances = 0;
        interactionIdleFrames = 0;
        toolCallsForIntent = 0;
        responsesForIntent = 0;
    }

    static synchronized void onIntent(long generation, String rawGoal) {
        activeIntentGeneration = generation;
        goal = allowedGoal(rawGoal);
        finalizedIntents++;
        toolCallsForIntent = 0;
        responsesForIntent = 0;
    }

    static synchronized void onFrame(String status, boolean turnComplete,
            boolean modelOutput, boolean hasToolCalls) {
        String normalized = status == null ? "" : status.trim();
        if ("IN_PROGRESS".equalsIgnoreCase(normalized)) interaction = "IN_PROGRESS";
        if ("IDLE".equalsIgnoreCase(normalized)) {
            interaction = "IDLE";
            interactionIdleFrames++;
            if (activeIntentGeneration >= 0 && toolCallsForIntent == 0
                    && !hasToolCalls) idleWithoutTool++;
        }
        if (turnComplete && "IN_PROGRESS".equals(interaction)) {
            incompleteUtterances++;
        }
        if (modelOutput) modelOutputFrames++;
    }

    static synchronized void onToolFrame(String name, String rawAction) {
        incomingToolCalls++;
        toolCallsForIntent++;
        lastTool = allowedTool(name);
        lastAction = allowedAction(rawAction);
    }

    static synchronized void onExecution() {
        executedToolCalls++;
    }

    static synchronized void onToolResponse() {
        toolResponses++;
        responsesForIntent++;
    }

    static synchronized void onCancelledQueued(int count) {
        cancelledQueuedCalls += Math.max(0, count);
    }

    static synchronized String report() {
        if (sessionStartedAtMs == 0) {
            return "Live pipeline (current process): no session recorded";
        }
        return "Live pipeline (current process, not historical Inspector tasks):\n"
                + "Finalized intents=" + finalizedIntents
                + " · current generation=" + activeIntentGeneration
                + " · goal=" + goal + "\n"
                + "Interaction=" + interaction
                + " · intermediate utterances=" + incompleteUtterances
                + " · IDLE frames=" + interactionIdleFrames + "\n"
                + "Model output frames=" + modelOutputFrames
                + " · toolCall frames=" + incomingToolCalls
                + " · executions=" + executedToolCalls
                + " · tool responses=" + toolResponses
                + " · queued cancellations=" + cancelledQueuedCalls + "\n"
                + "Current intent: tools=" + toolCallsForIntent
                + " responses=" + responsesForIntent
                + " · IDLE-without-tool=" + idleWithoutTool + "\n"
                + "Last requested: " + lastTool + "/" + lastAction;
    }

    private static String allowedGoal(String raw) {
        if ("APP:OPEN".equals(raw) || "MEDIA:PLAY".equals(raw)
                || "NAVIGATION:START".equals(raw) || "SEARCH:RESULT".equals(raw)
                || "MESSAGE:SEND".equals(raw) || "TEXT:TYPE_ONLY".equals(raw)) {
            return raw;
        }
        return raw == null || raw.isEmpty() ? "NONE" : "OTHER";
    }

    private static String allowedTool(String raw) {
        if ("phone_action".equals(raw) || "inspect_ui".equals(raw)
                || "send_text".equals(raw) || "wait_then_action".equals(raw)) {
            return raw;
        }
        return raw == null || raw.isEmpty() ? "NONE" : "OTHER";
    }

    private static String allowedAction(String raw) {
        if (raw == null) return "NONE";
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("OPEN_APP".equals(value) || "TAP".equals(value)
                || "SEARCH".equals(value) || "COMMIT_SEARCH".equals(value)
                || "TYPE".equals(value) || "SCROLL".equals(value)
                || "BACK".equals(value) || "HOME".equals(value)
                || "TAKE_PHOTO".equals(value)) return value;
        return value.isEmpty() ? "NONE" : "OTHER";
    }
}
