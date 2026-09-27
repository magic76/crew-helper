package com.crewpocket.helper;

public final class AutonomyEscalationPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(AutonomyEscalationPolicy.evaluate(
                        0,
                        "RETRY_OBSERVE",
                        "",
                        "INSPECT_UI",
                        "")
                        == AutonomyEscalationPolicy.Decision.RECOVER_ONCE,
                "first medium-confidence locator uncertainty gets one recovery");

        check(AutonomyEscalationPolicy.evaluate(
                        1,
                        "RETRY_OBSERVE",
                        "",
                        "INSPECT_UI",
                        "")
                        == AutonomyEscalationPolicy.Decision.ASK_USER,
                "second locator uncertainty asks the user");

        check(AutonomyEscalationPolicy.evaluate(
                        0,
                        "",
                        ObservationLoopPolicy.BLOCK_CODE,
                        "TRY_ALTERNATIVE",
                        "UNCHANGED_SCREEN_OBSERVED_TWICE")
                        == AutonomyEscalationPolicy.Decision.ASK_USER,
                "no-progress loop asks immediately");

        check(AutonomyEscalationPolicy.evaluate(
                        0,
                        "AUTO",
                        "",
                        "CONTINUE_GOAL",
                        "RUNTIME_V2_VERIFIED")
                        == AutonomyEscalationPolicy.Decision.NONE,
                "high-confidence verified action stays autonomous");

        check(AutonomyEscalationPolicy.evaluate(
                        0,
                        "",
                        "CANDIDATE_ARBITRATION_CANDIDATE_NOT_FOUND",
                        "INSPECT_UI",
                        "")
                        == AutonomyEscalationPolicy.Decision.RECOVER_ONCE,
                "stale arbitration candidate gets one fresh recovery");

        check(AutonomyEscalationPolicy.evaluate(
                        1,
                        "",
                        "CANDIDATE_ARBITRATION_CANDIDATE_NOT_FOUND",
                        "INSPECT_UI",
                        "")
                        == AutonomyEscalationPolicy.Decision.ASK_USER,
                "repeated stale arbitration asks the user");

        System.out.println(
                "PASS AutonomyEscalationPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
