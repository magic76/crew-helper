package com.crewpocket.helper;

public final class ActionVerifierV2Test {
    private static int assertions;
    private static void check(boolean v, String name) {
        assertions++;
        if (!v) throw new AssertionError(name);
    }

    private static ActionObservation obs(String pkg, String fp, String stable, String focus) {
        return ActionObservation.of(pkg, fp, stable, focus, "button", 20);
    }

    public static void main(String[] args) {
        ActionObservation a = obs("app", "fp1", "s1", "");
        ActionObservation b = obs("app", "fp2", "s2", "");

        ActionVerificationResult changed = ActionVerifierV2.verify("tap_screen",
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE,
                ExecutionEvidence.accepted(false), a, b);
        check(changed.status == ActionVerificationResult.Status.VERIFIED, "tap change verified");
        check(changed.committed(), "tap change committed");

        ActionVerificationResult unchanged = ActionVerifierV2.verify("tap_screen",
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE,
                ExecutionEvidence.accepted(false), a, a);
        check(unchanged.status == ActionVerificationResult.Status.PENDING, "accepted unchanged pending");
        check(!unchanged.committed(), "pending not committed");

        ActionVerificationResult falseNegative = ActionVerifierV2.verify("tap_screen",
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE,
                ExecutionEvidence.failed("GESTURE_FALSE"), a, b);
        check(falseNegative.status == ActionVerificationResult.Status.LIKELY,
                "false negative reconciled by UI evidence");

        ActionVerificationResult typed = ActionVerifierV2.verify("type_text",
                ActionTransaction.ExpectedEffect.TEXT_CHANGE,
                ExecutionEvidence.accepted(true), a, a);
        check(typed.status == ActionVerificationResult.Status.VERIFIED, "runtime verified type");

        ActionVerificationResult blocked = ActionVerifierV2.verify("tap_screen",
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE,
                ExecutionEvidence.blocked("POLICY_BLOCK"), a, b);
        check(blocked.status == ActionVerificationResult.Status.BLOCKED, "policy block cannot reconcile");

        ActionObservation launchAfter = obs("youtube", "fp9", "s9", "");
        ActionVerificationResult launch = ActionVerifierV2.verify("launch_app",
                ActionTransaction.ExpectedEffect.APP_CHANGE,
                ExecutionEvidence.accepted(false), a, launchAfter);
        check(launch.status == ActionVerificationResult.Status.VERIFIED, "package change verifies launch");

        ActionVerificationResult delayed = ActionVerifierV2.verify("tap_screen",
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE,
                new ExecutionEvidence(false, false, false, false, true, "GESTURE_UNCERTAIN"),
                a, a);
        check(delayed.status == ActionVerificationResult.Status.PENDING, "delayed UI pending");

        System.out.println("PASS ActionVerifierV2Test: " + assertions + " checks");
    }
}
