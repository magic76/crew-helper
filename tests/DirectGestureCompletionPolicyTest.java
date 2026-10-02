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
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "往下滑", "forward", true),
                "reveal-below semantic direction completes a down-content request");
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "往上滑", "backward", true),
                "reveal-above semantic direction completes an up-content request");
        check(DirectGestureCompletionPolicy.shouldFinish(
                        "下一頁", "forward", true),
                "page-down semantic request is one-shot");

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
                        "往下滑", "backward", true),
                "semantic content direction mismatch must not complete");
        check(DirectGestureCompletionPolicy
                        .looksLikeStandaloneGestureIntent("往右滑動"),
                "plain directional swipe is a standalone gesture intent");
        check(DirectGestureCompletionPolicy
                        .looksLikeStandaloneGestureIntent("滑動螢幕"),
                "generic screen swipe is a standalone gesture intent");
        check(!DirectGestureCompletionPolicy
                        .looksLikeStandaloneGestureIntent("然後往右滑"),
                "explicit continuation stays attached to current goal");
        check(!DirectGestureCompletionPolicy
                        .looksLikeStandaloneGestureIntent("往右滑然後播放音樂"),
                "compound gesture plus semantic action is not standalone");

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
