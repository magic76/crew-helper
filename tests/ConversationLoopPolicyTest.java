package com.crewpocket.helper;

public final class ConversationLoopPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        eq(ConversationLoopPolicy.ACTION_START,
                ConversationLoopPolicy.normalizeAction("begin"),
                "begin alias");
        eq(ConversationLoopPolicy.ACTION_WAIT,
                ConversationLoopPolicy.normalizeAction("continue"),
                "continue alias");
        eq(ConversationLoopPolicy.ACTION_STOP,
                ConversationLoopPolicy.normalizeAction("cancel"),
                "cancel alias");
        eq(ConversationLoopPolicy.ACTION_STATUS,
                ConversationLoopPolicy.normalizeAction("state"),
                "state alias");

        yes(ConversationLoopPolicy.isStopPhrase("停止對談"),
                "explicit Chinese stop");
        yes(ConversationLoopPolicy.isStopPhrase("不要再幫我回他"),
                "stop auto replying");
        yes(ConversationLoopPolicy.isStopPhrase("stop conversation"),
                "English stop");
        no(ConversationLoopPolicy.isStopPhrase("停止搜尋"),
                "unrelated stop must not revoke loop");

        eq(PendingActionPolicy.CONDITION_SCREEN_CHANGE,
                ConversationLoopPolicy.waitCondition(""),
                "default watcher is screen change");
        eq(PendingActionPolicy.CONDITION_TEXT_APPEARS,
                ConversationLoopPolicy.waitCondition("新訊息"),
                "marker watcher is text appears");

        eq(ConversationLoopPolicy.DEFAULT_DURATION_MINUTES,
                ConversationLoopPolicy.clampDurationMinutes(0),
                "default duration");
        eq(ConversationLoopPolicy.MAX_DURATION_MINUTES,
                ConversationLoopPolicy.clampDurationMinutes(999),
                "duration cap");
        yes(ConversationLoopPolicy.validRecipient("小明"),
                "recipient accepted");
        no(ConversationLoopPolicy.validRecipient(""),
                "empty recipient rejected");

        System.out.println(
                "ConversationLoopPolicyTest passed "
                        + checks
                        + " checks");
    }

    private static void yes(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void no(boolean value, String message) {
        yes(!value, message);
    }

    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    message + " expected=" + expected + " actual=" + actual);
        }
    }
}
