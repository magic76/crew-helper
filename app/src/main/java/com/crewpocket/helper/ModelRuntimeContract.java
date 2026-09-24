package com.crewpocket.helper;

import java.util.Locale;

/**
 * Canonical model-facing goal contract.
 *
 * Runtime may keep richer internal task / verification states, but Gemini Live
 * sees one goal state and one next directive. This prevents action success from
 * being mistaken for whole-goal completion.
 */
final class ModelRuntimeContract {
    static final String GOAL_IN_PROGRESS = "IN_PROGRESS";
    static final String GOAL_ANSWER_READY = "ANSWER_READY";
    static final String GOAL_DONE = "DONE";
    static final String GOAL_WAITING_RUNTIME = "WAITING_RUNTIME";
    static final String GOAL_NEED_USER = "NEED_USER";
    static final String GOAL_BLOCKED = "BLOCKED";

    static final String NEXT_CONTINUE = "CONTINUE_GOAL";
    static final String NEXT_OBSERVE = "OBSERVE";
    static final String NEXT_ANSWER = "ANSWER";
    static final String NEXT_WAIT_RUNTIME = "WAIT_RUNTIME";
    static final String NEXT_ASK_USER = "ASK_USER";
    static final String NEXT_TRY_ALTERNATIVE = "TRY_ALTERNATIVE";
    static final String NEXT_FINISH = "FINISH";
    static final String NEXT_STOP = "STOP";

    static final class Goal {
        final String state;
        final String next;
        final String requiredTool;
        final String requiredAction;

        Goal(String state, String next, String requiredTool) {
            this(state, next, requiredTool, "");
        }

        Goal(
                String state,
                String next,
                String requiredTool,
                String requiredAction) {
            this.state = safeToken(state, GOAL_IN_PROGRESS);
            this.next = safeToken(next, NEXT_CONTINUE);
            this.requiredTool = safeTool(requiredTool);
            this.requiredAction = safeAction(requiredAction);
        }
    }

    private ModelRuntimeContract() {}

