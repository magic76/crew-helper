package com.crewpocket.helper;

/**
 * Pure policy for user-trusted app autonomy.
 *
 * Trust increases execution confidence only for low-risk UI navigation.
 * It never overrides sensitive/destructive action policy.
 */
final class AppAutonomyPolicy {
    private AppAutonomyPolicy() {}

    static boolean maySelfResolve(boolean trustedApp, String targetMetadata) {
        return trustedApp && !ActionSafetyPolicy.blocks(targetMetadata);
    }

    static boolean mayRecoverWithoutObservation(
            boolean trustedApp,
            String runtimeName,
            String targetMetadata) {
        if (!maySelfResolve(trustedApp, targetMetadata)) return false;
        String name = runtimeName == null ? "" : runtimeName.trim();
        return "tap_screen".equals(name)
                || "tap_element".equals(name)
                || "swipe_screen".equals(name)
                || "search_current_app".equals(name)
                || "commit_search".equals(name)
                || "press_key".equals(name)
                || "launch_app".equals(name);
    }
}
