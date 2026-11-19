package com.crewpocket.helper;

import java.util.List;
import java.util.Locale;

/** Pure lifecycle gates for staged Pocket-proposed skills. */
final class HelperCoachSkillLifecyclePolicy {
    static final int MIN_INDEPENDENT_VERIFICATIONS = 2;

    private HelperCoachSkillLifecyclePolicy() {}

    static boolean canActivate(
            boolean candidate,
            int independentVerifiedTasks,
            boolean regressionChecksPassed) {
        return candidate
                && independentVerifiedTasks >= MIN_INDEPENDENT_VERIFICATIONS
                && regressionChecksPassed;
    }

    static boolean canRollback(boolean hasPreviousVersion) {
        return hasPreviousVersion;
    }

    static int nextVersion(int currentVersion, int requestedVersion) {
        return Math.max(Math.max(1, currentVersion + 1), requestedVersion);
    }

    static boolean isTrustedCompletionEvidence(String evidence) {
        String value = normalize(evidence);
        return "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(value)
                || "RUNTIME_VERIFIED".equals(value)
                || "TAP_EFFECT_OBSERVED".equals(value)
                || "NAVIGATION_TRANSITION_OBSERVED".equals(value)
                || "APP_PACKAGE_TRANSITION_OBSERVED".equals(value);
    }

    static boolean matchesTrustedPostcondition(
            String candidatePackage,
            String outcomePackage,
            List<String> postconditions,
            String evidence) {
        String proof = normalize(evidence);
        if (!validPackage(candidatePackage) || !candidatePackage.equals(outcomePackage)
                || !isTrustedCompletionEvidence(proof) || postconditions == null) {
            return false;
        }
        for (String postcondition : postconditions) {
            if (proof.equals(normalize(postcondition))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean validPackage(String value) {
        return value != null
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }
}
