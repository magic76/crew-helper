package com.crewpocket.helper;

public final class ConversationLoopTakeoverPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                ConversationLoopTakeoverPolicy.isHumanComposerEdit(
                        true, true, true, false),
                "focused editable text change is human takeover");
        check(
                !ConversationLoopTakeoverPolicy.isHumanComposerEdit(
                        true, false, false, false),
                "incoming message text is not human takeover");
        check(
                !ConversationLoopTakeoverPolicy.isHumanComposerEdit(
                        true, true, false, false),
                "unfocused editable node is not human takeover");
        check(
                !ConversationLoopTakeoverPolicy.isHumanComposerEdit(
                        true, true, true, true),
                "runtime-owned text mutation is not human takeover");
        check(
                !ConversationLoopTakeoverPolicy.isHumanComposerEdit(
                        false, true, true, false),
                "non-text UI change is not human takeover");

        System.out.println(
                "PASS ConversationLoopTakeoverPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
