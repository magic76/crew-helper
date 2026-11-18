package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime-owned freshness, allowlist, confirmation and replay checks for Pocket advice. */
final class HelperCoachRuntimePolicy {
    static final String ACTION_INSPECT_UI = "inspect_ui";
    static final String ACTION_RETRY_PREVIOUS_SAFE_TAP = "retry_previous_safe_tap";

    static final class Observation {
        final String eventId;
        final String taskId;
        final String appPackage;
        final String uiFingerprint;
        final String expectedPostcondition;
        final long createdAtMs;
        final long expiresAtMs;
        final boolean previousTapRetryable;
        final ArrayList<String> availableActions;

        Observation(
                String eventId,
                String taskId,
                String appPackage,
                String uiFingerprint,
                String expectedPostcondition,
                long createdAtMs,
                long expiresAtMs,
                boolean previousTapRetryable,
                List<String> actions) {
            this.eventId = clean(eventId);
            this.taskId = clean(taskId);
            this.appPackage = clean(appPackage);
            this.uiFingerprint = clean(uiFingerprint);
            this.expectedPostcondition = clean(expectedPostcondition);
            this.createdAtMs = Math.max(0L, createdAtMs);
            this.expiresAtMs = Math.max(0L, expiresAtMs);
            this.previousTapRetryable = previousTapRetryable;
            this.availableActions = new ArrayList<String>();
            if (actions != null) {
                for (String action : actions) {
                    String value = clean(action);
                    if (!value.isEmpty() && !this.availableActions.contains(value)) {
                        this.availableActions.add(value);
                    }
                }
            }
        }

        boolean advertises(String actionId) {
            return availableActions.contains(clean(actionId));
        }
    }

    static final class Advice {
        final String adviceId;
        final String observationEventId;
        final String taskId;
        final String appPackage;
        final String uiFingerprint;
        final String suggestedActionId;
        final String expectedPostcondition;
        final double confidence;
        final long expiresAtMs;
        final boolean requiresUserConfirmation;

        Advice(
                String adviceId,
                String observationEventId,
                String taskId,
                String appPackage,
                String uiFingerprint,
                String suggestedActionId,
                String expectedPostcondition,
                double confidence,
                long expiresAtMs,
                boolean requiresUserConfirmation) {
            this.adviceId = clean(adviceId);
            this.observationEventId = clean(observationEventId);
            this.taskId = clean(taskId);
            this.appPackage = clean(appPackage);
            this.uiFingerprint = clean(uiFingerprint);
            this.suggestedActionId = clean(suggestedActionId);
            this.expectedPostcondition = clean(expectedPostcondition);
            this.confidence = Math.max(0d, Math.min(1d, confidence));
            this.expiresAtMs = Math.max(0L, expiresAtMs);
            this.requiresUserConfirmation = requiresUserConfirmation;
        }
    }

    enum Decision {
        ACCEPT,
        NEEDS_USER_CONFIRMATION,
        MISSING_OBSERVATION,
        STALE_TASK,
        STALE_APP,
        UI_CHANGED,
        EXPIRED,
        UNKNOWN_ACTION,
        ACTION_NOT_ADVERTISED,
        UNSAFE_RETRY,
        POSTCONDITION_MISMATCH,
        LOW_CONFIDENCE,
        DUPLICATE_ADVICE,
        INVALID_MESSAGE
    }

    private static final int MAX_USED_ADVICE = 512;
    private final Set<String> usedAdvice = new HashSet<String>();
    private final ArrayList<String> usedOrder = new ArrayList<String>();

    synchronized Decision evaluate(
            Observation observation,
            Advice advice,
            String activeTaskId,
            String currentAppPackage,
            String currentUiFingerprint,
            long nowMs) {
        if (observation == null || advice == null) return Decision.MISSING_OBSERVATION;
        if (advice.adviceId.isEmpty() || advice.observationEventId.isEmpty()) {
            return Decision.INVALID_MESSAGE;
        }
        if (usedAdvice.contains(advice.adviceId)) return Decision.DUPLICATE_ADVICE;
        if (!observation.eventId.equals(advice.observationEventId)) {
            return Decision.MISSING_OBSERVATION;
        }
        if (!observation.taskId.equals(advice.taskId)
                || !observation.taskId.equals(clean(activeTaskId))) {
            return Decision.STALE_TASK;
        }
        if (!observation.appPackage.equals(advice.appPackage)
                || !observation.appPackage.equals(clean(currentAppPackage))) {
            return Decision.STALE_APP;
        }
        if (!observation.uiFingerprint.equals(advice.uiFingerprint)
                || !observation.uiFingerprint.equals(clean(currentUiFingerprint))) {
            return Decision.UI_CHANGED;
        }
        if (nowMs > observation.expiresAtMs
                || advice.expiresAtMs <= nowMs
                || advice.expiresAtMs > observation.expiresAtMs) {
            return Decision.EXPIRED;
        }
        if (advice.requiresUserConfirmation) {
            return Decision.NEEDS_USER_CONFIRMATION;
        }
        if (advice.confidence < 0.5d) return Decision.LOW_CONFIDENCE;
        if (!ACTION_INSPECT_UI.equals(advice.suggestedActionId)
                && !ACTION_RETRY_PREVIOUS_SAFE_TAP.equals(
                        advice.suggestedActionId)) {
            return Decision.UNKNOWN_ACTION;
        }
        if (!observation.advertises(advice.suggestedActionId)) {
            return Decision.ACTION_NOT_ADVERTISED;
        }
        if (ACTION_INSPECT_UI.equals(advice.suggestedActionId)) {
            if (!"FRESH_OBSERVATION".equals(advice.expectedPostcondition)) {
                return Decision.POSTCONDITION_MISMATCH;
            }
        } else if (ACTION_RETRY_PREVIOUS_SAFE_TAP.equals(advice.suggestedActionId)) {
            if (!observation.previousTapRetryable) return Decision.UNSAFE_RETRY;
            if (observation.expectedPostcondition.isEmpty()
                    || !observation.expectedPostcondition.equals(
                            advice.expectedPostcondition)) {
                return Decision.POSTCONDITION_MISMATCH;
            }
        } else {
            return Decision.UNKNOWN_ACTION;
        }
        rememberUsed(advice.adviceId);
        return Decision.ACCEPT;
    }

    synchronized int usedAdviceCount() {
        return usedAdvice.size();
    }

    private void rememberUsed(String id) {
        usedAdvice.add(id);
        usedOrder.add(id);
        while (usedOrder.size() > MAX_USED_ADVICE) {
            usedAdvice.remove(usedOrder.remove(0));
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
