package com.crewpocket.helper;

public final class AgentLedgerShadowTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        ShadowAgentRuntime runtime = new ShadowAgentRuntime();

        runtime.onUserIntent(7L, "goal_7", "task_1", true);
        check(runtime.state().phase == AgentState.Phase.READY, "user intent -> READY");
        check(runtime.state().generation == 7L, "generation projected");

        runtime.onToolQueued("call_1", "phone_action", 7L);
        runtime.onActionStarted(
                "call_1", 7L, "goal_7", "task_1",
                "phone_action", "tap_screen", "screen_A",
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE);
        check(runtime.state().phase == AgentState.Phase.EXECUTING, "action -> EXECUTING");

        runtime.onActionExecuted("call_1");
        check(runtime.state().phase == AgentState.Phase.WAITING_FOR_UI, "executed -> WAITING_FOR_UI");

        runtime.onScreenObserved("screen_B", "stable_B", "com.example");
        check(runtime.state().phase == AgentState.Phase.VERIFYING, "screen -> VERIFYING");

        runtime.onToolResult("call_1", "phone_action", true, "OK", "screen_B");
        check(runtime.state().phase == AgentState.Phase.WAITING_FOR_MODEL, "commit -> WAITING_FOR_MODEL");
        check(runtime.state().lastFailureCode.isEmpty(), "success clears failure");

        runtime.onUserIntent(8L, "goal_8", "task_2", true);
        runtime.onToolQueued("call_2", "phone_action", 8L);
        runtime.onActionStarted(
                "call_2", 8L, "goal_8", "task_2",
                "phone_action", "tap_screen", "screen_X",
                ActionTransaction.ExpectedEffect.SCREEN_CHANGE);
        runtime.onActionExecuted("call_2");
        runtime.onToolResult("call_2", "phone_action", false, "NO_PROGRESS", "screen_X");
        check(runtime.state().phase == AgentState.Phase.WAITING_FOR_MODEL, "step failure remains recoverable");
        check("NO_PROGRESS".equals(runtime.state().lastFailureCode), "failure code projected");

        runtime.onInterrupted("USER_CORRECTION");
        check(runtime.state().phase == AgentState.Phase.CANCELLED, "interrupt -> CANCELLED");

        runtime.onUserIntent(9L, "goal_9", "task_3", true);
        check(runtime.state().phase == AgentState.Phase.READY, "new intent revives terminal state");

        String dump = runtime.dumpRecent(8);
        check(dump.contains("USER_INTENT_ACCEPTED"), "ledger dumps events");
        check(dump.contains("ACTION_FAILED"), "ledger retains failure");

        System.out.println("PASS: " + assertions + " checks");
    }
}

