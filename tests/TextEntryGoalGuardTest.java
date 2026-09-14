package com.crewpocket.helper;

public final class TextEntryGoalGuardTest {
    private static int checks;

    public static void main(String[] args) {
        TextEntryGoalGuard.updateFromUserText("幫我打字打一百個字");
        TextEntryGoalGuard.Validation shortText = TextEntryGoalGuard.validate("只有五個字");
        check(shortText.constrained, "100-char request should arm constraint");
        check(!shortText.allowed, "5 chars must not satisfy 100-char request");
        check(shortText.expected == 100, "expected count should be 100");
        check(shortText.actual == 5, "actual count should be 5");
        check("TYPE_LENGTH_MISMATCH_EXPECTED_100_ACTUAL_5".equals(shortText.errorCode()),
                "mismatch error code should be privacy-safe counts only");

        TextEntryGoalGuard.Validation approximate = TextEntryGoalGuard.validate(repeat("字", 100));
        check(approximate.allowed, "100 chars should satisfy approximate 100-char request");
        check(TextEntryGoalGuard.validate(repeat("字", 91)).allowed,
                "approximate request should allow 90-110 percent range");
        check(!TextEntryGoalGuard.validate(repeat("字", 89)).allowed,
                "approximate request should reject materially short text");

        TextEntryGoalGuard.updateFromUserText("幫我輸入至少 20 個字");
        check(!TextEntryGoalGuard.validate(repeat("字", 19)).allowed,
                "minimum request should reject 19 of 20");
        check(TextEntryGoalGuard.validate(repeat("字", 20)).allowed,
                "minimum request should accept 20 of 20");
        check(TextEntryGoalGuard.validate(repeat("字", 30)).allowed,
                "minimum request should allow more than requested");

        TextEntryGoalGuard.updateFromUserText("請剛好輸入10個字");
        check(!TextEntryGoalGuard.validate(repeat("字", 9)).allowed,
                "exact request should reject short text");
        check(TextEntryGoalGuard.validate(repeat("字", 10)).allowed,
                "exact request should accept exact text");
        check(!TextEntryGoalGuard.validate(repeat("字", 11)).allowed,
                "exact request should reject long text");

        TextEntryGoalGuard.updateFromUserText("請輸入十二個字");
        TextEntryGoalGuard.Validation chineseCount = TextEntryGoalGuard.validate(repeat("字", 12));
        check(chineseCount.expected == 12 && chineseCount.allowed,
                "Chinese numeral count should parse");

        TextEntryGoalGuard.updateFromUserText("搜尋100個字");
        check(!TextEntryGoalGuard.validate("短").constrained,
                "search request must not arm TYPE length constraint");

        TextEntryGoalGuard.updateFromUserText("不要打字打一百個字");
        check(!TextEntryGoalGuard.validate("短").constrained,
                "negated typing request must not arm constraint");

        TextEntryGoalGuard.clear();
        System.out.println("TextEntryGoalGuardTest passed " + checks + " checks");
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
