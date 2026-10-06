package com.crewpocket.helper;

/**
 * Narrow policy for model-triggered element-reference recovery.
 *
 * Element labels are a human assist and must never become a generic autonomy
 * escape hatch. The only autonomous exception is an already trusted, low-risk
 * media-play flow where Runtime is otherwise stuck locating the next tap.
 */
final class ElementReferenceFallbackPolicy {
    private ElementReferenceFallbackPolicy() {}

    static boolean allowModelRecovery(
            String packageName,
            String goalIntent,
            String target,
            boolean activeTask) {
        return activeTask
                && ElementReferenceCommand.isModelMarker(target)
                && MediaPlaybackCompletionPolicy
                        .isDefaultTrustedPackage(packageName)
                && MediaGoalUiPolicy.isMediaPlayGoal(goalIntent);
    }
}
