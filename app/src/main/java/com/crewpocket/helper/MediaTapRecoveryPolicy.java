package com.crewpocket.helper;

import java.util.List;

/**
 * Pure deterministic recovery for reversible MEDIA:PLAY TAP schema mistakes.
 *
 * Runtime may repair only one high-confidence semantic target. Ambiguous goal
 * entities block fallback to Play rather than letting Runtime invent workflow.
 */
final class MediaTapRecoveryPolicy {
    static final double MIN_CONFIDENCE = 0.85;

    static final class Candidate {
        final String role;
        final String label;
        final String semanticHint;
        final boolean clickable;
        final boolean enabled;
        final boolean sensitive;
        final double confidence;

        Candidate(
                String role,
                String label,
                String semanticHint,
                boolean clickable,
                boolean enabled,
                boolean sensitive,
                double confidence) {
            this.role = clean(role);
            this.label = clean(label);
            this.semanticHint = clean(semanticHint);
            this.clickable = clickable;
            this.enabled = enabled;
            this.sensitive = sensitive;
            this.confidence = confidence;
        }
    }

    private MediaTapRecoveryPolicy() {}

    static String recoverTarget(
            String goalText,
            String completedTarget,
            List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";

        String entityTarget = "";
        int entityMatches = 0;
        for (Candidate candidate : candidates) {
            if (!isEligibleGoalEntity(
                    candidate, goalText, completedTarget)) {
                continue;
            }
            entityMatches++;
            if (entityMatches > 1) {
                // A visible but ambiguous goal entity means Runtime must not
                // jump ahead to a Play control.
                return "";
            }
            entityTarget = candidate.label;
        }
        if (entityMatches == 1) return entityTarget;

        String playTarget = "";
        int playMatches = 0;
        for (Candidate candidate : candidates) {
            if (!MediaGoalUiPolicy.isEligiblePlayCandidate(
                    candidate.role,
                    candidate.label,
                    candidate.semanticHint,
                    candidate.clickable,
                    candidate.enabled,
                    candidate.sensitive,
                    candidate.confidence)) {
                continue;
            }
            playMatches++;
            if (playMatches > 1) return "";
            playTarget = !candidate.label.isEmpty()
                    ? candidate.label
                    : candidate.semanticHint;
        }
        return playMatches == 1 ? playTarget : "";
    }

    private static boolean isEligibleGoalEntity(
            Candidate candidate,
            String goalText,
            String completedTarget) {
        return candidate != null
                && !candidate.sensitive
                && candidate.enabled
                && candidate.clickable
                && candidate.confidence >= MIN_CONFIDENCE
                && MediaGoalUiPolicy.isGoalEntityLabel(
                        candidate.label, goalText)
                && !MediaGoalUiPolicy.sameSemanticLabel(
                        candidate.label, completedTarget);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
