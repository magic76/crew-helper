package com.crewpocket.helper;

public final class ExperienceTriggerPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!ExperienceTriggerPolicy.isTrackableFriction(2),
                "low friction should stay quiet");
        check(ExperienceTriggerPolicy.isTrackableFriction(3),
                "medium friction should be tracked");
        check(!ExperienceTriggerPolicy.isImmediateFriction(4),
                "medium friction should not call model immediately");
        check(ExperienceTriggerPolicy.isImmediateFriction(5),
                "strong friction should be reviewed immediately");
        check(!ExperienceTriggerPolicy.shouldReviewRepeatedFriction(3, 1),
                "first medium friction occurrence should stay quiet");
        check(ExperienceTriggerPolicy.shouldReviewRepeatedFriction(3, 2),
                "second matching medium friction should trigger review");
        check(ExperienceTriggerPolicy.shouldReviewRepeatedFriction(4, 3),
                "later repeated friction remains reviewable");
        check(!ExperienceTriggerPolicy.shouldReviewRepeatedFriction(2, 10),
                "plain low friction never becomes a trigger by repetition alone");

        System.out.println("ExperienceTriggerPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
