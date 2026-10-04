package com.crewpocket.helper;

/**
 * Promotion policy for deterministic task recipes.
 *
 * A recipe is not a fast path after a single lucky success. It becomes eligible
 * only after the same safe step sequence has been observed successfully more
 * than once. Runtime failures raise the bar before that path may be trusted
 * again.
 */
final class GoldenPathPolicy {
    static final int REQUIRED_CONSISTENT_CONFIRMATIONS = 2;

    private GoldenPathPolicy() {}

    static int nextConfirmationCount(
            boolean sameStepSequence,
            int previousConfirmations) {
        if (!sameStepSequence) return 1;
        return Math.max(0, previousConfirmations) + 1;
    }

    static boolean isEligibleForFastPath(
            int confirmations,
            int verifiedRuns,
            int failedRuns,
            int unverifiedRuns) {
        if (confirmations < REQUIRED_CONSISTENT_CONFIRMATIONS) {
            return false;
        }

        int positiveEvidence =
                Math.max(0, confirmations)
                        + Math.max(0, verifiedRuns);

        // One Runtime failure is enough to remove an immature path from the
        // fast lane. It needs three more positive samples before promotion.
        int requiredPositiveEvidence =
                REQUIRED_CONSISTENT_CONFIRMATIONS
                        + Math.max(0, failedRuns) * 3;
        if (positiveEvidence < requiredPositiveEvidence) {
            return false;
        }

        // Repeated "executed but terminal state unverified" outcomes mean the
        // path is useful as guidance, but not reliable enough to run blindly.
        int unresolved = Math.max(0, unverifiedRuns);
        return unresolved <= 1 || positiveEvidence >= unresolved * 2;
    }

    static String state(
            int confirmations,
            int verifiedRuns,
            int failedRuns,
            int unverifiedRuns) {
        return isEligibleForFastPath(
                        confirmations,
                        verifiedRuns,
                        failedRuns,
                        unverifiedRuns)
                ? "GOLDEN"
                : "CANDIDATE";
    }
}
