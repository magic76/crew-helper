package com.crewpocket.helper;

public final class ScreenItemPriorityPolicyTest {
    public static void main(String[] args) {
        int directions = ScreenItemPriorityPolicy.score(
                "button", "路線", "navigation:DIRECTIONS", "tap");
        int start = ScreenItemPriorityPolicy.score(
                "button", "開始", "navigation:START", "tap");
        int place = ScreenItemPriorityPolicy.score(
                "text", "大皇宮", "", "");
        int rating = ScreenItemPriorityPolicy.score(
                "text", "4.6 顆星", "", "");

        expect(directions > place, "Directions must outrank place metadata");
        expect(start > place, "Start must outrank place metadata");
        expect(place >= rating, "Result title should not rank below plain rating text");

        int genericButton = ScreenItemPriorityPolicy.score(
                "button", "更多", "", "tap");
        int metadata = ScreenItemPriorityPolicy.score(
                "text", "營業時間", "", "");
        expect(genericButton > metadata, "Clickable controls should outrank metadata");

        expect(
                ScreenItemPriorityPolicy.isActionable(
                        "button", "開始", "navigation:START", "tap"),
                "start button should be actionable");
        expect(
                ScreenItemPriorityPolicy.isSceneContext(
                        "text", "大皇宮", "", ""),
                "place title should survive as scene context");
        expect(
                ScreenItemPriorityPolicy.isSceneContext(
                        "text", "4.6 顆星", "", ""),
                "rating should remain available as scene context");
        expect(
                !ScreenItemPriorityPolicy.isSceneContext(
                        "button", "路線", "navigation:DIRECTIONS", "tap"),
                "action controls must not consume scene quota");

        System.out.println("ScreenItemPriorityPolicyTest passed");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
