package com.crewpocket.helper;

import java.util.Locale;

/** Pure policy/state labels for the explicit continuous conversation recipe. */
final class ConversationLoopPolicy {
    static final String RECIPE_TYPE = "CONVERSATION_LOOP";

    static final String ACTION_START = "START";
    static final String ACTION_WAIT = "WAIT";
    static final String ACTION_STOP = "STOP";
    static final String ACTION_STATUS = "STATUS";

    static final int DEFAULT_DURATION_MINUTES = 30;
    static final int MAX_DURATION_MINUTES = 60;

    private ConversationLoopPolicy() {}

    static String normalizeAction(String value) {
        String action = value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
        if ("START".equals(action)
                || "BEGIN".equals(action)
                || "ON".equals(action)) {
            return ACTION_START;
        }
        if ("WAIT".equals(action)
                || "CONTINUE".equals(action)
                || "REARM".equals(action)) {
            return ACTION_WAIT;
        }
        if ("STOP".equals(action)
                || "END".equals(action)
                || "OFF".equals(action)
                || "CANCEL".equals(action)) {
            return ACTION_STOP;
        }
        if ("STATUS".equals(action)
                || "STATE".equals(action)) {
            return ACTION_STATUS;
        }
        return "";
    }

    static boolean isStopPhrase(String raw) {
        String text = compact(raw);
        if (text.isEmpty()) return false;
        return containsAny(
                text,
                "停止對談",
                "停止对谈",
                "結束對談",
                "结束对谈",
                "停止對話模式",
                "停止对话模式",
                "結束對話模式",
                "结束对话模式",
                "不要再幫我回",
                "不要再帮我回",
                "先不要回他",
                "先不要回她",
                "停止幫我回",
                "停止帮我回",
                "stopconversation",
                "stopreplying",
                "stopautoreply");
    }

    static boolean validRecipient(String recipient) {
        String value = recipient == null ? "" : recipient.trim();
        return value.length() >= 1 && value.length() <= 40;
    }

    static int clampDurationMinutes(int value) {
        if (value <= 0) return DEFAULT_DURATION_MINUTES;
        return Math.max(1, Math.min(MAX_DURATION_MINUTES, value));
    }

    static String waitCondition(String marker) {
        return marker == null || marker.trim().isEmpty()
                ? PendingActionPolicy.CONDITION_SCREEN_CHANGE
                : PendingActionPolicy.CONDITION_TEXT_APPEARS;
    }

    private static String compact(String raw) {
        return TextMatch.caseFold(raw == null ? "" : raw)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]", "")
                .trim();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            String normalized = compact(needle);
            if (!normalized.isEmpty() && value.contains(normalized)) {
                return true;
            }
        }
        return false;
    }
}
