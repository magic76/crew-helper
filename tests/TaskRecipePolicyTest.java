package com.crewpocket.helper;

public final class TaskRecipePolicyTest {
    public static void main(String[] args) {
        matchesPoliteVariants();
        keepsDifferentQueriesDistinct();
        blocksUnsafeTapTargets();
        allowsLowRiskToolsOnly();
        rejectsSensitiveSearch();
        System.out.println("TaskRecipePolicyTest passed");
    }

    private static void matchesPoliteVariants() {
        check(TaskRecipePolicy.goalsMatch(
                "請幫我打開設定再進入藍牙",
                "幫我打開設定再進入藍牙一下"));
    }

    private static void keepsDifferentQueriesDistinct() {
        check(!TaskRecipePolicy.goalsMatch(
                "搜尋台北車站",
                "搜尋高雄車站"));
    }

    private static void blocksUnsafeTapTargets() {
        check(TaskRecipePolicy.isUnsafeTapTarget("確認付款"));
        check(TaskRecipePolicy.isUnsafeTapTarget("Delete account"));
        check(!TaskRecipePolicy.isUnsafeTapTarget("藍牙"));
    }

    private static void allowsLowRiskToolsOnly() {
        check(TaskRecipePolicy.isAllowedTool("launch_app"));
        check(TaskRecipePolicy.isAllowedTool("tap_screen"));
        check(!TaskRecipePolicy.isAllowedTool("type_text"));
        check(!TaskRecipePolicy.isAllowedTool("send_text"));
    }

    private static void rejectsSensitiveSearch() {
        check(!TaskRecipePolicy.canPersistSearch("OTP 123456"));
        check(TaskRecipePolicy.canPersistSearch("台北車站"));
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("check failed");
    }
}
