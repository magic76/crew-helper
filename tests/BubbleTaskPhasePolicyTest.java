package com.crewpocket.helper;

public final class BubbleTaskPhasePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(BubbleTaskPhasePolicy.classify("", false)
                        == BubbleTaskPhasePolicy.Phase.NONE,
                "inactive");
        check(BubbleTaskPhasePolicy.classify(
                        "Agent 任務開始：abc", true)
                        == BubbleTaskPhasePolicy.Phase.THINKING,
                "task start thinks");
        check(BubbleTaskPhasePolicy.classify(
                        "Agent 第 2 / 8 步：正在執行「tap_screen」", true)
                        == BubbleTaskPhasePolicy.Phase.ACTING,
                "tool execution acts");
        check(BubbleTaskPhasePolicy.classify(
                        "熟悉流程已執行，正在確認最終狀態", true)
                        == BubbleTaskPhasePolicy.Phase.WAITING,
                "verification waits");
        check(BubbleTaskPhasePolicy.classify(
                        "正在確認導航是否已開始", true)
                        == BubbleTaskPhasePolicy.Phase.WAITING,
                "Maps terminal verification waits");
        check(BubbleTaskPhasePolicy.classify(
                        "對話模式：偵測到聊天室變化，等待檢查新訊息", true)
                        == BubbleTaskPhasePolicy.Phase.WAITING,
                "screen wait");

        check(BubbleTaskPhasePolicy.progressKey("  a   b  ").equals("a b"),
                "progress key normalized");

        System.out.println(
                "BubbleTaskPhasePolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
