package com.crewpocket.helper;

public final class BubbleLogoStatePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(resolve(0, false, false, false)
                        == BubbleLogoStatePolicy.Mode.IDLE,
                "idle");
        check(resolve(1, false, false, false)
                        == BubbleLogoStatePolicy.Mode.LISTENING,
                "listening");
        check(resolve(2, false, false, false)
                        == BubbleLogoStatePolicy.Mode.SPEAKING,
                "speaking");
        check(resolve(1, true, false, true)
                        == BubbleLogoStatePolicy.Mode.WORKING,
                "working wins over background waiting");
        check(resolve(1, false, true, true)
                        == BubbleLogoStatePolicy.Mode.WAITING_USER,
                "user attention wins over waiting");
        check(resolve(1, false, false, true)
                        == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING,
                "conversation waiting wins over generic listening");
        check(resolve(3, true, true, true)
                        == BubbleLogoStatePolicy.Mode.ERROR,
                "error wins all");

        System.out.println(
                "BubbleLogoStatePolicyTest passed "
                        + checks + " checks");
    }

    private static BubbleLogoStatePolicy.Mode resolve(
            int voice,
            boolean working,
            boolean attention,
            boolean waiting) {
        return BubbleLogoStatePolicy.resolve(
                voice, working, attention, waiting);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