    static Goal deriveGoal(
            String taskState,
            String nextRequirement,
            String modelStatus,
            String reason,
            String fallbackNext) {
        String task = upper(taskState);
        String requirement = upper(nextRequirement);
        String status = upper(modelStatus);
        String why = upper(reason);
        String fallback = upper(fallbackNext);

        if ("ANSWER_READY".equals(task)
                || requirement.contains("ANSWER_IF_SUFFICIENT")) {
            return new Goal(GOAL_ANSWER_READY, NEXT_ANSWER, "");
        }
        if ("DONE".equals(task) || "NONE".equals(requirement)) {
            return new Goal(GOAL_DONE, NEXT_FINISH, "");
        }
        if ("WAITING_BACKGROUND".equals(task)) {
            return new Goal(GOAL_WAITING_RUNTIME, NEXT_WAIT_RUNTIME, "");
        }
        if ("WAITING_USER".equals(task)
                || "NEED_USER".equals(task)
                || "NEED_USER".equals(status)) {
            return new Goal(GOAL_NEED_USER, NEXT_ASK_USER, "");
        }
        if ("BLOCKED".equals(task)) {
            return new Goal(GOAL_BLOCKED, NEXT_STOP, "");
        }

        if ("CONTINUE_GOAL".equals(requirement)) {
            return new Goal(GOAL_IN_PROGRESS, NEXT_CONTINUE, "");
        }
        if ("COMMIT_SEARCH".equals(requirement)) {
            return new Goal(
                    GOAL_IN_PROGRESS,
                    NEXT_CONTINUE,
                    "phone_action",
                    "COMMIT_SEARCH");
        }
        if (requirement.contains("INSPECT_UI")) {
            return new Goal(GOAL_IN_PROGRESS, NEXT_OBSERVE, "inspect_ui");
        }
        if (requirement.contains("SEND_TEXT_OR_CONTINUE_CONVERSATION_LOOP")) {
            return new Goal(GOAL_IN_PROGRESS, NEXT_CONTINUE, "");
        }
        if (requirement.contains("START_CONVERSATION_LOOP")) {
            return new Goal(
                    GOAL_IN_PROGRESS, NEXT_CONTINUE, "start_conversation_loop");
        }

        if ("WAIT".equals(status)) {
            if ("WAIT_FOR_RUNTIME".equals(fallback)) {
                return new Goal(
                        GOAL_WAITING_RUNTIME, NEXT_WAIT_RUNTIME, "");
            }
            return new Goal(GOAL_IN_PROGRESS, NEXT_OBSERVE, "");
        }

        if ("FAILED".equals(status)) {
            if ("WAIT_SETUP_FAILED".equals(why)) {
                return new Goal(GOAL_NEED_USER, NEXT_ASK_USER, "");
            }
            if ("POLICY_BLOCKED".equals(why)
                    || "SCOPE_BLOCKED".equals(why)
                    || "STALE_OR_CANCELLED".equals(why)
                    || "SEND_NOT_AUTHORIZED".equals(why)) {
                return new Goal(GOAL_BLOCKED, NEXT_STOP, "");
            }
            if ("DELEGATED_SESSION_REQUIRED".equals(why)) {
                return new Goal(
                        GOAL_IN_PROGRESS, NEXT_CONTINUE,
                        "start_conversation_loop");
            }
            return new Goal(
                    GOAL_IN_PROGRESS, NEXT_TRY_ALTERNATIVE, "");
        }

        // Action success is deliberately NOT whole-goal completion.
        // EVIDENCE_AVAILABLE / IN_PROGRESS both mean continue reasoning from
        // current evidence unless Runtime explicitly emitted DONE/ANSWER_READY.
        if ("EVIDENCE_AVAILABLE".equals(task)
                || "IN_PROGRESS".equals(task)
                || "DONE".equals(status)
                || task.isEmpty()) {
            return new Goal(
                    GOAL_IN_PROGRESS,
                    normalizeFallback(fallback),
                    "");
        }

        return new Goal(
                GOAL_IN_PROGRESS,
                normalizeFallback(fallback),
                "");
    }

    static String actionState(String modelStatus) {
        return actionState(modelStatus, false, false, false, false);
    }

    static String actionState(
            String modelStatus,
            boolean success,
            boolean verifiedCommit,
            boolean pendingVerification,
            boolean blocked) {
        String status = upper(modelStatus);
        if (blocked || "NEED_USER".equals(status)) return "BLOCKED";
        if (pendingVerification) return "PENDING";
        if (verifiedCommit || success || "DONE".equals(status)) {
            return "VERIFIED";
        }
        if ("WAIT".equals(status)) return "PENDING";
        return "FAILED";
    }

    private static String normalizeFallback(String value) {
        if ("OBSERVE".equals(value)) return NEXT_OBSERVE;
        if ("ASK_USER".equals(value)) return NEXT_ASK_USER;
        if ("WAIT_FOR_RUNTIME".equals(value)) return NEXT_WAIT_RUNTIME;
        if ("TRY_DIFFERENT_METHOD".equals(value)) return NEXT_TRY_ALTERNATIVE;
        if ("STOP".equals(value)
                || "STOP_AND_WAIT_FOR_USER".equals(value)) {
            return NEXT_STOP;
        }
        return NEXT_CONTINUE;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeToken(String value, String fallback) {
        String out = upper(value);
        return out.matches("[A-Z0-9_:-]{1,64}") ? out : fallback;
    }

    private static String safeTool(String value) {
        String out = value == null ? "" : value.trim();
        return out.matches("[a-z0-9_]{1,64}") ? out : "";
    }

    private static String safeAction(String value) {
        String out = upper(value);
        return out.matches("[A-Z0-9_]{1,64}") ? out : "";
    }
}
