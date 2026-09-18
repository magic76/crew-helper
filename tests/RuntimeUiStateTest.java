package com.crewpocket.helper;

public final class RuntimeUiStateTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        RuntimeUiState sent = RuntimeUiState.success("已送出", "");
        check(sent.phase == RuntimeUiState.Phase.DONE, "success phase");
        check(sent.isSuccess(), "success severity");

        RuntimeUiState waiting =
                RuntimeUiState.waitingUser("需要你選擇", "第一個或第二個");
        check(waiting.needsAttention(), "waiting needs attention");
        check(waiting.recommendedAutoHideMs() == 6200L, "waiting stays longer");

        RuntimeUiState connecting =
                RuntimeUiState.fromLiveStatus("正在連線…", true);
        check(connecting.phase == RuntimeUiState.Phase.CONNECTING, "live connecting phase");

        RuntimeUiState failed =
                RuntimeUiState.fromLiveStatus("連線失敗", true);
        check(failed.isError(), "live error is structured");

        RuntimeUiState legacy =
                RuntimeUiState.fromLegacy("已框選", "直接說你想怎麼處理");
        check(legacy.phase == RuntimeUiState.Phase.CONTEXT_READY,
                "legacy compatibility maps context state");

        System.out.println("PASS RuntimeUiStateTest: " + assertions + " checks");
    }
}
