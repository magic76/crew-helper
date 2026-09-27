package com.crewpocket.helper;

public final class ObservationLoopPolicyTest {
    private static int checks;

    public static void main(String[] args) {
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
        check(!second.blocked
                        && second.nextSameScreenCount == 2,
                "second unchanged observation is allowed");

        ObservationLoopPolicy.Decision third =
                ObservationLoopPolicy.evaluate(
                        second.fingerprint,
                        second.nextSameScreenCount,
                        "screen-a",
                        false);
        check(third.blocked
                        && third.nextSameScreenCount == 2,
                "third unchanged observation is blocked");

        ObservationLoopPolicy.Decision stillBlocked =
                ObservationLoopPolicy.evaluate(
                        third.fingerprint,
                        third.nextSameScreenCount,
                        "screen-a",
                        false);
        check(stillBlocked.blocked,
                "unchanged screen stays latched");

        ObservationLoopPolicy.Decision changed =
                ObservationLoopPolicy.evaluate(
                        third.fingerprint,
                        third.nextSameScreenCount,
                        "screen-b",
                        false);
        check(!changed.blocked
                        && changed.nextSameScreenCount == 1,
                "changed fingerprint resets the guard");

        ObservationLoopPolicy.Decision progress =
                ObservationLoopPolicy.evaluate(
                        "screen-a",
                        2,
                        "screen-a",
                        true);
        check(!progress.blocked
                        && progress.nextSameScreenCount == 1,
                "Runtime revalidation progress resets the guard");

        ObservationLoopPolicy.Decision unavailable =
                ObservationLoopPolicy.evaluate(
                        "screen-a",
                        2,
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
