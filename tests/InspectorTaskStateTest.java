package com.crewpocket.helper;

public final class InspectorTaskStateTest {
    public static void main(String[] args) {
        expect(InspectorTaskState.ACTIVE,
                InspectorTaskState.classify(true, false, "", "", ""),
                "active state");
        expect(InspectorTaskState.CANCELLED,
                InspectorTaskState.classify(false, true, "Agent 任務已停止", "新使用者指令取代舊任務", ""),
                "cancelled state");
        expect(InspectorTaskState.FAILED,
                InspectorTaskState.classify(false, false, "Agent 任務結束：失敗", "未完成", ""),
                "failed state");
        expect(InspectorTaskState.FAILED,
                InspectorTaskState.classify(false, false, "", "", "blocked by runtime"),
                "blocked state");
        expect(InspectorTaskState.COMPLETED,
                InspectorTaskState.classify(false, false, "Agent 任務結束", "", ""),
                "completed state");
        System.out.println("InspectorTaskStateTest passed");
    }

    private static void expect(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
