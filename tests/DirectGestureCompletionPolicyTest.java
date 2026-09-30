package com.crewpocket.helper;

public final class DirectGestureCompletionPolicyTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑動", "right", true),
                "plain right swipe is one-shot");
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "幫我往右滑一下", "right", true),
                "polite right swipe stays one-shot");
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "swipe right", "right", true),
                "plain English swipe is one-shot");

        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑動然後打開設定", "right", true),
                "compound gesture goal must remain active");
        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑兩次", "right", true),
                "repeat-count swipe must remain active");
        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往左滑動", "right", true),
                "direction mismatch must not complete");
        check(!DirectGestureCompletionPolicy.shouldFinish(
                        "往右滑動", "right", false),
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
