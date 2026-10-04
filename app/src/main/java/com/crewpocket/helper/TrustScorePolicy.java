package com.crewpocket.helper;

/**
 * Small user-facing reliability score for recent finished tasks.
 *
 * Clean success is worth 100, recovered success is slightly discounted because
 * the user still experienced friction, and partial completion is worth 40.
 * Failures and unresolved non-user cancellations contribute zero.
 */
final class TrustScorePolicy {
    private TrustScorePolicy() {}

    static int score(
            int cleanSuccess,
            int recoveredSuccess,
            int partial,
            int evaluatedSamples) {
        int samples = Math.max(0, evaluatedSamples);
        if (samples == 0) return -1;

        long weighted =
                Math.max(0, cleanSuccess) * 100L
                        + Math.max(0, recoveredSuccess) * 85L
                        + Math.max(0, partial) * 40L;
        int result = (int) Math.round(weighted / (double) samples);
        return Math.max(0, Math.min(100, result));
    }

    static String band(int score, int evaluatedSamples) {
        if (evaluatedSamples < 5 || score < 0) return "CALIBRATING";
        if (score >= 90) return "HIGH";
        if (score >= 75) return "MEDIUM";
        return "LOW";
    }
}
