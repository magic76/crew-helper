package com.crewpocket.helper;

/** Pure event -> state projection. No Android, network or tool side effects. */
final class AgentReducer {
    private AgentReducer() {}

    static AgentState reduce(AgentState current, AgentEvent event) {
        if (current == null) current = AgentState.idle();
        if (event == null) return current;

        final long now = event.timestampMs;
        long generation = event.generation >= 0 ? event.generation : current.generation;
        String goalId = choose(event.goalId, current.goalId);
        String taskId = choose(event.taskId, current.taskId);
        String actionId = choose(event.actionId, current.activeActionId);
        String fingerprint = choose(event.attr("fingerprint"), current.lastScreenFingerprint);
        String stableKey = choose(event.attr("stableKey"), current.lastStableScreenKey);
        String verification = current.lastVerificationStatus;
        String failure = current.lastFailureCode;
        AgentState.Phase next = current.phase;

        switch (event.type) {
            case USER_INTENT_ACCEPTED:
                next = AgentState.Phase.READY;
                actionId = "";
                verification = "";
                failure = "";
                break;
            case TOOL_QUEUED:
            case ACTION_PREFLIGHT_ALLOWED:
                if (next == AgentState.Phase.IDLE || isTerminal(next)) next = AgentState.Phase.READY;
                break;
            case ACTION_STARTED:
                next = AgentState.Phase.EXECUTING;
                verification = "";
                failure = "";
                break;
            case ACTION_EXECUTED:
                next = AgentState.Phase.WAITING_FOR_UI;
                break;
            case SCREEN_OBSERVED:
                if (next == AgentState.Phase.WAITING_FOR_UI
                        || next == AgentState.Phase.EXECUTING
                        || next == AgentState.Phase.WAITING_FOR_VERIFICATION) {
                    next = AgentState.Phase.VERIFYING;
                }
                break;
            case ACTION_VERIFICATION_PENDING:
                next = AgentState.Phase.WAITING_FOR_VERIFICATION;
                verification = "PENDING";
                failure = "";
                break;
            case ACTION_VERIFIED:
                next = AgentState.Phase.VERIFYING;
                verification = choose(event.attr("status"), "VERIFIED");
                failure = "";
                break;
            case ACTION_COMMITTED:
                next = AgentState.Phase.WAITING_FOR_MODEL;
                actionId = "";
                verification = choose(event.attr("status"), verification);
                failure = "";
                break;
            case ACTION_FAILED:
                next = AgentState.Phase.WAITING_FOR_MODEL;
                actionId = "";
                verification = choose(event.attr("status"), "FAILED");
                failure = choose(event.attr("code"), "ACTION_FAILED");
                break;
            case TOOL_RESULT_SENT:
            case MODEL_WAITING:
                if (!isTerminal(next) && next != AgentState.Phase.WAITING_FOR_VERIFICATION) {
                    next = AgentState.Phase.WAITING_FOR_MODEL;
                }
                break;
            case USER_WAITING:
            case OBSERVATION_REQUIRED:
                if (event.type == AgentEvent.Type.USER_WAITING) next = AgentState.Phase.WAITING_FOR_USER;
                else next = AgentState.Phase.WAITING_FOR_VERIFICATION;
                break;
            case USER_INTERRUPTED:
            case TASK_CANCELLED:
                next = AgentState.Phase.CANCELLED;
                actionId = "";
                failure = choose(event.attr("reason"), "CANCELLED");
                break;
            case TASK_COMPLETED:
                next = AgentState.Phase.COMPLETED;
                actionId = "";
                failure = "";
                break;
            case TASK_FAILED:
                next = AgentState.Phase.FAILED;
                actionId = "";
                failure = choose(event.attr("code"), "TASK_FAILED");
                break;
            case LOCATOR_RESOLVED:
            case LOCATOR_REJECTED:
            case DUPLICATE_IGNORED:
            case STALE_ACTION_REJECTED:
                // Diagnostic / guard events do not rewrite the current main phase.
                break;
            default:
                break;
        }

        return current.with(next, generation, goalId, taskId, actionId,
                fingerprint, stableKey, verification, failure, now);
    }

    private static boolean isTerminal(AgentState.Phase phase) {
        return phase == AgentState.Phase.COMPLETED
                || phase == AgentState.Phase.FAILED
                || phase == AgentState.Phase.CANCELLED;
    }

    private static String choose(String preferred, String fallback) {
        return preferred == null || preferred.isEmpty()
                ? (fallback == null ? "" : fallback)
                : preferred;
    }
}

