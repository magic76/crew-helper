package com.crewpocket.helper;

public final class TrustScorePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(TrustScorePolicy.score(0, 0, 0, 0) == -1,
                "empty history has no score");
        check(TrustScorePolicy.score(10, 0, 0, 10) == 100,
                "clean success scores 100");
        check(TrustScorePolicy.score(0, 10, 0, 10) == 85,
                "recovered success is discounted");
        check(TrustScorePolicy.score(8, 0, 1, 10) == 84,
                "partial and failed work lower trust");
        check("CALIBRATING".equals(TrustScorePolicy.band(100, 4)),
                "small sample stays calibrating");
        check("HIGH".equals(TrustScorePolicy.band(93, 12)),
                "high band recognized");
        check("MEDIUM".equals(TrustScorePolicy.band(80, 12)),
                "medium band recognized");
        check("LOW".equals(TrustScorePolicy.band(60, 12)),
                "low band recognized");

        System.out.println("TrustScorePolicyTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
