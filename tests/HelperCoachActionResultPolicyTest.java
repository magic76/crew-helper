package com.crewpocket.helper;

import java.util.Arrays;
import java.util.Map;

public final class HelperCoachActionResultPolicyTest {
    public static void main(String[] args) {
        acceptedAdviceProducesCorrelatedRuntimeResult();
        unsafeFieldsAreDroppedAndTerminalStatusIsBounded();
        System.out.println("HelperCoachActionResultPolicyTest passed");
    }

    private static void acceptedAdviceProducesCorrelatedRuntimeResult() {
        Map<String, Object> event = HelperCoachActionResultPolicy.buildEvent(
                "result-1", "task-1", "2026-11-18T15:00:00Z",
                "observation-1", "advice-1",
                HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                HelperCoachActionResultPolicy.STATUS_EXECUTED, "NONE", true,
                Arrays.asList("MEDIA_PLAYBACK_BECAME_ACTIVE", "private label"));
        check(event != null, "valid advice result is built");
        check("coach_action_result".equals(event.get("kind")), "stable result event kind");
        check("observation-1".equals(event.get("observation_event_id")), "observation is correlated");
        check("advice-1".equals(event.get("advice_event_id")), "advice is correlated");
        check(Boolean.TRUE.equals(event.get("runtime_action_success")), "action result is explicit");
        check(event.get("evidence_refs").toString().contains("MEDIA_PLAYBACK_BECAME_ACTIVE"),
                "allowlisted Runtime evidence is retained");
        check(!event.get("evidence_refs").toString().contains("private label"),
                "arbitrary screen text is omitted");
    }

    private static void unsafeFieldsAreDroppedAndTerminalStatusIsBounded() {
        Map<String, Object> invalidAction = HelperCoachActionResultPolicy.buildEvent(
                "result-2", "task-1", "2026-11-18T15:00:00Z",
                "observation-1", "advice-2", "launch_app",
                HelperCoachActionResultPolicy.STATUS_FAILED, "PRIVATE_SEARCH_RESULT", false,
                Arrays.asList("query=private"));
        check(invalidAction == null, "unknown action cannot be reported as accepted advice");
        Map<String, Object> failed = HelperCoachActionResultPolicy.buildEvent(
                "result-3", "task-1", "2026-11-18T15:00:00Z",
                "observation-1", "advice-3",
                HelperCoachRuntimePolicy.ACTION_INSPECT_UI,
                HelperCoachActionResultPolicy.STATUS_FAILED, "PRIVATE_SEARCH_RESULT", false,
                Arrays.asList("query=private"));
        check("RUNTIME_ACTION_FAILED".equals(failed.get("error_code")),
                "free-form error text is replaced by a safe code");
        check("[]".equals(failed.get("evidence_refs").toString()),
                "untrusted evidence references are omitted");
        check(HelperCoachActionResultPolicy.STATUS_CANCELLED.equals(
                HelperCoachActionResultPolicy.statusForRuntimeResult(false, "COACH_UI_CHANGED")),
                "changed UI cancels accepted advice");
        check(HelperCoachActionResultPolicy.STATUS_FAILED.equals(
                HelperCoachActionResultPolicy.statusForRuntimeResult(false, "LOCATOR_FAILED")),
                "ordinary Runtime errors are failures");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
