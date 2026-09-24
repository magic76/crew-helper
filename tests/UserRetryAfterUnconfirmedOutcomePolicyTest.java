package com.crewpocket.helper;

public final class UserRetryAfterUnconfirmedOutcomePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        long now = 10_000L;

        UserRetryAfterUnconfirmedOutcomePolicy.Decision translated =
                UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "幫我暫停一下",
                        "pause",
                        now,
                        now - 2_000L,
                        1,
                        "EVIDENCE_AVAILABLE");
        check(translated.retry, "cross-language pause retry should be detected");
        check("CONTROL:PAUSE".equals(translated.intentFamily),
                "pause retry should use a privacy-safe semantic family");

        UserRetryAfterUnconfirmedOutcomePolicy.Decision exact =
                UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "打開地圖",
                        "打開地圖",
                        now,
                        now - 1_000L,
                        1,
                        "IN_PROGRESS");
        check(exact.retry, "exact repeated command should be detected");
        check("EXACT_REPEAT".equals(exact.intentFamily),
                "exact repeated command should not persist raw text");

        check(!UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "暫停",
                        "下一首",
                        now,
                        now - 1_000L,
                        1,
                        "EVIDENCE_AVAILABLE").retry,
                "different control intents must not be conflated");

        check(!UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "暫停",
                        "pause",
                        now,
                        0L,
                        1,
                        "EVIDENCE_AVAILABLE").retry,
                "retry requires a recent successful mutation");

        check(!UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "暫停",
                        "pause",
                        now,
                        now - UserRetryAfterUnconfirmedOutcomePolicy.RETRY_WINDOW_MS - 1L,
                        1,
                        "EVIDENCE_AVAILABLE").retry,
                "old mutations must not classify a new instruction as retry");

        check(!UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "暫停",
                        "pause",
                        now,
                        now - 1_000L,
                        1,
                        "DONE").retry,
                "terminal goal state must not create retry friction");

        check(!UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        "暫停",
                        "pause",
                        now,
                        now - 1_000L,
                        1,
                        "WAITING_USER").retry,
                "an expected user response must remain a continuation, not retry friction");

        System.out.println(
                "UserRetryAfterUnconfirmedOutcomePolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
