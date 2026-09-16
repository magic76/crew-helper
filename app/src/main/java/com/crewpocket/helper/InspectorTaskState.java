package com.crewpocket.helper;

import java.util.Locale;

/** Privacy-safe terminal state classifier for Agent Inspector. */
final class InspectorTaskState {
    static final String ACTIVE = "ACTIVE";
    static final String COMPLETED = "COMPLETED";
    static final String CANCELLED = "CANCELLED";
    static final String FAILED = "FAILED";

    private InspectorTaskState() {}

    static String classify(boolean active,
                           boolean cancelled,
                           String status,
                           String endReason,
                           String blockedReason) {
        if (active) return ACTIVE;

        // blockedReason is intentionally not part of terminal-state identity.
        // A task can hit a temporary stability/policy block, recover, and still
        // complete successfully. Historical block evidence remains reported
        // separately by AgentInspectorStore.
        String text = ((status == null ? "" : status) + " "
                + (endReason == null ? "" : endReason))
                .toLowerCase(Locale.ROOT);

        if (cancelled || containsAny(text,
                "cancelled", "canceled", "user cancelled", "user canceled",
                "使用者取消", "任務已停止", "新使用者指令取代", "superseded")) {
            return CANCELLED;
        }

        if (containsAny(text,
                "failed", "error", "timeout", "timed out", "blocked",
                "失敗", "錯誤", "错误", "未完成", "逾時", "超時", "阻擋", "阻止")) {
            return FAILED;
        }
        return COMPLETED;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && value.contains(needle)) return true;
        }
        return false;
    }
}
