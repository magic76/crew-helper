package com.crewpocket.helper;

/**
 * Pure telemetry semantics for Candidate Arbitration Phase 0.
 *
 * interactionVerified is technical action acceptance/progress.
 * semanticEffectVerified intentionally rejects generic screen-change evidence.
 */
final class CandidateArbitrationOutcomePolicy {
    private CandidateArbitrationOutcomePolicy() {}

    static boolean interactionVerified(
            boolean success,
            String stepResult,
            String verificationStatus) {
        String step = clean(stepResult);
        String verification = clean(verificationStatus);
        if ("STEP_FAILED".equals(step)
                || "FAILED".equals(verification)
                || "BLOCKED".equals(verification)
                || "CANCELLED".equals(verification)) {
            return false;
        }
        return success
                || "STEP_OK".equals(step)
                || "STEP_PENDING".equals(step);
    }

    static boolean semanticEffectVerified(
            boolean explicitVerified,
            String verificationCode,
            String completionEvidence) {
        if (explicitVerified) return true;
        String code = clean(verificationCode);
        if ("RUNTIME_VERIFIED".equals(code)) return true;

        String evidence = clean(completionEvidence);
        return "MAPS_NAVIGATION_ACTIVE_VERIFIED".equals(evidence)
                || "MEDIA_PLAY_CONTROL_VERIFIED".equals(evidence)
                || "MEDIA_UI_PLAYING".equals(evidence)
                || "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(evidence);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
