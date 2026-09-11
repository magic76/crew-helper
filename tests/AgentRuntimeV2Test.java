package com.crewpocket.helper;

public final class AgentRuntimeV2Test {
    private static int assertions;
    private static void check(boolean v, String name) {
        assertions++;
        if (!v) throw new AssertionError(name);
    }

    private static ActionObservation obs(String fp, String stable) {
        return ActionObservation.of("pkg", fp, stable, "", "", 10);
    }

    public static void main(String[] args) {
        AgentRuntimeV2 runtime = new AgentRuntimeV2();
        runtime.onUserIntent(7L, "goal", "task", true);
        ActionObservation s1 = obs("fp1", "s1");
        runtime.onScreenObserved(s1);

        AgentRuntimeV2.PreflightResult stale = runtime.preflight(
                6L, 7L, "old", "tap_screen", "tap:search", s1);
        check(stale.decision == AgentRuntimeV2.PreflightDecision.REJECT_STALE, "stale rejected");

        AgentRuntimeV2.PreflightResult allow = runtime.preflight(
                7L, 7L, "t1", "tap_screen", "tap:search", s1);
        check(allow.allowed(), "first tap allowed");
        runtime.onActionStarted("t1", 7L, "goal", "task", "phone_action", "tap_screen",
                allow.actionHash, ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE, s1);
        runtime.onActionExecuted("t1", ExecutionEvidence.accepted(false));
        ActionObservation s2 = obs("fp2", "s2");
        ActionVerificationResult committed = runtime.verifyAndRecord("t1", null, s2);
        check(committed != null && committed.committed(), "action committed");

        // Current screen is now s2; duplicate must be compared against the post-action screen.
        runtime.onScreenObserved(s2);
        AgentRuntimeV2.PreflightResult duplicate = runtime.preflight(
                7L, 7L, "t2", "tap_screen", "tap:search", s2);
        check(duplicate.decision == AgentRuntimeV2.PreflightDecision.REJECT_DUPLICATE,
                "recent committed duplicate rejected");

        ActionObservation s3 = obs("fp3", "s3");
        runtime.onScreenObserved(s3);
        AgentRuntimeV2.PreflightResult changedScreen = runtime.preflight(
                7L, 7L, "t3", "tap_screen", "tap:search", s3);
        check(changedScreen.allowed(), "same action allowed after screen change");

        AgentRuntimeV2.PreflightResult pendingAllow = runtime.preflight(
                7L, 7L, "p1", "tap_screen", "tap:next", s3);
        runtime.onActionStarted("p1", 7L, "goal", "task", "phone_action", "tap_screen",
                pendingAllow.actionHash, ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE, s3);
        runtime.onActionExecuted("p1", ExecutionEvidence.accepted(false));
        ActionVerificationResult pending = runtime.verifyAndRecord("p1", null, s3);
        check(pending != null && pending.pending(), "unchanged becomes pending");

        AgentRuntimeV2.PreflightResult pendingRetry = runtime.preflight(
                7L, 7L, "p2", "tap_screen", "tap:next", s3);
        check(pendingRetry.decision == AgentRuntimeV2.PreflightDecision.REQUIRE_OBSERVE,
                "pending repeat requires observation");

        runtime.reverifyPending(s3);
        AgentRuntimeV2.PreflightResult failedRetry = runtime.preflight(
                7L, 7L, "p3", "tap_screen", "tap:next", s3);
        check(failedRetry.decision == AgentRuntimeV2.PreflightDecision.REQUIRE_OBSERVE,
                "explicit unchanged observation resolves pending to failure barrier");

        // A separate pending action can still commit when a later observation changes.
        AgentRuntimeV2.PreflightResult pendingAllow2 = runtime.preflight(
                7L, 7L, "q1", "tap_screen", "tap:menu", s3);
        runtime.onActionStarted("q1", 7L, "goal", "task", "phone_action", "tap_screen",
                pendingAllow2.actionHash, ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE, s3);
        runtime.onActionExecuted("q1", ExecutionEvidence.accepted(false));
        runtime.verifyAndRecord("q1", null, s3);

        ActionObservation s4 = obs("fp4", "s4");
        runtime.onScreenObserved(s4);
        int reverified = runtime.reverifyPending(s4);
        check(reverified >= 1, "pending transaction commits after later observation");

        // Repeat-safe scrolls are intentionally not deduped.
        AgentRuntimeV2.PreflightResult scroll1 = runtime.preflight(
                7L, 7L, "scroll1", "swipe_screen", "scroll:up", s4);
        AgentRuntimeV2.PreflightResult scroll2 = runtime.preflight(
                7L, 7L, "scroll2", "swipe_screen", "scroll:up", s4);
        check(scroll1.allowed() && scroll2.allowed(), "scroll remains repeatable");

        System.out.println("PASS AgentRuntimeV2Test: " + assertions + " checks");
    }
}
