package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

public final class GoalTaskContinuityPolicyTest {
    public static void main(String[] args) throws Exception {
        expect(false, GoalTaskContinuityPolicy.compatible(
                task("tap_screen", "press_key"), task("search_notes")),
                "device to notebook must split");
        expect(true, GoalTaskContinuityPolicy.compatible(
                task("tap_screen", "inspect_ui"), task("tap_screen")),
                "device follow-up should stay linked");
        expect(true, GoalTaskContinuityPolicy.compatible(
                task("search_notes"), task("list_notes")),
                "notebook tasks may stay linked");
        expect(false, GoalTaskContinuityPolicy.compatible(
                task("read_web_page"), task("tap_screen")),
                "web to device must split");
        expect(false, GoalTaskContinuityPolicy.compatible(
                task("unknown_tool"), task("tap_screen")),
                "unknown evidence must not link");
        expect(false, GoalTaskContinuityPolicy.compatible(
                task("tap_screen", "search_notes"), task("tap_screen")),
                "mixed-domain previous task must not link");
        System.out.println("GoalTaskContinuityPolicyTest passed");
    }

    private static JSONObject task(String... tools) throws Exception {
        JSONArray steps = new JSONArray();
        for (String tool : tools) steps.put(new JSONObject().put("tool", tool));
        return new JSONObject().put("steps", steps);
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
