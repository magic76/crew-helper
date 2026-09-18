package com.crewpocket.helper;

/**
 * Pure trigger policy for Crew Experience learning.
 *
 * Plain success never triggers Experience. Runtime first detects deterministic
 * friction. Strong friction is reviewable immediately; medium friction must
 * repeat for the same rule key before a model call is allowed.
 */
final class ExperienceTriggerPolicy {
    static final int IMMEDIATE_FRICTION_SCORE = 5;
    static final int REPEATED_FRICTION_MIN_SCORE = 3;
    static final int REPEATED_FRICTION_OCCURRENCES = 2;

    private ExperienceTriggerPolicy() {}

    static boolean isImmediateFriction(int score) {
        return score >= IMMEDIATE_FRICTION_SCORE;
    }

    static boolean isTrackableFriction(int score) {
        return score >= REPEATED_FRICTION_MIN_SCORE;
    }

    static boolean shouldReviewRepeatedFriction(int score, int occurrences) {
        return isTrackableFriction(score)
                && occurrences >= REPEATED_FRICTION_OCCURRENCES;
    }
}
