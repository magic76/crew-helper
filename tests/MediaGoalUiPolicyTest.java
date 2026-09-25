package com.crewpocket.helper;

public final class MediaGoalUiPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        int playScore = MediaGoalUiPolicy.scoreItem(
                "button", "播放", "", "tap",
                "MEDIA:PLAY", "播放鄧紫棋");
        int artistScore = MediaGoalUiPolicy.scoreItem(
                "button", "鄧紫棋", "", "tap",
                "MEDIA:PLAY", "播放鄧紫棋");
        int otherScore = MediaGoalUiPolicy.scoreItem(
                "button", "更多", "", "tap",
                "MEDIA:PLAY", "播放鄧紫棋");

        check(playScore > otherScore,
                "play control receives media-goal priority");
        check(artistScore > otherScore,
                "goal-mentioned media entity is prioritized");
        check(artistScore > playScore,
                "unresolved actionable goal entity stays ahead of generic Play");

        check(MediaGoalUiPolicy.isGoalEntityLabel(
                        "鄧紫棋", "播放鄧紫棋"),
                "artist label is recognized from goal text");
        check(!MediaGoalUiPolicy.isGoalEntityLabel(
                        "播放", "播放鄧紫棋"),
                "play control is not treated as unresolved media entity");

        check(MediaGoalUiPolicy.isEligiblePlayCandidate(
                        "button",
                        "播放",
                        "",
                        true,
                        true,
                        false,
                        0.92),
                "unique confident play node is eligible for recovery");
        check(!MediaGoalUiPolicy.isEligiblePlayCandidate(
                        "button",
                        "播放",
                        "",
                        true,
                        true,
                        false,
                        0.70),
                "low confidence play node is not auto-selected");

        check(MediaGoalUiPolicy.looksLikeNumericOrdinalTarget("10"),
                "numeric ordinal-like target detected");
        check(!MediaGoalUiPolicy.looksLikeNumericOrdinalTarget("鄧紫棋"),
                "semantic label is not numeric target");

        System.out.println(
                "MediaGoalUiPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
