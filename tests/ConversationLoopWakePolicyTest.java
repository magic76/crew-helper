package com.crewpocket.helper;

public final class ConversationLoopWakePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                ConversationLoopWakePolicy.decide(false, false)
                        == ConversationLoopWakePolicy.Decision.CREATE_BACKGROUND_TASK,
                "missing task must create a background conversation task");
        check(
                ConversationLoopWakePolicy.decide(true, true)
                        == ConversationLoopWakePolicy.Decision.REUSE_LOOP_TASK,
                "existing loop task may receive the wake");
        check(
                ConversationLoopWakePolicy.decide(true, false)
                        == ConversationLoopWakePolicy.Decision.DEFER_FOR_FOREGROUND_TASK,
                "unrelated foreground task must not be hijacked");

        System.out.println(
                "ConversationLoopWakePolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
