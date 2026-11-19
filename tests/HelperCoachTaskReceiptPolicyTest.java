package com.crewpocket.helper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HelperCoachTaskReceiptPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        Map<String, Object> event = HelperCoachTaskReceiptPolicy.buildEvent(
                "receipt-1", "agent_123", "2026-11-20T12:00:00Z",
                "com.example.music", "MEDIA:PLAY");
        check(event != null, "valid task receipt is built");
        check(Arrays.equals(
                        HelperCoachTaskReceiptPolicy.FIELDS,
                        event.keySet().toArray(new String[0])),
                "receipt fields are exact and ordered");
        check(Integer.valueOf(1).equals(event.get("protocol_version")),
                "protocol version is v1");
        check("task_received".equals(event.get("kind")),
                "receipt kind is task_received");
        check("agent_123".equals(event.get("task_id")),
                "receipt preserves the stable task id");

        Map<String, Object> unknowns = HelperCoachTaskReceiptPolicy.buildEvent(
                "receipt-2", "agent_124", "2026-11-20T12:00:00Z",
                "not a package", "free form secret text");
        check("".equals(unknowns.get("app_package")),
                "unknown app becomes empty");
        check("".equals(unknowns.get("goal")),
                "non-GoalIntent content becomes empty");
        check(HelperCoachTaskReceiptPolicy.buildEvent(
                        "receipt-3", "", "2026-11-20T12:00:00Z", "", "") == null,
                "invalid task id is rejected");
        check(GoalIntentKey.isAllowed("MEDIA:PLAY"),
                "closed GoalIntent is allowed");
        check(!GoalIntentKey.isAllowed("MEDIA:PLAY secret"),
                "GoalIntent allowlist rejects arbitrary suffixes");

        System.out.println("HelperCoachTaskReceiptPolicyTest passed "
                + checks + " checks");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
