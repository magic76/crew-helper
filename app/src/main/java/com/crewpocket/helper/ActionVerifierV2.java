package com.crewpocket.helper;

/**
 * 0077 action-specific verifier.
 *
 * Android's callback says whether a command was accepted; this class decides
 * whether the expected semantic effect actually became observable.
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

        // 0077: for SEARCH this means the query-excluding result surface changed.
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
            expectationMet = screenChanged || focusChanged;
            observedCode = "TYPE_EFFECT_OBSERVED";
            pendingCode = "TYPE_AWAITING_TEXT_EVIDENCE";
            noEffectCode = "TYPE_EFFECT_NOT_OBSERVED";
        } else if ("search_current_app".equals(name)) {
            // Generic ScreenFingerprint includes query text, so screenChanged
            // can be caused by typing alone. It must never verify SEARCH.
            expectationMet = false;
            observedCode = "SEARCH_RESULTS_OBSERVED";
            pendingCode = "SEARCH_AWAITING_RESULTS_EVIDENCE";
            noEffectCode = "SEARCH_RESULTS_NOT_OBSERVED";
        } else if ("commit_search".equals(name)) {
            // IME Search/Enter accepted != results. SearchCommitRuntime provides
            // runtimeVerified only after result-surface evidence.
            expectationMet = false;
            observedCode = "SEARCH_COMMIT_RESULTS_OBSERVED";
            pendingCode = "SEARCH_COMMIT_AWAITING_RESULTS";
            noEffectCode = "SEARCH_COMMIT_RESULTS_NOT_OBSERVED";
        } else if ("swipe_screen".equals(name)) {
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
