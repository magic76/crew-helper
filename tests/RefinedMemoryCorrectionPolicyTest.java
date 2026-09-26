package com.crewpocket.helper;

public final class RefinedMemoryCorrectionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        RefinedMemoryCorrectionPolicy.Result firstWrong =
                RefinedMemoryCorrectionPolicy.apply(
                        1,
                        0,
                        true);
        check(firstWrong.successCount == 0,
                "freshly learned wrong vote is retracted");
        check(firstWrong.failureCount == 1,
                "reliable correction adds negative evidence");
        check(RefinedMemoryPolicy.STATE_SUSPECT.equals(
                        firstWrong.state),
                "corrected memory is quarantined");

        RefinedMemoryCorrectionPolicy.Result oldUsed =
                RefinedMemoryCorrectionPolicy.apply(
                        5,
                        1,
                        false);
        check(oldUsed.successCount == 5,
                "older success votes are not erased");
        check(oldUsed.failureCount == 2,
                "used memory still receives negative evidence");
        check(RefinedMemoryPolicy.STATE_SUSPECT.equals(
                        oldUsed.state),
                "used memory is conservatively quarantined");

        RefinedMemoryCorrectionPolicy.Result zero =
                RefinedMemoryCorrectionPolicy.apply(
                        0,
                        0,
                        true);
        check(zero.successCount == 0,
                "success count never becomes negative");

        System.out.println(
                "RefinedMemoryCorrectionPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
