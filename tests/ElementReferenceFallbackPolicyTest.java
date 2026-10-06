package com.crewpocket.helper;

public final class ElementReferenceFallbackPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(ElementReferenceFallbackPolicy.allowModelRecovery(
                        "com.apple.android.music",
                        "MEDIA:PLAY",
                        "element_reference:open",
                        true),
                "trusted active Apple Music playback may open element recovery");

        check(!ElementReferenceFallbackPolicy.allowModelRecovery(
                        "com.apple.android.music",
                        "MEDIA:PLAY",
                        "顯示元素",
                        true),
                "ordinary user phrase is not model fallback authority");

        check(!ElementReferenceFallbackPolicy.allowModelRecovery(
                        "com.apple.android.music",
                        "SEARCH",
                        "element_reference:open",
                        true),
                "non-media goal cannot use automatic element recovery");

        check(!ElementReferenceFallbackPolicy.allowModelRecovery(
                        "com.example.bank",
                        "MEDIA:PLAY",
                        "element_reference:open",
                        true),
                "untrusted app cannot use automatic element recovery");

        check(!ElementReferenceFallbackPolicy.allowModelRecovery(
                        "com.apple.android.music",
                        "MEDIA:PLAY",
                        "element_reference:open",
                        false),
                "no active Agent task means no automatic recovery");

        System.out.println(
                "PASS ElementReferenceFallbackPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
