package com.crewpocket.helper;

public final class ExperienceTriggerPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(ExperienceTriggerPolicy.isImmediate(ReflectionRuleEvidence.KIND_RECOVERY),
                "proven recovery should be reviewed immediately");
        check(!ExperienceTriggerPolicy.isImmediate(ReflectionRuleEvidence.KIND_ROUTINE),
                "routine success should not be reviewed immediately");
        check(!ExperienceTriggerPolicy.shouldReviewRoutineAtCount(1),
                "first routine occurrence should stay quiet");
        check(!ExperienceTriggerPolicy.shouldReviewRoutineAtCount(2),
                "second routine occurrence should stay quiet");
        check(ExperienceTriggerPolicy.shouldReviewRoutineAtCount(3),
                "third matching routine occurrence should trigger review");
        check(!ExperienceTriggerPolicy.shouldReviewRoutineAtCount(4),
                "routine review should not fire on every later occurrence");
        check(ExperienceTriggerPolicy.shouldReviewRoutineAtCount(6),
                "sixth occurrence should provide a later confirmation opportunity");

        System.out.println("ExperienceTriggerPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
