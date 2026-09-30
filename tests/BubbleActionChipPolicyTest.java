package com.crewpocket.helper;

public final class BubbleActionChipPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(BubbleActionChipPolicy.TRANSIENT_MS >= 1_200L
                        && BubbleActionChipPolicy.TRANSIENT_MS <= 2_000L,
                "normal actions stay visible briefly");
        check(BubbleActionChipPolicy.LONG_ACTION_DELAY_MS
                        > BubbleActionChipPolicy.TRANSIENT_MS,
                "long-action persistence starts after transient chip");
        check(BubbleActionChipPolicy.HISTORY_LIMIT == 3,
                "bubble history stays intentionally small");

        check("正在搜尋林家花園".equals(
                        BubbleActionChipPolicy.label(
                                "  正在搜尋林家花園  ",
                                BubbleTaskPhasePolicy.Phase.ACTING)),
                "stage text is preferred");
        check("正在思考".equals(
                        BubbleActionChipPolicy.label(
                                "",
                                BubbleTaskPhasePolicy.Phase.THINKING)),
                "thinking fallback");
        check("等待畫面".equals(
                        BubbleActionChipPolicy.label(
                                null,
                                BubbleTaskPhasePolicy.Phase.WAITING)),
                "waiting fallback");
        check(BubbleActionChipPolicy.stillSameAction(
                        "正在操作",
                        "  正在操作  "),
                "same action stays pinned");
        check(!BubbleActionChipPolicy.stillSameAction(
                        "正在操作",
                        "正在確認結果"),
                "new progress releases old pin");

        System.out.println(
                "PASS BubbleActionChipPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
