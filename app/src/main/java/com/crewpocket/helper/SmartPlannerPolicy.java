package com.crewpocket.helper;

/**
 * 0130 safety/budget contract for the external Smart Planner loop.
 *
 * The planner may choose WHAT NEXT, but it never receives raw coordinate
 * authority and it never gains SEND/payment/credential capabilities. All
 * execution still goes through Crew Helper Runtime endpoints.
 */
final class SmartPlannerPolicy {
    static final int MAX_PLANNER_CALLS = 10;
    static final int MAX_MUTATIONS = 8;
    static final long MAX_TASK_MS = 180_000L;

    private SmartPlannerPolicy() {}

    static boolean isAllowedAction(String action) {
        String value = safe(action).toUpperCase();
        return "OPEN_APP".equals(value)
                || "TAP".equals(value)
                || "TYPE".equals(value)
                || "SEARCH".equals(value)
                || "COMMIT_SEARCH".equals(value)
                || "SCROLL".equals(value)
                || "BACK".equals(value)
                || "HOME".equals(value);
    }

    static boolean isMutation(String action) {
        return isAllowedAction(action);
    }

    static boolean isNeverAllowed(String action) {
        String value = safe(action).toUpperCase();
        return value.contains("SEND")
                || value.contains("PAY")
                || value.contains("PURCHASE")
                || value.contains("OTP")
                || value.contains("PASSWORD")
                || value.contains("CREDENTIAL")
                || value.contains("COORDINATE")
                || value.contains("RAW_TAP");
    }

    static boolean canContinue(int plannerCalls, int mutations, long elapsedMs) {
        return plannerCalls < MAX_PLANNER_CALLS
                && mutations < MAX_MUTATIONS
                && elapsedMs < MAX_TASK_MS;
    }

    static String normalizedDirection(String direction) {
        String value = safe(direction).toLowerCase();
        if ("forward".equals(value) || "backward".equals(value)
                || "left".equals(value) || "right".equals(value)) {
            return value;
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
