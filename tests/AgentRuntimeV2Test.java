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

    private static ActionObservation searchObs(
            String fp, String stable, String searchSurface) {
        return ActionObservation.of(
                "pkg", fp, stable, "", "", 10, searchSurface);
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

        AgentRuntimeV2.ReverificationSummary failedSummary =
                runtime.reverifyPendingDetailed(s3);
        check(failedSummary.failed == 1,
                "explicit unchanged observation reports failed pending tap");
        check("tap_screen".equals(failedSummary.failedRuntimeName),
                "failed reverification identifies tap runtime");
        check("NO_EFFECT_AFTER_EXPLICIT_OBSERVE".equals(
                        failedSummary.failedCode),
                "failed reverification exposes no-effect code");

        AgentRuntimeV2.PreflightResult recoveryRetry = runtime.preflight(
                7L, 7L, "p3", "tap_screen", "tap:next", s3);
        check(recoveryRetry.allowed(),
                "explicit unchanged observation admits one recovery retry");
        check("RECOVERY_RETRY_ALLOWED".equals(recoveryRetry.code),
                "recovery retry is explicit in preflight result");

        runtime.onActionStarted("p3", 7L, "goal", "task", "phone_action", "tap_screen",
                recoveryRetry.actionHash,
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE, s3);
        runtime.onActionExecuted("p3", ExecutionEvidence.accepted(false));
        ActionVerificationResult recoveryPending = runtime.verifyAndRecord("p3", null, s3);
        check(recoveryPending != null && recoveryPending.pending(),
                "recovery retry still requires verification");
        runtime.reverifyPending(s3);
        AgentRuntimeV2.PreflightResult secondRecovery = runtime.preflight(
                7L, 7L, "p4", "tap_screen", "tap:next", s3);
        check(secondRecovery.decision == AgentRuntimeV2.PreflightDecision.REQUIRE_OBSERVE,
                "second recovery retry is blocked");

        // SEND remains safety-owned and never receives an automatic recovery retry.
        AgentRuntimeV2.PreflightResult sendAllow = runtime.preflight(
                7L, 7L, "send1", "send_text", "send:current", s3);
        check(sendAllow.allowed(), "first send transaction allowed by generic runtime");
        runtime.onActionStarted("send1", 7L, "goal", "task", "send_text", "send_text",
                sendAllow.actionHash,
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE, s3);
        runtime.onActionExecuted("send1", ExecutionEvidence.accepted(false));
        ActionVerificationResult sendPending = runtime.verifyAndRecord("send1", null, s3);
        check(sendPending != null && sendPending.pending(), "unchanged send becomes pending");
        runtime.reverifyPending(s3);
        AgentRuntimeV2.PreflightResult sendRetry = runtime.preflight(
                7L, 7L, "send2", "send_text", "send:current", s3);
        check(sendRetry.decision == AgentRuntimeV2.PreflightDecision.REQUIRE_OBSERVE,
                "send never receives automatic recovery retry");

        // A separate pending action can still commit when a later observation changes.
        AgentRuntimeV2.PreflightResult pendingAllow2 = runtime.preflight(
                7L, 7L, "q1", "tap_screen", "tap:menu", s3);
        runtime.onActionStarted("q1", 7L, "goal", "task", "phone_action", "tap_screen",
                pendingAllow2.actionHash, ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE, s3);
        runtime.onActionExecuted("q1", ExecutionEvidence.accepted(false));
        runtime.verifyAndRecord("q1", null, s3);

        ActionObservation s4 = obs("fp4", "s4");
        runtime.onScreenObserved(s4);
        AgentRuntimeV2.ReverificationSummary committedSummary =
                runtime.reverifyPendingDetailed(s4);
        check(committedSummary.committed >= 1,
                "pending transaction commits after later observation");
        check(committedSummary.failed == 0,
                "successful later observation is not reported as failed");

        // Search is intentionally delayed: the initial query/editor transition
        // is not enough, but a later query-excluding result surface must commit
        // the same transaction without resubmitting Search/Enter.
        AgentRuntimeV2 searchRuntime = new AgentRuntimeV2();
        searchRuntime.onUserIntent(8L, "search-goal", "search-task", true);
        ActionObservation searchBefore =
                searchObs("search-fp-a", "search-stable", "surface-a");
        searchRuntime.onScreenObserved(searchBefore);
        AgentRuntimeV2.PreflightResult searchAllow =
                searchRuntime.preflight(
                        8L, 8L, "search1", "commit_search",
                        "commit:search", searchBefore);
        check(searchAllow.allowed(), "search commit preflight allowed");
        searchRuntime.onActionStarted(
                "search1", 8L, "search-goal", "search-task",
                "commit_search", "commit_search",
                searchAllow.actionHash,
                ActionTransaction.ExpectedEffect.SCREEN_CHANGE,
                searchBefore);
        searchRuntime.onActionExecuted(
                "search1",
                new ExecutionEvidence(
                        true, false, false, false, true, ""));
        ActionObservation queryOnly =
                searchObs("search-fp-b", "search-stable", "surface-a");
        ActionVerificationResult searchPending =
                searchRuntime.verifyAndRecord(
                        "search1", null, queryOnly);
        check(searchPending != null && searchPending.pending(),
                "query-only search transition stays pending");

        AgentRuntimeV2.ReverificationSummary searchStillPending =
                searchRuntime.reverifyPendingDetailed(queryOnly);
        check(searchStillPending.pending == 1
                        && searchStillPending.failed == 0,
                "delayed search remains pending during legal wait");

        ActionObservation searchResults =
                searchObs("search-fp-c", "results-stable", "surface-b");
        AgentRuntimeV2.ReverificationSummary searchCommitted =
                searchRuntime.reverifyPendingDetailed(searchResults);
        check(searchCommitted.committed == 1,
                "late search result surface commits pending transaction");
        check(searchCommitted.searchResultsCommitted,
                "late search commit is identified for user-action scope");

        // Repeat-safe scrolls are intentionally not deduped.
        AgentRuntimeV2.PreflightResult scroll1 = runtime.preflight(
                7L, 7L, "scroll1", "swipe_screen", "scroll:up", s4);
        AgentRuntimeV2.PreflightResult scroll2 = runtime.preflight(
                7L, 7L, "scroll2", "swipe_screen", "scroll:up", s4);
        check(scroll1.allowed() && scroll2.allowed(), "scroll remains repeatable");

        System.out.println("PASS AgentRuntimeV2Test: " + assertions + " checks");
    }
}
