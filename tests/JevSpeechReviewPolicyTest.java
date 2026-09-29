package com.crewpocket.helper;

public final class JevSpeechReviewPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!JevSpeechReviewPolicy.shouldReview(
                        "搜尋林家花園", 0.70d, false),
                "missing Jev key keeps original voice path");
        check(!JevSpeechReviewPolicy.shouldReview(
                        "好", 0.40d, true),
                "short confirmation should not pay Jev latency");
        check(JevSpeechReviewPolicy.shouldReview(
                        "搜尋林家花園", 0.96d, true),
                "actionable search receives one Jev decision pack");
        check(JevSpeechReviewPolicy.shouldReview(
                        "播放 Lovefool", 0.95d, true),
                "mixed-script media entity receives semantic review");
        check(JevSpeechReviewPolicy.shouldReview(
                        "傳給 Jeremy", 0.91d, true),
                "recipient-dependent action receives semantic review");
        check(JevSpeechReviewPolicy.shouldReview(
                        "今天好像有點冷", 0.60d, true),
                "low-confidence finalized speech may be reviewed");

        check(!JevSpeechReviewPolicy.shouldApply(
                        "USE_RAW", 0.99d),
                "trust decision never injects extra guidance");
        check(!JevSpeechReviewPolicy.shouldApply(
                        "VERIFY_WITH_UI", 0.40d),
                "low-confidence Jev result cannot override Runtime");
        check(JevSpeechReviewPolicy.shouldApply(
                        "VERIFY_WITH_UI", 0.72d),
                "confident verification strategy is applied");
        check(JevSpeechReviewPolicy.shouldApply(
                        "ASK_USER", 0.80d),
                "confident unresolved ambiguity may request clarification");

        System.out.println(
                "PASS JevSpeechReviewPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
