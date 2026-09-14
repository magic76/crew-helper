package com.crewpocket.helper;

public final class ActionRecoveryPolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    private static ActionVerificationResult result(ActionVerificationResult.Status status) {
        return new ActionVerificationResult(status, status.name(), false, false, false, false);
    }

    public static void main(String[] args) {
        check(ActionRecoveryPolicy.decide(
                        "tap_screen", result(ActionVerificationResult.Status.PENDING), 0, false)
                        == ActionRecoveryPolicy.Decision.OBSERVE,
                "pending action requires observation");
        check(ActionRecoveryPolicy.decide(
                        "tap_screen", result(ActionVerificationResult.Status.FAILED), 0, true)
                        == ActionRecoveryPolicy.Decision.RETRY_ONCE,
                "tap gets one recovery retry after explicit observation");
        check(ActionRecoveryPolicy.decide(
                        "tap_screen", result(ActionVerificationResult.Status.FAILED), 1, true)
                        == ActionRecoveryPolicy.Decision.STOP,
                "tap recovery is bounded to one retry");
        check(ActionRecoveryPolicy.decide(
                        "tap_screen", result(ActionVerificationResult.Status.FAILED), 0, false)
                        == ActionRecoveryPolicy.Decision.STOP,
                "retry requires explicit post-failure observation");
        check(ActionRecoveryPolicy.decide(
                        "send_text", result(ActionVerificationResult.Status.FAILED), 0, true)
                        == ActionRecoveryPolicy.Decision.STOP,
                "send is never retried");
        check(ActionRecoveryPolicy.decide(
                        "type_text", result(ActionVerificationResult.Status.FAILED), 0, true)
                        == ActionRecoveryPolicy.Decision.STOP,
                "type is never retried");
        check(ActionRecoveryPolicy.decide(
                        "commit_search", result(ActionVerificationResult.Status.FAILED), 0, true)
                        == ActionRecoveryPolicy.Decision.STOP,
                "search commit is never retried");
        check(ActionRecoveryPolicy.decide(
                        "launch_app", result(ActionVerificationResult.Status.FAILED), 0, true)
                        == ActionRecoveryPolicy.Decision.RETRY_ONCE,
                "launch may retry once");
        check(ActionRecoveryPolicy.decide(
                        "press_key", result(ActionVerificationResult.Status.FAILED), 0, true)
                        == ActionRecoveryPolicy.Decision.RETRY_ONCE,
                "navigation key may retry once");
        check(ActionRecoveryPolicy.decide(
                        "tap_element", result(ActionVerificationResult.Status.VERIFIED), 0, true)
                        == ActionRecoveryPolicy.Decision.NONE,
                "verified action needs no recovery");

        System.out.println("PASS ActionRecoveryPolicyTest: " + assertions + " checks");
    }
}
