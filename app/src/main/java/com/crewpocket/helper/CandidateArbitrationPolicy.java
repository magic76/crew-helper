package com.crewpocket.helper;

import java.util.Locale;

/**
 * Pure Phase-0 admission gate for deep candidate arbitration.
 *
 * No confidence constants live here. FALLBACK qualification deliberately calls
 * LocatorConfidencePolicy.hasAmbiguousCompetition() so locator and experiment
 * cannot drift into two ambiguity systems.
 */
final class CandidateArbitrationPolicy {
    enum TriggerType {
        NONE,
        AMBIGUOUS,
        FALLBACK
    }

    private CandidateArbitrationPolicy() {}

    static TriggerType qualify(
            String locatorDecision,
            int candidateCount,
            double bestConfidence,
            double runnerUpConfidence,
            boolean bestExactViewId,
            boolean runnerUpExactViewId) {
        String decision = locatorDecision == null
                ? ""
                : locatorDecision.trim().toUpperCase(Locale.ROOT);

        if ("AMBIGUOUS".equals(decision)) {
            return TriggerType.AMBIGUOUS;
        }
        if (!"FALLBACK".equals(decision) || candidateCount < 2) {
            return TriggerType.NONE;
        }

        return LocatorConfidencePolicy.hasAmbiguousCompetition(
                        bestConfidence,
                        runnerUpConfidence,
                        bestExactViewId,
                        runnerUpExactViewId)
                ? TriggerType.FALLBACK
                : TriggerType.NONE;
    }

    static boolean phase0MayOverrideBaseline() {
        return false;
    }
}
