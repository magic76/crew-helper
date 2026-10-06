package com.crewpocket.helper;

public final class ObservationLoopPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!ObservationLoopPolicy.hasSemanticProgress(0, 0, 1),
                "pending verification alone is not semantic progress");
        check(ObservationLoopPolicy.hasSemanticProgress(1, 0, 0),
                "committed reverification is semantic progress");
        check(ObservationLoopPolicy.hasSemanticProgress(0, 1, 0),
                "failed reverification is semantic progress");

        boolean pendingOnlyProgress =
                ObservationLoopPolicy.hasSemanticProgress(0, 0, 1);
        ObservationLoopPolicy.Decision pendingFirst =
                ObservationLoopPolicy.evaluate(
                        "", 0, "pending-screen", pendingOnlyProgress);
        ObservationLoopPolicy.Decision pendingSecond =
                ObservationLoopPolicy.evaluate(
                        pendingFirst.fingerprint,
                        pendingFirst.nextSameScreenCount,
                        "pending-screen",
                        pendingOnlyProgress);
        check(!pendingFirst.blocked && pendingSecond.blocked,
                "pending-only same-screen flow is bounded after one visual recovery");

        ObservationLoopPolicy.Decision first =
                ObservationLoopPolicy.evaluate(
                        "", 0, "screen-a", false);
        check(!first.blocked
                        && first.nextSameScreenCount == 1,
                "first observation is allowed");

        ObservationLoopPolicy.Decision second =
                ObservationLoopPolicy.evaluate(
                        first.fingerprint,
                        first.nextSameScreenCount,
                        "screen-a",
                        false);
        check(second.blocked
                        && second.nextSameScreenCount == 1,
                "second unchanged observation is blocked after one recovery");

        ObservationLoopPolicy.Decision stillBlocked =
                ObservationLoopPolicy.evaluate(
                        second.fingerprint,
                        second.nextSameScreenCount,
                        "screen-a",
                        false);
        check(stillBlocked.blocked,
                "unchanged screen stays latched");

        ObservationLoopPolicy.Decision changed =
                ObservationLoopPolicy.evaluate(
                        second.fingerprint,
                        second.nextSameScreenCount,
                        "screen-b",
                        false);
        check(!changed.blocked
                        && changed.nextSameScreenCount == 1,
                "changed fingerprint resets the guard");

        ObservationLoopPolicy.Decision progress =
                ObservationLoopPolicy.evaluate(
                        "screen-a",
                        1,
                        "screen-a",
                        true);
        check(!progress.blocked
                        && progress.nextSameScreenCount == 1,
                "Runtime revalidation progress resets the guard");

        ObservationLoopPolicy.Decision unavailable =
                ObservationLoopPolicy.evaluate(
                        "screen-a",
                        1,
                        "",
                        false);
        check(!unavailable.blocked
                        && unavailable.nextSameScreenCount == 0,
                "missing fingerprint never creates a false block");

        System.out.println(
                "PASS ObservationLoopPolicyTest: "
                        + checks + " checks");
    }

    private static void check(
            boolean value,
            String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
