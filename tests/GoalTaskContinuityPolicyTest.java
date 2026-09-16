package com.crewpocket.helper;

import java.util.Arrays;
import java.util.List;

public final class GoalTaskContinuityPolicyTest {
    public static void main(String[] args) {
        expect(false, GoalTaskContinuityPolicy.compatible(
                tools("tap_screen", "press_key"), tools("search_notes")),
                "device to notebook must split");
        expect(true, GoalTaskContinuityPolicy.compatible(
                tools("tap_screen", "inspect_ui"), tools("tap_screen")),
                "device follow-up should stay linked");
        expect(true, GoalTaskContinuityPolicy.compatible(
                tools("search_notes"), tools("list_notes")),
                "notebook tasks may stay linked");
        expect(false, GoalTaskContinuityPolicy.compatible(
                tools("read_web_page"), tools("tap_screen")),
                "web to device must split");
        expect(false, GoalTaskContinuityPolicy.compatible(
                tools("unknown_tool"), tools("tap_screen")),
                "unknown evidence must not link");
        expect(false, GoalTaskContinuityPolicy.compatible(
                tools("tap_screen", "search_notes"), tools("tap_screen")),
                "mixed-domain previous task must not link");
        System.out.println("GoalTaskContinuityPolicyTest passed");
    }

    private static List<String> tools(String... values) {
        return Arrays.asList(values);
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
