package com.crewpocket.helper;

/**
 * 0073 action-specific verifier.
 *
 * Android's callback says whether a command was accepted; this class decides
 * whether the expected semantic effect actually became observable.  It stores
 * no user-visible text and only reasons over privacy-safe structural evidence.
 */
final class ActionVerifierV2 {
    private ActionVerifierV2() {}

    static ActionVerificationResult verify(String runtimeName,
                                           ActionTransaction.ExpectedEffect expected,
                                           ExecutionEvidence execution,
                                           ActionObservation before,
                                           ActionObservation after) {
        if (execution == null) execution = ExecutionEvidence.failed("NO_EXECUTION_EVIDENCE");
        if (before == null) before = ActionObservation.unavailable();
        if (after == null) after = ActionObservation.unavailable();
        if (expected == null) expected = ActionTransaction.ExpectedEffect.UNKNOWN;

        final String name = safe(runtimeName);
        final boolean screenChanged = after.fingerprintChangedFrom(before);
        final boolean stableChanged = after.stableScreenChangedFrom(before);
        final boolean packageChanged = after.packageChangedFrom(before);
        final boolean focusChanged = after.focusChangedFrom(before);
        final boolean anyObservedChange =
                screenChanged || stableChanged || packageChanged || focusChanged;

        if (execution.cancelled) {
            return result(ActionVerificationResult.Status.CANCELLED,
                    nonEmpty(execution.errorCode, "ACTION_CANCELLED"),
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }
        if (execution.blocked) {
            return result(ActionVerificationResult.Status.BLOCKED,
                    nonEmpty(execution.errorCode, "ACTION_BLOCKED"),
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        // Domain runtimes can provide stronger proof than a screen fingerprint:
        // TYPE can prove the focused editor contains the write; SEARCH can prove
        // commit; launch can prove the requested package is foreground.
        if (execution.runtimeVerified) {
            return result(ActionVerificationResult.Status.VERIFIED,
                    "RUNTIME_VERIFIED",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        boolean expectationMet = false;
        String observedCode = "EXPECTED_EFFECT_OBSERVED";
        String pendingCode = after.available
                ? "ACCEPTED_NO_EFFECT_OBSERVED"
                : "ACCEPTED_AWAITING_OBSERVATION";
        String noEffectCode = "NO_OBSERVABLE_EFFECT";

        if ("launch_app".equals(name)) {
            expectationMet = packageChanged || stableChanged;
            observedCode = packageChanged
                    ? "APP_PACKAGE_TRANSITION_OBSERVED"
                    : "APP_SURFACE_TRANSITION_OBSERVED";
            pendingCode = "LAUNCH_AWAITING_TARGET_EVIDENCE";
            noEffectCode = "LAUNCH_TARGET_NOT_OBSERVED";
        } else if ("type_text".equals(name)) {
            // Text often changes the semantic fingerprint while focus remains
            // on the same editor. A focus transition is also legitimate when
            // Runtime first focuses the target before typing.
            expectationMet = screenChanged || focusChanged;
            observedCode = "TYPE_EFFECT_OBSERVED";
            pendingCode = "TYPE_AWAITING_TEXT_EVIDENCE";
            noEffectCode = "TYPE_EFFECT_NOT_OBSERVED";
        } else if ("search_current_app".equals(name)) {
            // Merely focusing a field is not a completed search. Require real
            // screen/surface progress unless the search runtime already proved
            // commit through runtimeVerified above.
            expectationMet = screenChanged || stableChanged || packageChanged;
            observedCode = "SEARCH_SURFACE_TRANSITION_OBSERVED";
            pendingCode = "SEARCH_AWAITING_RESULTS_EVIDENCE";
            noEffectCode = "SEARCH_EFFECT_NOT_OBSERVED";
        } else if ("commit_search".equals(name)) {
            expectationMet = screenChanged || stableChanged || packageChanged;
            observedCode = "SEARCH_COMMIT_TRANSITION_OBSERVED";
            pendingCode = "SEARCH_COMMIT_AWAITING_RESULTS";
            noEffectCode = "SEARCH_COMMIT_NO_TRANSITION";
        } else if ("swipe_screen".equals(name)) {
            // A package change is not evidence that the requested scroll worked.
            expectationMet = screenChanged || stableChanged;
            observedCode = "SCROLL_PROGRESS_OBSERVED";
            pendingCode = "SCROLL_AWAITING_PROGRESS";
            noEffectCode = "SCROLL_NO_PROGRESS";
        } else if ("tap_screen".equals(name) || "tap_element".equals(name)) {
            expectationMet = anyObservedChange;
            observedCode = "TAP_EFFECT_OBSERVED";
            pendingCode = "TAP_AWAITING_EFFECT";
            noEffectCode = "TAP_NO_EFFECT";
        } else if ("press_key".equals(name)) {
            expectationMet = screenChanged || stableChanged || packageChanged;
            observedCode = "NAVIGATION_TRANSITION_OBSERVED";
            pendingCode = "NAVIGATION_AWAITING_EFFECT";
            noEffectCode = "NAVIGATION_NO_EFFECT";
        } else {
            switch (expected) {
                case APP_CHANGE:
                    expectationMet = packageChanged || stableChanged;
                    break;
                case SCREEN_CHANGE:
                    expectationMet = screenChanged || stableChanged || packageChanged;
                    break;
                case SCROLL_CHANGE:
                    expectationMet = screenChanged || stableChanged;
                    break;
                case FOCUS_CHANGE:
                    expectationMet = focusChanged || screenChanged;
                    break;
                case TEXT_CHANGE:
                    expectationMet = screenChanged || focusChanged;
                    break;
                case ANY_OBSERVABLE_CHANGE:
                    expectationMet = anyObservedChange;
                    break;
                case DOMAIN_VERIFIED:
                    expectationMet = false;
                    pendingCode = "DOMAIN_VERIFICATION_REQUIRED";
                    noEffectCode = "DOMAIN_VERIFICATION_MISSING";
                    break;
                case NO_UI_CHANGE:
                    expectationMet = execution.executionAccepted;
                    observedCode = "EXECUTION_ACCEPTED_NO_UI_CHANGE_EXPECTED";
                    break;
                case UNKNOWN:
                default:
                    expectationMet = anyObservedChange;
                    break;
            }
        }

        if (expectationMet) {
            // Post-action UI evidence can reconcile an Android false negative,
            // but report LIKELY so callers know execution and observation disagreed.
            ActionVerificationResult.Status status = execution.executionAccepted
                    ? ActionVerificationResult.Status.VERIFIED
                    : ActionVerificationResult.Status.LIKELY;
            return result(status,
                    execution.executionAccepted
                            ? observedCode
                            : observedCode + "_FALSE_NEGATIVE_RECONCILED",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        if (expected == ActionTransaction.ExpectedEffect.NO_UI_CHANGE
                && execution.executionAccepted) {
            return result(ActionVerificationResult.Status.VERIFIED,
                    "EXECUTION_ACCEPTED_NO_UI_CHANGE_EXPECTED",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        // Accepted asynchronous actions are PENDING, never fake success. This
        // forces one fresh observation before a same-action retry.
        if (execution.executionAccepted) {
            return result(ActionVerificationResult.Status.PENDING,
                    pendingCode,
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        if (execution.allowDelayedUi && after.available) {
            return result(ActionVerificationResult.Status.PENDING,
                    "DELAYED_UI_AWAITING_OBSERVATION",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        return result(ActionVerificationResult.Status.FAILED,
                nonEmpty(execution.errorCode, after.available
                        ? noEffectCode
                        : "EXECUTION_FAILED_NO_OBSERVATION"),
                screenChanged, stableChanged, packageChanged, focusChanged);
    }

    private static ActionVerificationResult result(ActionVerificationResult.Status status,
                                                   String code,
                                                   boolean screenChanged,
                                                   boolean stableChanged,
                                                   boolean packageChanged,
                                                   boolean focusChanged) {
        return new ActionVerificationResult(status, code, screenChanged, stableChanged,
                packageChanged, focusChanged);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
