package com.crewpocket.helper;

import java.util.List;

public final class VoiceCommandQualityPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        assertTrue(
                VoiceCommandQualityPolicy.looksIncomplete("幫我傳給"),
                "dangling recipient phrase must be incomplete");
        assertTrue(
                VoiceCommandQualityPolicy.looksIncomplete("我想"),
                "dangling intent must be incomplete");
        assertFalse(
                VoiceCommandQualityPolicy.looksIncomplete("送出"),
                "short explicit send command is complete");
        assertFalse(
                VoiceCommandQualityPolicy.looksIncomplete("對"),
                "confirmation is complete");
        assertTrue(
                VoiceCommandQualityPolicy.isAffirmative("確認"),
                "confirmation keyword");
        assertTrue(
                VoiceCommandQualityPolicy.isNegative("不對"),
                "negative correction keyword");

        List<String> entities =
                VoiceCommandQualityPolicy.criticalEntities(
                        "跟小明說七點半在 101 見");
        String summary =
                VoiceCommandQualityPolicy.summary(entities);
        assertContains(summary, "小明", "recipient should be captured");
        assertContains(summary, "七點半", "Chinese spoken time should be captured");
        assertContains(summary, "101", "numeric entity should be captured");

        List<String> numeric =
                VoiceCommandQualityPolicy.criticalEntities(
                        "跟 Amy 說 19:30 到，代碼 4486819");
        String numericSummary =
                VoiceCommandQualityPolicy.summary(numeric);
        assertContains(numericSummary, "Amy", "English recipient should be captured");
        assertContains(numericSummary, "19:30", "clock time should be captured");
        assertContains(numericSummary, "4486819", "number should be captured");

        System.out.println(
                "VoiceCommandQualityPolicyTest passed "
                        + checks
                        + " checks");
    }

    private static void assertTrue(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertContains(
            String value,
            String expected,
            String message) {
        checks++;
        if (value == null || !value.contains(expected)) {
            throw new AssertionError(
                    message
                            + " expected="
                            + expected
                            + " actual="
                            + value);
        }
    }
}
