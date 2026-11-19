package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the bounded event that correlates an accepted Pocket advice to its Runtime result. */
final class HelperCoachActionResultPolicy {
    static final String STATUS_EXECUTED = "executed";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_CANCELLED = "cancelled";

    private static final List<String> ACTIONS = Arrays.asList(
            HelperCoachRuntimePolicy.ACTION_INSPECT_UI,
            HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP);
    private static final List<String> EVIDENCE = Arrays.asList(
            "MEDIA_PLAYBACK_BECAME_ACTIVE",
            "RUNTIME_VERIFIED",
            "TAP_EFFECT_OBSERVED",
            "NAVIGATION_TRANSITION_OBSERVED",
            "APP_PACKAGE_TRANSITION_OBSERVED");
    private static final List<String> CANCEL_CODES = Arrays.asList(
            "COACH_ADVICE_STALE",
            "COACH_UI_CHANGED",
            "TASK_CANCELLED",
            "USER_CANCELLED");
    private static final List<String> ERROR_CODES = Arrays.asList(
            "COACH_ADVICE_STALE",
            "COACH_ACTION_MISMATCH",
            "COACH_ACTION_UNSAFE",
            "COACH_ACTION_UNKNOWN",
            "COACH_ACTION_BUILD_FAILED",
            "COACH_QUEUE_FAILED",
            "COACH_REOBSERVE_FAILED",
            "COACH_UI_CHANGED",
            "TASK_CANCELLED",
            "USER_CANCELLED",
            "AMBIGUOUS",
            "APP_NOT_FOUND",
            "ELEMENT_NOT_FOUND",
            "LOCATOR_MARGIN_TOO_SMALL",
            "NO_PROGRESS_LOOP",
            "NOT_FOUND",
            "POLICY_BLOCK",
            "SEARCH_NEEDS_QUERY",
            "SEMANTIC_TARGET_NOT_FOUND",
            "TAP_FAILED",
            "ACTION_FAILED",
            "RUNTIME_ACTION_FAILED");

    private HelperCoachActionResultPolicy() {}

    static String statusForRuntimeResult(boolean success, String errorCode) {
        if (success) return STATUS_EXECUTED;
        return CANCEL_CODES.contains(normalizeCode(errorCode))
                ? STATUS_CANCELLED : STATUS_FAILED;
    }

    static Map<String, Object> buildEvent(
            String eventId,
            String taskId,
            String timestamp,
            String observationEventId,
            String adviceEventId,
            String actionId,
            String status,
            String errorCode,
            boolean runtimeActionSuccess,
            List<String> evidenceRefs) {
        if (!isSafeId(eventId) || !isSafeId(taskId)
                || !isSafeId(observationEventId) || !isSafeId(adviceEventId)
                || !ACTIONS.contains(actionId)) return null;
        String safeStatus = normalizeStatus(status);
        String safeCode = runtimeActionSuccess ? "NONE" : normalizeCode(errorCode);
        ArrayList<String> safeEvidence = new ArrayList<String>();
        if (evidenceRefs != null) {
            for (String reference : evidenceRefs) {
                String value = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
                if (EVIDENCE.contains(value) && !safeEvidence.contains(value)
                        && safeEvidence.size() < 8) safeEvidence.add(value);
            }
        }

        LinkedHashMap<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("protocol_version", 1);
        event.put("event_id", eventId);
        event.put("task_id", taskId);
        event.put("timestamp", timestamp == null ? "" : timestamp);
        event.put("source", "helper");
        event.put("kind", "coach_action_result");
        event.put("observation_event_id", observationEventId);
        event.put("advice_event_id", adviceEventId);
        event.put("suggested_action_id", actionId);
        event.put("status", safeStatus);
        event.put("runtime_action_success", runtimeActionSuccess);
        event.put("error_code", safeCode);
        event.put("evidence_refs", safeEvidence);
        return event;
    }

    private static String normalizeStatus(String status) {
        if (STATUS_EXECUTED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status)) return status;
        return STATUS_FAILED;
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return ERROR_CODES.contains(value)
                ? value : "RUNTIME_ACTION_FAILED";
    }

    private static boolean isSafeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}");
    }
}
