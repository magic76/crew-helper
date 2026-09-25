package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MediaGoalUiPolicyTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JSONObject play = new JSONObject()
                .put("role", "button")
                .put("label", "播放")
                .put("can", "tap");
        JSONObject artist = new JSONObject()
                .put("role", "button")
                .put("label", "鄧紫棋")
                .put("can", "tap");
        JSONObject other = new JSONObject()
                .put("role", "button")
                .put("label", "更多")
                .put("can", "tap");

        check(MediaGoalUiPolicy.scoreItem(
                        play, "MEDIA:PLAY", "播放鄧紫棋")
                        > MediaGoalUiPolicy.scoreItem(
                                other, "MEDIA:PLAY", "播放鄧紫棋"),
                "play control receives highest media-goal priority");
        check(MediaGoalUiPolicy.scoreItem(
                        artist, "MEDIA:PLAY", "播放鄧紫棋")
                        > MediaGoalUiPolicy.scoreItem(
                                other, "MEDIA:PLAY", "播放鄧紫棋"),
                "goal-mentioned media result is prioritized");

        JSONObject screen = new JSONObject()
                .put("success", true)
                .put("elements", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "button")
                                .put("label", "播放")
                                .put("clickable", true)
                                .put("enabled", true)
                                .put("confidence", 0.92)));
        check("播放".equals(MediaGoalUiPolicy.uniquePlayTarget(screen)),
                "unique confident play control may recover empty TAP");

        JSONObject unresolvedEntityScreen = new JSONObject()
                .put("success", true)
                .put("elements", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "button")
                                .put("label", "鄧紫棋")
                                .put("clickable", true)
                                .put("enabled", true)
                                .put("confidence", 0.92))
                        .put(new JSONObject()
                                .put("role", "button")
                                .put("label", "播放")
                                .put("clickable", true)
                                .put("enabled", true)
                                .put("confidence", 0.92)));
        check("鄧紫棋".equals(
                        MediaGoalUiPolicy.uniqueGoalEntityTarget(
                                unresolvedEntityScreen,
                                "播放鄧紫棋")),
                "unique goal-mentioned media entity may recover empty TAP first");
        check(MediaGoalUiPolicy.uniquePlayTarget(
                        unresolvedEntityScreen,
                        "播放鄧紫棋").isEmpty(),
                "play recovery waits while a goal entity is still actionable");

        screen.getJSONArray("elements").put(new JSONObject()
                .put("role", "button")
                .put("label", "播放")
                .put("clickable", true)
                .put("enabled", true)
                .put("confidence", 0.92));
        check(MediaGoalUiPolicy.uniquePlayTarget(screen).isEmpty(),
                "multiple play controls are not auto-selected");

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
