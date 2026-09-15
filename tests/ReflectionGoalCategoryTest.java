package com.crewpocket.helper;

public final class ReflectionGoalCategoryTest {
    private static int checks;

    public static void main(String[] args) {
        check(ReflectionGoalCategory.UI_TARGET_RESOLUTION.equals(
                ReflectionGoalCategory.fromLegacyGoal("finding ui elements")),
                "legacy finding-ui candidate should migrate to UI_TARGET_RESOLUTION");
        check(ReflectionGoalCategory.ACTION_FAILURE_RECOVERY.equals(
                ReflectionGoalCategory.fromLegacyGoal("recovering from action failure")),
                "legacy action-recovery candidate should migrate");
        check(ReflectionGoalCategory.ROUTE_MODE_SELECTION.equals(
                ReflectionGoalCategory.fromLegacyGoal("selecting travel mode")),
                "route-mode wording should canonicalize");
        check(ReflectionGoalCategory.SEARCH_RESULT_SELECTION.equals(
                ReflectionGoalCategory.fromLegacyGoal("choosing search result")),
                "search-result wording should canonicalize");

        check(ReflectionGoalCategory.ROUTE_MODE_SELECTION.equals(
                ReflectionGoalCategory.classify(true, true, true, true, true, true)),
                "route mode should be the most specific category");
        check(ReflectionGoalCategory.UI_TARGET_RESOLUTION.equals(
                ReflectionGoalCategory.classify(false, true, true, true, true, true)),
                "UI target failure should outrank generic recovery");
        check(ReflectionGoalCategory.SEARCH_RESULT_SELECTION.equals(
                ReflectionGoalCategory.classify(false, false, true, true, true, true)),
                "search evidence should receive a stable category");
        check(ReflectionGoalCategory.SCREEN_STATE_RECOVERY.equals(
                ReflectionGoalCategory.classify(false, false, false, true, true, true)),
                "screen recovery should be recognized");
        check(ReflectionGoalCategory.POST_ACTION_VERIFICATION.equals(
                ReflectionGoalCategory.classify(false, false, false, false, true, true)),
                "verification failure should be recognized");
        check(ReflectionGoalCategory.ACTION_FAILURE_RECOVERY.equals(
                ReflectionGoalCategory.classify(false, false, false, false, false, true)),
                "generic failure should have a fallback category");
        check("".equals(ReflectionGoalCategory.classify(
                false, false, false, false, false, false)),
                "clean uncategorized success should stay free-text keyed");

        System.out.println("ReflectionGoalCategoryTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
