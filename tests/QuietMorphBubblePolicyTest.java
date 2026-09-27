package com.crewpocket.helper;

public final class QuietMorphBubblePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.THINKING, false),
                "thinking stays logo-only");
        check(!QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.ACTING, false),
                "acting stays logo-only");
        check(!QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.LISTENING, false),
                "listening stays logo-only");
        check(!QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.SPEAKING, false),
                "speaking stays logo-only");
        check(!QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING, false),
                "conversation wait stays logo-only");

        check(!QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.WAITING, false),
                "short wait stays logo-only");
        check(QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.WAITING, true),
                "long wait morphs after delay");

        check(QuietMorphBubblePolicy.shouldShowPersistentMorph(
                        BubbleLogoStatePolicy.Mode.WAITING_USER, false),
                "user attention always morphs");
        check("需要你".equals(QuietMorphBubblePolicy.persistentLabel(
                        BubbleLogoStatePolicy.Mode.WAITING_USER, false)),
                "user attention label");
        check("需要你".equals(QuietMorphBubblePolicy.persistentLabel(
                        BubbleLogoStatePolicy.Mode.STUCK, false)),
                "stuck is action-oriented instead of system-centric");
        check("連線異常".equals(QuietMorphBubblePolicy.persistentLabel(
                        BubbleLogoStatePolicy.Mode.ERROR, false)),
                "error label");
        check(QuietMorphBubblePolicy.DONE_MORPH_MS < 1_300L,
                "done acknowledgement is shorter than the old capsule");

        System.out.println(
                "PASS QuietMorphBubblePolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
