package com.crewpocket.helper;

public final class InformationAnswerFastPathPolicyTest {
    public static void main(String[] args) {
        expect(true, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 0, false, false));
        expect(true, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 0, 1, false, false));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "swipe_screen", true, 1, 1, false, false));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", false, 1, 1, false, false));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 0, 0, false, false));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, true, false));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, false, true));

        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, false, false,
                "搜尋大皇宮並導航過去", ""));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, false, false,
                "搜尋大皇宮，按路線然後開始導航", ""));
        expect(false, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, false, false,
                "find Grand Palace and start navigation", ""));
        expect(true, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, false, false,
                "大皇宮幾點關門", ""));
        expect(true, InformationAnswerFastPathPolicy.shouldOffer(
                "inspect_ui", true, 1, 1, false, false,
                "Grand Palace opening hours", ""));
        System.out.println("InformationAnswerFastPathPolicyTest passed");
    }

    private static void expect(boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
