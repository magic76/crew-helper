package com.crewpocket.helper;

public final class LiveInteractionCompletionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!LiveInteractionCompletionPolicy.isComplete(
                        "IN_PROGRESS", false, false, 0),
                "thinking continuation does not finish the interaction");
        check(!LiveInteractionCompletionPolicy.isComplete(
                        "", false, false, 0),
                "turnComplete without authoritative status is not sufficient");
        check(!LiveInteractionCompletionPolicy.isComplete(
                        "IDLE", false, true, 0),
                "IDLE in same frame as tool call does not finish");
        check(!LiveInteractionCompletionPolicy.isComplete(
                        "IDLE", false, false, 1),
                "IDLE while async function call is running does not finish");
        check(!LiveInteractionCompletionPolicy.isComplete(
                        "IDLE", true, false, 0),
                "IDLE paired with fresh transcript does not end that input");
        check(LiveInteractionCompletionPolicy.isComplete(
                        "IDLE", false, false, 0),
                "IDLE after final async tool result may end interaction");
        check(LiveInteractionCompletionPolicy.isComplete(
                        "idle", false, false, 0),
                "server status matching is case insensitive");
        System.out.println(
                "LiveInteractionCompletionPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
