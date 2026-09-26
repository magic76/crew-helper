package com.crewpocket.helper;

public final class RefinedMemoryDashboardPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(RefinedMemoryDashboardPolicy.shouldCountAsBaseline(true, 0),
                "known no-memory task is a valid baseline");
        check(!RefinedMemoryDashboardPolicy.shouldCountAsBaseline(false, 0),
                "trace mismatch cannot silently become baseline");
        check(!RefinedMemoryDashboardPolicy.shouldCountAsBaseline(true, 1),
                "memory-used task is not baseline");

        check(RefinedMemoryDashboardPolicy.isVerifiedWin(true, false),
                "verified uncorrected task is a win");
        check(!RefinedMemoryDashboardPolicy.isVerifiedWin(true, true),
                "later correction retracts verified win");
        check(!RefinedMemoryDashboardPolicy.isVerifiedWin(false, false),
                "unverified task is not a win");

        check(!RefinedMemoryDashboardPolicy.hasSufficientComparisonSample(4),
                "fewer than five baseline samples is insufficient");
        check(RefinedMemoryDashboardPolicy.hasSufficientComparisonSample(5),
                "five samples unlock observational comparison");

        System.out.println(
                "RefinedMemoryDashboardPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
