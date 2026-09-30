package com.crewpocket.helper;

import org.json.JSONObject;

public final class DirectGestureCompletionPolicyTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JSONObject right = new JSONObject().put("direction", "right");
        JSONObject ok = new JSONObject().put("success", true);

        check(DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑動", right, ok),
                "plain right swipe is one-shot");
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "幫我往右滑一下", right, ok),
                "polite right swipe stays one-shot");
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "swipe right", right, ok),
                "plain English swipe is one-shot");

        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑動然後打開設定", right, ok),
                "compound gesture goal must remain active");
        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑兩次", right, ok),
                "repeat-count swipe must remain active");
        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往左滑動", right, ok),
                "direction mismatch must not complete");
        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑動", right, new JSONObject().put("success", false)),
                "failed swipe must not complete");

        System.out.println(
                "PASS DirectGestureCompletionPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
