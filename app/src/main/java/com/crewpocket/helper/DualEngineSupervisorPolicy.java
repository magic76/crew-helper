package com.crewpocket.helper;

/**
 * Deterministic boundary between Think planning, Live execution and Runtime
 * verification. This class only sees allowlisted goal/tool/error codes.
 * No user speech, screen data or app content is required for a recovery check.
 */
final class DualEngineSupervisorPolicy {
    private DualEngineSupervisorPolicy() {}

    static String completionRequirement(String goal) {
        if ("APP:OPEN".equals(goal)) return "Verify requested foreground app package";
        if ("MEDIA:PLAY".equals(goal)) return "Verify actual playback, not merely search or a tap";
        if ("NAVIGATION:START".equals(goal)) return "Verify active turn-by-turn navigation";
        if ("SEARCH:RESULT".equals(goal)) return "Verify results surface after submitting search";
        if ("MESSAGE:SEND".equals(goal)) return "Verify send authorization and delivery evidence";
        if ("TEXT:TYPE_ONLY".equals(goal)) return "Verify text entered without sending";
        return "Verify the actual user goal using fresh Runtime evidence";
    }

    static String safeFailureCode(String raw) {
        if (raw == null) return "";
        switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SEARCH_NEEDS_QUERY":
            case "UNKNOWN_SEMANTIC_ACTION":
            case "APP_NOT_FOUND":
            case "LAUNCH_TARGET_NOT_OBSERVED":
            case "NO_OBSERVABLE_EFFECT":
            case "NO_PROGRESS":
            case "STEP_FAILED":
            case "SEARCH_AWAITING_RESULTS":
            case "SEARCH_COMMIT_AWAITING_RESULTS":
            case "SEARCH_RESULTS_NOT_OBSERVED":
            case "TAP_NO_EFFECT":
            case "TAP_AWAITING_EFFECT":
            case "LOCATOR_MARGIN_TOO_SMALL":
            case "NO_MATCH":
            case "PLAYBACK_NOT_CONFIRMED":
                return raw.trim().toUpperCase(java.util.Locale.ROOT);
            default:
                return "";
        }
    }

    static boolean shouldRequestRepair(
            String goal, boolean hasSafePublicPrompt,
            String runtimeTool, boolean success, boolean needsUser,
            boolean blocked, boolean cancelled, int alreadyRequested,
            String failureCode) {
        if (!"MEDIA:PLAY".equals(goal) || !hasSafePublicPrompt
                || success || needsUser || blocked || cancelled
                || alreadyRequested != 0) return false;
        if (!("phone_action".equals(runtimeTool)
                || "launch_app".equals(runtimeTool)
                || "tap_screen".equals(runtimeTool)
                || "tap_element".equals(runtimeTool)
                || "search_current_app".equals(runtimeTool)
                || "commit_search".equals(runtimeTool)
                || "press_key".equals(runtimeTool))) return false;
        return !safeFailureCode(failureCode).isEmpty();
    }

    /** Only a failed, safe, still-active tool step can cause replanning. */
    static boolean isTerminalState(String state) {
        return "DONE".equals(state) || "BLOCKED".equals(state)
                || "WAITING_USER".equals(state)
                || "NEED_USER".equals(state);
    }
}
