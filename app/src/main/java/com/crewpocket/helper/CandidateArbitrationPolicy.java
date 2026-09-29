package com.crewpocket.helper;

import java.util.Locale;

/**
 * Shared Candidate Arbitration admission and deterministic A/B assignment.
 *
 * Locator ambiguity remains owned by LocatorConfidencePolicy. This class may
 * choose whether a qualifying event enters control/treatment, but it must not
 * grow a second confidence or margin system.
 */
final class CandidateArbitrationPolicy {
    enum TriggerType {
        NONE,
        AMBIGUOUS,
        FALLBACK
    }

    enum Bucket {
        CONTROL,
        TREATMENT
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

        if (candidateCount < 2
                || bestConfidence
                        < LocatorConfidencePolicy.RETRY_MIN_CONFIDENCE) {
            return TriggerType.NONE;
        }

        boolean competing =
                LocatorConfidencePolicy.hasAmbiguousCompetition(
                        bestConfidence,
                        runnerUpConfidence,
                        bestExactViewId,
                        runnerUpExactViewId);
        if (!competing) return TriggerType.NONE;

        if ("AMBIGUOUS".equals(decision)) {
            return TriggerType.AMBIGUOUS;
        }
        if ("FALLBACK".equals(decision)) {
            return TriggerType.FALLBACK;
        }
        return TriggerType.NONE;
    }

    /**
     * The advisor is useful only when the top two candidates expose some
     * semantic difference for it to reason about. Bounds are intentionally not
     * considered: geometry remains deterministic Runtime evidence, not a reason
     * to spend a model call.
     */
    static boolean hasUsefulSemanticSeparation(
            String bestLabel,
            String bestRole,
            String bestViewId,
            String bestSemanticHint,
            String runnerLabel,
            String runnerRole,
            String runnerViewId,
            String runnerSemanticHint) {
        String bestAnchor = semanticAnchor(
                bestLabel, bestViewId, bestSemanticHint);
        String runnerAnchor = semanticAnchor(
                runnerLabel, runnerViewId, runnerSemanticHint);
        if (bestAnchor.isEmpty() && runnerAnchor.isEmpty()) {
            return false;
        }
        String best = bestAnchor + "|" + clean(bestRole).toLowerCase(Locale.ROOT);
        String runner = runnerAnchor + "|" + clean(runnerRole).toLowerCase(Locale.ROOT);
        return !best.equals(runner);
    }

    /**
     * Stable task-level 50/50 assignment. Repeated qualifying events inside one
     * task stay in the same bucket so control/treatment do not contaminate each
     * other during a single user goal.
     */
    static Bucket deterministicBucket(
            String taskId,
            long generation,
            String packageName,
            String goalIntent) {
        String id = clean(taskId);
        String key = !id.isEmpty()
                ? id
                : generation
                        + "|"
                        + clean(packageName)
                        + "|"
                        + clean(goalIntent);

        // 64-bit FNV-1a: deterministic across process/device/runtime versions.
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return (hash & 1L) == 0L
                ? Bucket.CONTROL
                : Bucket.TREATMENT;
    }

    // Regression guard retained for the merged Phase-0 invariant.
    static boolean phase0MayOverrideBaseline() {
        return false;
    }

    private static String semanticAnchor(
            String label,
            String viewId,
            String semanticHint) {
        String safeLabel = clean(label);
        String safeViewId = clean(viewId);
        String safeHint = clean(semanticHint);
        if (safeLabel.isEmpty()
                && safeViewId.isEmpty()
                && safeHint.isEmpty()) {
            return "";
        }
        return (safeLabel
                + "|"
                + safeViewId
                + "|"
                + safeHint)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
