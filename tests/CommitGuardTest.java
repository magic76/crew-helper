package com.crewpocket.helper;

public final class CommitGuardTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                CommitGuard.evaluate(true, false, false, false, false).allowed(),
                "authorized current-chat send may commit");
        check(
                CommitGuard.evaluate(false, false, false, false, false).decision
                        == CommitGuard.Decision.BLOCK_MISSING_INTENT,
                "missing send intent blocks commit");
        check(
                CommitGuard.evaluate(true, false, true, false, false).decision
                        == CommitGuard.Decision.REQUIRE_TARGET_VERIFICATION,
                "named recipient requires target verification");
        check(
                CommitGuard.evaluate(true, false, true, true, false).allowed(),
                "verified named recipient may commit");
        check(
                CommitGuard.evaluate(true, true, false, false, false).decision
                        == CommitGuard.Decision.SUPPRESS_DUPLICATE,
                "dispatched commit is never repeated");
        check(
                CommitGuard.evaluate(true, false, false, false, true).decision
                        == CommitGuard.Decision.HANDOFF_SENSITIVE,
                "sensitive commit is handed off");

        System.out.println("PASS CommitGuardTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
