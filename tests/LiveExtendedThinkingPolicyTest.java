package com.crewpocket.helper;

public final class LiveExtendedThinkingPolicyTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!LiveExtendedThinkingPolicy.isExtended("gemini-3.8-live"),
                "standard remains default");
        check(LiveExtendedThinkingPolicy.isExtended(
                "gemini-3.8-live-extended-thinking"), "exact opt-in");
        check(LiveExtendedThinkingPolicy.STANDARD_MODEL.equals(
                LiveExtendedThinkingPolicy.safeModel("unknown")), "invalid model fail closed");
        check("BLOCKING".equals(LiveExtendedThinkingPolicy.toolBehavior(false)),
                "ordinary Live behavior unchanged");
        check("NON_BLOCKING".equals(LiveExtendedThinkingPolicy.toolBehavior(true)),
                "thinking tools are asynchronous");
        check(LiveExtendedThinkingPolicy.interactionComplete(false,true,"",false),
                "ordinary Live finishes at turnComplete");
        check(!LiveExtendedThinkingPolicy.interactionComplete(true,true,"IN_PROGRESS",false),
                "intermediate filler must not end agent interaction");
        check(!LiveExtendedThinkingPolicy.interactionComplete(true,true,"",false),
                "missing status must not end thinking interaction");
        check(!LiveExtendedThinkingPolicy.interactionComplete(true,false,"IDLE",true),
                "in-flight nonblocking tools must not be interrupted");
        check(LiveExtendedThinkingPolicy.interactionComplete(true,false,"IDLE",false),
                "standalone IDLE ends interaction");
        check(LiveExtendedThinkingPolicy.interactionComplete(true,true,"IDLE",false),
                "IDLE accompanying final speech ends interaction");
        check(LiveExtendedThinkingPolicy.interactionInProgress(true,"IN_PROGRESS"),
                "in progress tracked even without turnComplete");
        check(!LiveExtendedThinkingPolicy.interactionInProgress(false,"IN_PROGRESS"),
                "ordinary Live unaffected");
        System.out.println("PASS LiveExtendedThinkingPolicyTest: "+checks+" checks");
    }
}
