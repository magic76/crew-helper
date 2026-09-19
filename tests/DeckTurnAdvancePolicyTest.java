package com.crewpocket.helper;

public final class DeckTurnAdvancePolicyTest {
    private static int checks;

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(DeckTurnAdvancePolicy.shouldAdvance(true, true, false),
                "audible narration may advance");
        check(!DeckTurnAdvancePolicy.shouldAdvance(true, true, true),
                "non-deck tool call blocks advance");
        check(!DeckTurnAdvancePolicy.shouldAdvance(true, false, false),
                "transcript-only turn cannot advance");
        check(!DeckTurnAdvancePolicy.shouldAdvance(false, true, false),
                "audio without final narration cannot advance");
        System.out.println("PASS DeckTurnAdvancePolicyTest: " + checks + " checks");
    }
}
