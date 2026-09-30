package com.crewpocket.helper;

public final class BubbleActionChipPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(BubbleActionChipPolicy.PHASE_DEBOUNCE_MS == 300L,
                "phase debounce moved out of legacy Morph policy");
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

        check(BubbleActionChipPolicy.kind(
                        "正在搜尋林家花園",
                        BubbleTaskPhasePolicy.Phase.ACTING)
                        == BubbleActionChipPolicy.Kind.SEARCH,
                "search action kind");
        check(BubbleActionChipPolicy.kind(
                        "正在往右滑",
                        BubbleTaskPhasePolicy.Phase.ACTING)
                        == BubbleActionChipPolicy.Kind.SWIPE,
                "swipe action kind");
        check(BubbleActionChipPolicy.kind(
                        "等待頁面載入",
                        BubbleTaskPhasePolicy.Phase.WAITING)
                        == BubbleActionChipPolicy.Kind.WAIT,
                "wait action kind");
        check(BubbleActionChipPolicy.kind(
                        "",
                        BubbleTaskPhasePolicy.Phase.THINKING)
                        == BubbleActionChipPolicy.Kind.THINK,
                "thinking phase kind");

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
