package com.crewpocket.helper;

public final class ExperiencePolicyEpochTest {
    private static int checks;

    public static void main(String[] args) {
        check(ExperiencePolicyEpoch.isCurrent(
                        ExperiencePolicyEpoch.CURRENT_REVISION),
                "current revision is active");
        check(ExperiencePolicyEpoch.shouldStale(0),
                "legacy revision without epoch is stale");
        check(ExperiencePolicyEpoch.shouldStale(
                        ExperiencePolicyEpoch.CURRENT_REVISION - 1),
                "older policy revision is stale");
        check(!ExperiencePolicyEpoch.shouldStale(
                        ExperiencePolicyEpoch.CURRENT_REVISION),
                "current revision is not stale");

        System.out.println(
                "PASS ExperiencePolicyEpochTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
