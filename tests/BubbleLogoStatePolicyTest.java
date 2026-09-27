package com.crewpocket.helper;

public final class BubbleLogoStatePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(resolve(0, BubbleTaskPhasePolicy.Phase.NONE, false, false)
                        == BubbleLogoStatePolicy.Mode.IDLE,
                "idle");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.NONE, false, false)
                        == BubbleLogoStatePolicy.Mode.LISTENING,
                "listening");
        check(resolve(2, BubbleTaskPhasePolicy.Phase.NONE, false, false)
                        == BubbleLogoStatePolicy.Mode.SPEAKING,
                "speaking");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.THINKING, false, true)
                        == BubbleLogoStatePolicy.Mode.THINKING,
                "thinking wins over background waiting");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.ACTING, false, true)
                        == BubbleLogoStatePolicy.Mode.ACTING,
                "acting wins over background waiting");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.WAITING, false, false)
                        == BubbleLogoStatePolicy.Mode.WAITING,
                "screen waiting");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.STUCK, false, false)
                        == BubbleLogoStatePolicy.Mode.STUCK,
                "stuck");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.ACTING, true, true)
                        == BubbleLogoStatePolicy.Mode.WAITING_USER,
                "user attention wins over task phase");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.NONE, false, true)
                        == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING,
                "conversation waiting wins over generic listening");
        check(resolve(1, BubbleTaskPhasePolicy.Phase.WAITING, false, true)
                        == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING,
                "reply waiting wins over generic screen waiting");
        check(resolve(3, BubbleTaskPhasePolicy.Phase.STUCK, true, true)
                        == BubbleLogoStatePolicy.Mode.ERROR,
                "error wins all");

        System.out.println(
                "BubbleLogoStatePolicyTest passed "
                        + checks + " checks");
    }

    private static BubbleLogoStatePolicy.Mode resolve(
            int voice,
            BubbleTaskPhasePolicy.Phase phase,
            boolean attention,
            boolean waiting) {
        return BubbleLogoStatePolicy.resolve(
                voice, phase, attention, waiting);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
