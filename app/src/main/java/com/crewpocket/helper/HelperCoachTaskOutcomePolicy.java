package com.crewpocket.helper;

import java.util.Locale;

/** Goal-specific, conservative evidence policy for terminal Pocket task outcomes. */
final class HelperCoachTaskOutcomePolicy {
    static final class Outcome {
        final boolean verified;
        final String verificationMethod;
        final String evidenceRef;

        Outcome(boolean verified, String verificationMethod, String evidenceRef) {
            this.verified = verified;
            this.verificationMethod = verificationMethod;
            this.evidenceRef = evidenceRef;
        }
    }

    private HelperCoachTaskOutcomePolicy() {}

    static Outcome evaluate(
            String goalIntent,
            String taskState,
            String completionEvidence,
            boolean taskCompleted) {
        String goal = GoalIntentKey.closedValue(goalIntent);
        String state = normalize(taskState);
        String evidence = normalize(completionEvidence);
        if (!taskCompleted || !"DONE".equals(state)) return unverified();

        if ("MEDIA:PLAY".equals(goal)
                && "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(evidence)) {
            return verified("MEDIA_PLAYBACK_BECAME_ACTIVE");
        }
        if ("MEDIA:PLAY".equals(goal) && "MEDIA_UI_PLAYING".equals(evidence)) {
            // Compatibility with older Runtime evidence; normalize the explicit
            // target-player UI state to the current Pocket contract token.
            return verified("MEDIA_PLAYBACK_BECAME_ACTIVE");
        }
        if ("NAVIGATION:START".equals(goal)
                && "MAPS_NAVIGATION_ACTIVE_VERIFIED".equals(evidence)) {
            return verified("MAPS_NAVIGATION_ACTIVE_VERIFIED");
        }
        if ("APP:OPEN".equals(goal)
                && "APP_OPEN_FOREGROUND_VERIFIED".equals(evidence)) {
            return verified("APP_PACKAGE_TRANSITION_OBSERVED");
        }
        return unverified();
    }

    private static Outcome verified(String method) {
        return new Outcome(true, method, method);
    }

    private static Outcome unverified() {
        return new Outcome(false, "RUNTIME_UNVERIFIED", "TASK_NOT_VERIFIED");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
