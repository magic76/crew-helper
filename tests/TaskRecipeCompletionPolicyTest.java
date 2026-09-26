package com.crewpocket.helper;

public final class TaskRecipeCompletionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "MEDIA:PLAY",
                        "DONE",
                        "MEDIA_PLAY_CONTROL_VERIFIED",
                        true,
                        false,
                        "com.apple.android.music",
                        "Play"),
                "tap success without playback cannot complete media recipe");

        check(TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "MEDIA:PLAY",
                        "DONE",
                        "MEDIA_PLAYBACK_BECAME_ACTIVE",
                        true,
                        true,
                        "com.apple.android.music",
                        "Pause"),
                "actual playback can complete media recipe");

        check(!TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "NAVIGATION:START",
                        "DONE",
                        "MAPS_START_NAVIGATION_SCREEN_CHANGED",
                        true,
                        false,
                        "com.google.android.apps.maps",
                        "Start Directions"),
                "screen change after Start is not enough");

        check(TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "NAVIGATION:START",
                        "EVIDENCE_AVAILABLE",
                        "AUTO_AFTER_ACTION",
                        true,
                        false,
                        "com.google.android.apps.maps",
                        "Exit navigation Re-center"),
                "real active-navigation UI can close recipe");

        check(!TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "SEARCH:RESULT",
                        "ANSWER_READY",
                        "SEARCH_RESULT_SCREEN_INSPECTED",
                        true,
                        false,
                        "com.google.android.apps.maps",
                        "Search result"),
                "search stays non-terminal until query-result affinity exists");

        check(!TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "APP:OPEN",
                        "DONE",
                        "TASK_RECIPE_COMPLETED",
                        true,
                        false,
                        "com.android.settings",
                        "Settings"),
                "generic recipe-completed evidence cannot prove whole goal");

        check(TaskRecipeCompletionPolicy.isGoalTerminalVerified(
                        "APP:OPEN",
                        "DONE",
                        "APP_LAUNCHED",
                        true,
                        false,
                        "com.android.settings",
                        "Settings"),
                "specific verified terminal evidence remains usable");

        System.out.println(
                "TaskRecipeCompletionPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
