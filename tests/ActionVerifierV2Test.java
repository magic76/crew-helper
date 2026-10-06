package com.crewpocket.helper;

public final class ActionVerifierV2Test {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    private static ActionObservation obs(String pkg, String fp, String stable, String focus) {
        return ActionObservation.of(pkg, fp, stable, focus, "button", 20);
    }

    private static ActionObservation searchObs(
            String pkg,
            String fp,
            String stable,
            String focus,
            String searchSurface) {
        return ActionObservation.of(
                pkg, fp, stable, focus, "button", 20, searchSurface);
    }

    public static void main(String[] args) {
        ActionObservation a = obs("app", "fp1", "s1", "field");
        ActionObservation changed = obs("app", "fp2", "s2", "field");
        ActionObservation focusChanged = obs("app", "fp1", "s1", "field2");

        ActionVerificationResult tap = ActionVerifierV2.verify("tap_screen",
                ActionExpectation.forRuntimeAction("tap_screen"),
                ExecutionEvidence.accepted(false), a, changed);
        check(tap.status == ActionVerificationResult.Status.VERIFIED, "tap verified");
        check("TAP_EFFECT_OBSERVED".equals(tap.code), "tap code");

        ActionVerificationResult tapPending = ActionVerifierV2.verify("tap_screen",
                ActionExpectation.forRuntimeAction("tap_screen"),
                ExecutionEvidence.accepted(false), a, a);
        check(tapPending.status == ActionVerificationResult.Status.PENDING, "tap pending");

        ActionVerificationResult typedRuntime = ActionVerifierV2.verify("type_text",
                ActionExpectation.forRuntimeAction("type_text"),
                ExecutionEvidence.accepted(true), a, a);
        check(typedRuntime.status == ActionVerificationResult.Status.VERIFIED,
                "runtime verified type");

        ActionVerificationResult typedStructural = ActionVerifierV2.verify("type_text",
                ActionExpectation.forRuntimeAction("type_text"),
                ExecutionEvidence.accepted(false), a, focusChanged);
        check(typedStructural.status == ActionVerificationResult.Status.VERIFIED,
                "focus transition can verify type");

        ActionObservation launchAfter = obs("youtube", "fp9", "s9", "");
        ActionVerificationResult launch = ActionVerifierV2.verify("launch_app",
                ActionExpectation.forRuntimeAction("launch_app"),
                ExecutionEvidence.accepted(false), a, launchAfter);
        check(launch.status == ActionVerificationResult.Status.VERIFIED,
                "package change verifies launch");

        ActionVerificationResult searchStructuralOnly = ActionVerifierV2.verify(
                "search_current_app",
                ActionExpectation.forRuntimeAction("search_current_app"),
                ExecutionEvidence.accepted(false), a, changed);
        check(searchStructuralOnly.status == ActionVerificationResult.Status.PENDING,
                "generic screen change cannot verify search");
        check("SEARCH_AWAITING_RESULTS_EVIDENCE".equals(searchStructuralOnly.code),
                "search pending code");

        ActionVerificationResult searchRuntime = ActionVerifierV2.verify(
                "search_current_app",
                ActionExpectation.forRuntimeAction("search_current_app"),
                ExecutionEvidence.accepted(true), a, changed);
        check(searchRuntime.status == ActionVerificationResult.Status.VERIFIED,
                "result-surface evidence verifies search");

        ActionVerificationResult searchFocusOnly = ActionVerifierV2.verify(
                "search_current_app",
                ActionExpectation.forRuntimeAction("search_current_app"),
                ExecutionEvidence.accepted(false), a, focusChanged);
        check(searchFocusOnly.status == ActionVerificationResult.Status.PENDING,
                "search focus-only remains pending");

        ActionObservation searchBefore =
                searchObs("app", "fp-search-a", "s-search", "field", "surface-a");
        ActionObservation queryOnlyChanged =
                searchObs("app", "fp-search-b", "s-search", "field", "surface-a");
        ActionObservation lateResults =
                searchObs("app", "fp-search-c", "s-results", "", "surface-b");

        ActionVerificationResult queryOnlySearch = ActionVerifierV2.verify(
                "search_current_app",
                ActionExpectation.forRuntimeAction("search_current_app"),
                ExecutionEvidence.accepted(false),
                searchBefore,
                queryOnlyChanged);
        check(queryOnlySearch.status == ActionVerificationResult.Status.PENDING,
                "query/editor change alone cannot verify search");

        ActionVerificationResult lateSearch = ActionVerifierV2.verify(
                "search_current_app",
                ActionExpectation.forRuntimeAction("search_current_app"),
                ExecutionEvidence.accepted(false),
                searchBefore,
                lateResults);
        check(lateSearch.status == ActionVerificationResult.Status.VERIFIED,
                "late query-excluding result surface verifies search");
        check("SEARCH_RESULTS_OBSERVED".equals(lateSearch.code),
                "late search uses result evidence code");

        ActionVerificationResult commitStructuralOnly = ActionVerifierV2.verify(
                "commit_search",
                ActionExpectation.forRuntimeAction("commit_search"),
                ExecutionEvidence.accepted(false), a, changed);
        check(commitStructuralOnly.status == ActionVerificationResult.Status.PENDING,
                "generic transition cannot verify search commit");
        check("SEARCH_COMMIT_AWAITING_RESULTS".equals(commitStructuralOnly.code),
                "commit pending code");

        ActionVerificationResult commitRuntime = ActionVerifierV2.verify(
                "commit_search",
                ActionExpectation.forRuntimeAction("commit_search"),
                ExecutionEvidence.accepted(true), a, changed);
        check(commitRuntime.status == ActionVerificationResult.Status.VERIFIED,
                "search commit result-surface proof verified");

        ActionVerificationResult lateCommit = ActionVerifierV2.verify(
                "commit_search",
                ActionExpectation.forRuntimeAction("commit_search"),
                ExecutionEvidence.accepted(false),
                searchBefore,
                lateResults);
        check(lateCommit.status == ActionVerificationResult.Status.VERIFIED,
                "late query-excluding result surface verifies search commit");

        ActionVerificationResult scroll = ActionVerifierV2.verify("swipe_screen",
                ActionExpectation.forRuntimeAction("swipe_screen"),
                ExecutionEvidence.accepted(false), a, changed);
        check(scroll.status == ActionVerificationResult.Status.VERIFIED,
                "scroll progress verified");

        ActionVerificationResult nav = ActionVerifierV2.verify("press_key",
                ActionExpectation.forRuntimeAction("press_key"),
                ExecutionEvidence.accepted(false), a, changed);
        check(nav.status == ActionVerificationResult.Status.VERIFIED,
                "navigation verified");

        ActionVerificationResult photo = ActionVerifierV2.verify("take_photo",
                ActionExpectation.forRuntimeAction("take_photo"),
                ExecutionEvidence.accepted(true), a, a);
        check(photo.status == ActionVerificationResult.Status.VERIFIED,
                "domain-verified photo capture does not require screen change");

        ActionVerificationResult blocked = ActionVerifierV2.verify("tap_screen",
                ActionExpectation.forRuntimeAction("tap_screen"),
                ExecutionEvidence.blocked("POLICY_BLOCK"), a, changed);
        check(blocked.status == ActionVerificationResult.Status.BLOCKED,
                "policy block cannot reconcile");

        ActionVerificationResult delayed = ActionVerifierV2.verify("tap_screen",
                ActionExpectation.forRuntimeAction("tap_screen"),
                new ExecutionEvidence(false, false, false, false, true,
                        "GESTURE_UNCERTAIN"),
                a, a);
        check(delayed.status == ActionVerificationResult.Status.PENDING,
                "delayed UI pending");

        System.out.println("PASS ActionVerifierV2Test: " + assertions + " checks");
    }
}
