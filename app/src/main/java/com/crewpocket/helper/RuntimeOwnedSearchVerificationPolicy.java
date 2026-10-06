package com.crewpocket.helper;

/**
 * Pure policy for search-result verification that must not depend on another
 * Gemini tool turn. A submitted search with pending result evidence is an
 * immediate Runtime observation problem, not a background wait.
 */
final class RuntimeOwnedSearchVerificationPolicy {
    private RuntimeOwnedSearchVerificationPolicy() {}

    static boolean shouldVerify(
            String toolName,
            String taskState,
            String nextRequirement,
            String verificationStatus,
            String searchTransaction) {
        boolean searchTool =
                "search_current_app".equals(toolName)
                        || "commit_search".equals(toolName);
        if (!searchTool) return false;
        if (!"IN_PROGRESS".equals(clean(taskState))) return false;

        boolean inspectRequired =
                clean(nextRequirement).contains("INSPECT_UI");
        boolean pending =
                "PENDING".equals(clean(verificationStatus))
                        || "PENDING_RESULTS".equals(
                                clean(searchTransaction));
        return inspectRequired && pending;
    }

    static boolean shouldOpenElementFallback(
            String packageName,
            String goalIntent,
            boolean resultsObserved) {
        return !resultsObserved
                && MediaPlaybackCompletionPolicy
                        .isDefaultTrustedPackage(packageName)
                && MediaGoalUiPolicy.isMediaPlayGoal(goalIntent);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
