package com.crewpocket.helper;

import java.util.Locale;

/**
 * Runtime-owned canonical identity for reflection lessons.
 *
 * Gemini may describe a goal in free text, but that text must never be used as
 * the primary database key for known Runtime failure/recovery patterns. These
 * categories let repeated evidence accumulate reliably across wording changes.
 */
final class ReflectionGoalCategory {
    static final String UI_TARGET_RESOLUTION = "UI_TARGET_RESOLUTION";
    static final String ACTION_FAILURE_RECOVERY = "ACTION_FAILURE_RECOVERY";
    static final String SEARCH_RESULT_SELECTION = "SEARCH_RESULT_SELECTION";
    static final String ROUTE_MODE_SELECTION = "ROUTE_MODE_SELECTION";
    static final String POST_ACTION_VERIFICATION = "POST_ACTION_VERIFICATION";
    static final String SCREEN_STATE_RECOVERY = "SCREEN_STATE_RECOVERY";

    private ReflectionGoalCategory() {}

    static String classify(boolean hasRouteModeAction,
                           boolean hasUiTargetFailure,
                           boolean hasSearchEvidence,
                           boolean hasScreenRecovery,
                           boolean hasVerificationFailure,
                           boolean hasFailureOrPartial) {
        if (hasRouteModeAction) return ROUTE_MODE_SELECTION;
        if (hasUiTargetFailure) return UI_TARGET_RESOLUTION;
        if (hasSearchEvidence) return SEARCH_RESULT_SELECTION;
        if (hasScreenRecovery) return SCREEN_STATE_RECOVERY;
        if (hasVerificationFailure) return POST_ACTION_VERIFICATION;
        if (hasFailureOrPartial) return ACTION_FAILURE_RECOVERY;
        return "";
    }

    static String fromLegacyGoal(String goalPattern) {
        String goal = normalize(goalPattern);
        if (goal.isEmpty()) return "";

        if (containsAny(goal,
                "route mode", "travel mode", "transport mode", "driving mode",
                "walking mode", "cycling mode")) {
            return ROUTE_MODE_SELECTION;
        }
        if (containsAny(goal,
                "finding ui", "find ui", "finding target", "locating ui",
                "locate ui", "ui target", "ui element", "target element",
                "target not found")) {
            return UI_TARGET_RESOLUTION;
        }
        if (containsAny(goal,
                "search result", "select result", "result selection",
                "choosing search result", "finding search result")) {
            return SEARCH_RESULT_SELECTION;
        }
        if (containsAny(goal,
                "screen observation", "screen state", "observe screen",
                "refresh screen", "screen recovery")) {
            return SCREEN_STATE_RECOVERY;
        }
        if (containsAny(goal,
                "post action verification", "verify action", "verifying action",
                "verify result", "verification failure", "confirming result")) {
            return POST_ACTION_VERIFICATION;
        }
        if (containsAny(goal,
                "recovering from action failure", "action failure recovery",
                "recover from failure", "failed action", "retrying failed action")) {
            return ACTION_FAILURE_RECOVERY;
        }
        return "";
    }

    static boolean isCanonical(String value) {
        return UI_TARGET_RESOLUTION.equals(value)
                || ACTION_FAILURE_RECOVERY.equals(value)
                || SEARCH_RESULT_SELECTION.equals(value)
                || ROUTE_MODE_SELECTION.equals(value)
                || POST_ACTION_VERIFICATION.equals(value)
                || SCREEN_STATE_RECOVERY.equals(value);
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
