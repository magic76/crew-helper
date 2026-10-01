package com.crewpocket.helper;

import java.util.Locale;

/**
 * Decides whether Agent model output may be exposed to the user.
 *
 * This is intentionally separate from task completion: WAITING_USER/NEED_USER
 * are conversational boundaries that must be audible, but they are not terminal
 * task states. IN_PROGRESS and background waits remain silent.
 */
final class AgentSpeechGatePolicy {
    private AgentSpeechGatePolicy() {}

    static boolean isUserInputBoundary(String taskState) {
        String state = normalize(taskState);
        return "WAITING_USER".equals(state)
                || "NEED_USER".equals(state);
    }

    static boolean isSilentWait(String taskState) {
        return "WAITING_BACKGROUND".equals(normalize(taskState));
    }

    static boolean shouldWithhold(
            boolean taskActive,
            boolean awaitingModel,
            String taskState,
            String lastToolName,
            boolean requiresPostActionInspection,
            boolean hasBlockedReason,
            int mutationActions) {
        if (!taskActive) return false;

        // Asking the human is a visible conversational event, not completion.
        // Never suppress the one concise question needed to unblock the task.
        if (isUserInputBoundary(taskState)) return false;

        // A real blocker is itself user-relevant evidence. Do not let a stale
        // verification flag suppress the concise explanation Runtime requested.
        if (hasBlockedReason
                || "BLOCKED".equals(normalize(taskState))) {
            return false;
        }

        // Background waits are Runtime-owned. There is nothing useful to say
        // until the external condition wakes the task.
        if (isSilentWait(taskState)) return true;

        if (!awaitingModel) return false;
        if (requiresPostActionInspection) return true;

        boolean completionReady =
                AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        taskState,
                        lastToolName,
                        requiresPostActionInspection,
                        hasBlockedReason,
                        mutationActions);

        // Once the phone has been mutated, intermediate narration stays hidden
        // until Runtime has terminal/answer-ready evidence.
        return mutationActions > 0 && !completionReady;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
