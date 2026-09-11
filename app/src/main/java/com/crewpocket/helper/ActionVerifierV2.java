package com.crewpocket.helper;

/**
 * Pure verifier.  It reconciles Android execution callbacks with post-action
 * semantic UI evidence and returns one runtime-owned truth value.
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

        final boolean screenChanged = after.fingerprintChangedFrom(before);
        final boolean stableChanged = after.stableScreenChangedFrom(before);
        final boolean packageChanged = after.packageChangedFrom(before);
        final boolean focusChanged = after.focusChangedFrom(before);
        final boolean anyObservedChange = screenChanged || stableChanged || packageChanged || focusChanged;

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

        // Domain-specific runtimes such as TYPE and SEND_CURRENT already perform
        // stronger verification than a generic screen fingerprint can provide.
        if (execution.runtimeVerified) {
            return result(ActionVerificationResult.Status.VERIFIED,
                    "RUNTIME_VERIFIED",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        boolean expectationMet = false;
        switch (expected) {
            case APP_CHANGE:
                expectationMet = packageChanged || stableChanged;
                break;
            case SCREEN_CHANGE:
            case SCROLL_CHANGE:
                expectationMet = screenChanged || stableChanged || packageChanged;
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
                // No generic fingerprint may substitute for a domain verifier.
                expectationMet = false;
                break;
            case NO_UI_CHANGE:
                expectationMet = execution.executionAccepted;
                break;
            case UNKNOWN:
            default:
                expectationMet = anyObservedChange;
                break;
        }

        if (expectationMet) {
            // Android callbacks occasionally report false even though the UI did
            // change.  In that case the post-action state is the stronger signal.
            ActionVerificationResult.Status status = execution.executionAccepted
                    ? ActionVerificationResult.Status.VERIFIED
                    : ActionVerificationResult.Status.LIKELY;
            return result(status,
                    execution.executionAccepted ? "EXPECTED_EFFECT_OBSERVED" : "FALSE_NEGATIVE_RECONCILED",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        if (expected == ActionTransaction.ExpectedEffect.NO_UI_CHANGE && execution.executionAccepted) {
            return result(ActionVerificationResult.Status.VERIFIED,
                    "EXECUTION_ACCEPTED_NO_UI_CHANGE_EXPECTED",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        // Accepted asynchronous actions are not called success-without-proof.
        // They become PENDING, which forces one observation before a same-action retry.
        if (execution.executionAccepted) {
            return result(ActionVerificationResult.Status.PENDING,
                    after.available ? "ACCEPTED_NO_EFFECT_OBSERVED" : "ACCEPTED_AWAITING_OBSERVATION",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        // A narrowly whitelisted delayed UI (e.g. Maps/canvas) may still have
        // accepted a gesture even when the accessibility callback is false.
        if (execution.allowDelayedUi && after.available) {
            return result(ActionVerificationResult.Status.PENDING,
                    "DELAYED_UI_AWAITING_OBSERVATION",
                    screenChanged, stableChanged, packageChanged, focusChanged);
        }

        return result(ActionVerificationResult.Status.FAILED,
                nonEmpty(execution.errorCode, after.available
                        ? "NO_OBSERVABLE_EFFECT"
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
}

