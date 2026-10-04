package com.crewpocket.helper;

public final class GoldenPathPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(GoldenPathPolicy.nextConfirmationCount(false, 9) == 1,
                "changed path resets confirmations");
        check(GoldenPathPolicy.nextConfirmationCount(true, 1) == 2,
                "same path accumulates confirmations");

        check(!GoldenPathPolicy.isEligibleForFastPath(1, 0, 0, 0),
                "one lucky success is not a golden path");
        check(GoldenPathPolicy.isEligibleForFastPath(2, 0, 0, 0),
                "two consistent successful captures promote a path");

        check(!GoldenPathPolicy.isEligibleForFastPath(2, 0, 1, 0),
                "first runtime failure demotes an immature path");
        check(GoldenPathPolicy.isEligibleForFastPath(5, 0, 1, 0),
                "enough later positive evidence can restore a failed path");

        check(!GoldenPathPolicy.isEligibleForFastPath(2, 0, 0, 3),
                "mostly unverified outcomes cannot stay golden");
        check(GoldenPathPolicy.isEligibleForFastPath(6, 0, 0, 3),
                "strong positive evidence can tolerate bounded unverified runs");

        System.out.println("GoldenPathPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
