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
        System.out.println("InformationAnswerFastPathPolicyTest passed");
    }

    private static void expect(boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
