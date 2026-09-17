package com.crewpocket.helper;

/**
 * Pure trigger policy for Crew Experience learning.
 *
 * Proven recovery is immediately reviewable. Routine successful patterns are
 * intentionally quiet until Runtime has observed the same deterministic rule
 * enough times to make it worth a model call.
 */
final class ExperienceTriggerPolicy {
    static final int ROUTINE_REPEAT_THRESHOLD = 3;

    private ExperienceTriggerPolicy() {}

    static boolean isImmediate(String kind) {
        return ReflectionRuleEvidence.KIND_RECOVERY.equals(kind);
    }

    static boolean shouldReviewRoutineAtCount(int count) {
        return count >= ROUTINE_REPEAT_THRESHOLD
                && count % ROUTINE_REPEAT_THRESHOLD == 0;
    }
}
