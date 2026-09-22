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
}
