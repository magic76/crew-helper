package com.crewpocket.helper;

/**
 * Decides whether a Gemini Live frame contains substantive model progress.
 *
 * A modelTurn envelope by itself is only protocol framing. It must not cancel
 * the Agent response watchdog unless the model actually produced a tool call,
 * transcript text, model text, or audio.
 */
final class LiveModelProgressPolicy {
    private LiveModelProgressPolicy() {}

    static boolean hasSubstantiveProgress(
            boolean hasToolCalls,
            String outputTranscript,
            boolean modelTurnPresent,
            boolean hasModelAudio,
            boolean hasModelText) {
        if (hasToolCalls) return true;
        if (outputTranscript != null
                && !outputTranscript.trim().isEmpty()) {
            return true;
        }
        if (!modelTurnPresent) return false;
        return hasModelAudio || hasModelText;
    }

    static boolean shouldClearAgentWatchdog(
            boolean hasToolCalls,
            boolean substantiveModelProgress,
            boolean agentAwaitingIncompleteAction) {
        if (hasToolCalls) return true;
        if (agentAwaitingIncompleteAction) return false;
        return substantiveModelProgress;
    }
}
