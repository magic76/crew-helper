package com.crewpocket.helper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact, privacy-bounded task receipt event for the Pocket Coach feed. */
final class HelperCoachTaskReceiptPolicy {
    static final String[] FIELDS = new String[] {
            "protocol_version",
            "event_id",
            "task_id",
            "timestamp",
            "source",
            "kind",
            "app_package",
            "goal"
    };

    private HelperCoachTaskReceiptPolicy() {}

    static Map<String, Object> buildEvent(
            String eventId,
            String taskId,
            String timestamp,
            String appPackage,
            String goalIntent) {
        if (!safeId(eventId) || !safeId(taskId) || timestamp == null
                || timestamp.trim().isEmpty()) return null;

        LinkedHashMap<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("protocol_version", 1);
        event.put("event_id", eventId.trim());
        event.put("task_id", taskId.trim());
        event.put("timestamp", timestamp.trim());
        event.put("source", "helper");
        event.put("kind", "task_received");
        event.put("app_package", safePackage(appPackage));
        event.put("goal", GoalIntentKey.closedValue(goalIntent));
        return event;
    }

    private static boolean safeId(String value) {
        return value != null && value.trim().matches("[A-Za-z0-9_.:-]{1,128}");
    }

    private static String safePackage(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                ? text : "";
    }
}
