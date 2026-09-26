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
        int mediaResultScore = MediaGoalUiPolicy.scoreItem(
                "button", "泡沫", "track", "tap",
                "MEDIA:PLAY", "播放鄧紫棋");
        int otherScore = MediaGoalUiPolicy.scoreItem(
                "button", "更多", "", "tap",
                "MEDIA:PLAY", "播放鄧紫棋");
        int sceneScore = MediaGoalUiPolicy.scoreItem(
                "text", "目前結果", "", "",
                "MEDIA:PLAY", "播放鄧紫棋");

        check(playScore > artistScore,
                "Play/Resume controls are first for media projection");
        check(artistScore > mediaResultScore,
                "goal-mentioned media entity is second");
        check(mediaResultScore > otherScore,
                "other actionable media result is third");
        check(otherScore > sceneScore,
                "ordinary actionable stays above scene context");

        int completedArtistScore = MediaGoalUiPolicy.scoreItem(
                "button", "鄧紫棋", "", "tap",
                "MEDIA:PLAY", "播放鄧紫棋", "鄧紫棋");
        check(playScore > completedArtistScore,
                "completed media entity is demoted after activation");
        check(MediaGoalUiPolicy.sameSemanticLabel(
                        " 鄧紫棋 ", "鄧紫棋"),
                "completed media target comparison is normalized");

        check(MediaGoalUiPolicy.isGoalEntityLabel(
                        "鄧紫棋", "播放鄧紫棋"),
                "artist label is recognized from goal text");
        check(!MediaGoalUiPolicy.isGoalEntityLabel(
                        "播放", "播放鄧紫棋"),
                "play control is not treated as unresolved media entity");

        check(MediaGoalUiPolicy.isActionableMediaResult(
                        "button", "泡沫", "track", "tap"),
                "semantic media result is recognized without app selector");
        check(!MediaGoalUiPolicy.isActionableMediaResult(
                        "button", "更多", "", "tap"),
                "generic action is not promoted as media result");

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
        check(MediaGoalUiPolicy.shouldRejectNumericTarget(
                        "10", false),
                "numeric target is rejected outside element-reference mode");
        check(!MediaGoalUiPolicy.shouldRejectNumericTarget(
                        "10", true),
                "numeric target remains valid during explicit element-reference mode");
        check(!MediaGoalUiPolicy.looksLikeNumericOrdinalTarget("鄧紫棋"),
                "semantic label is not numeric target");

        int genericMediaGoalScore = MediaGoalUiPolicy.scoreItem(
                "button", "播放", "", "tap",
                "SEARCH:RESULT", "播放鄧紫棋");
        int genericBaseScore = ScreenItemPriorityPolicy.score(
                "button", "播放", "", "tap");
        check(genericMediaGoalScore == genericBaseScore,
                "non-media goals preserve generic screen ranking");

        System.out.println(
                "MediaGoalUiPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
