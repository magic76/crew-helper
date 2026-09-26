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

        check(RefinedMemoryDashboardPolicy.isAppliedPattern(
                        "SEARCH_QUERY>TAP_GOAL_ENTITY>TAP_PLAY_CONTROL",
                        "SEARCH_QUERY > TAP_GOAL_ENTITY > TAP_PLAY_CONTROL"),
                "applied pattern survives formatting differences");

        RefinedMemoryDashboardPolicy.UsageAccumulator usage =
                new RefinedMemoryDashboardPolicy.UsageAccumulator();
        usage.observe(true, true, true, true, true, false, true, 3, 4000L);
        usage.observe(true, true, false, false, true, false, false, 5, 8000L);
        usage.observe(true, false, false, false, true, false, false, 9, 9000L);
        usage.observe(true, true, true, true, true, true, true, 4, 5000L);
        check(usage.usedTasks == 2,
                "usage accumulator counts memory tasks");
        check(usage.verifiedTasks == 1,
                "corrected memory task retracts verified win");
        check(usage.appliedTasks == 2,
                "canonical applied patterns are counted");
        check(usage.baselineTasks == 1,
                "trace mismatch is excluded from baseline");
        check(usage.baselineVerified == 1,
                "known baseline verified task is counted");

        RefinedMemoryDashboardPolicy.OverallAccumulator overall =
                new RefinedMemoryDashboardPolicy.OverallAccumulator();
        overall.observe(true, true, true, false);
        overall.observe(true, false, true, false);
        overall.observe(false, false, true, false);
        overall.observe(true, true, true, true);
        check(overall.memoryTasks == 2 && overall.memoryVerified == 1,
                "overall memory summary retracts corrected win");
        check(overall.noMemoryTasks == 1
                        && overall.noMemoryVerified == 1,
                "overall summary excludes unknown trace from baseline");

        System.out.println(
                "RefinedMemoryDashboardPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
