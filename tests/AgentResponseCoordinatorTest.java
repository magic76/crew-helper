package com.crewpocket.helper;

public final class AgentResponseCoordinatorTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        AgentTaskRecord task = new AgentTaskRecord("agent_test");
        task.awaitingModel = true;

        check(AgentResponseCoordinator.markDirectiveSendFailure(
                        task, "internal directive failed"),
                "send failure transitions active task");
        check(!task.awaitingModel,
                "send failure clears awaitingModel");
        check("internal directive failed".equals(task.status),
                "send failure records stage");

        System.out.println("PASS AgentResponseCoordinatorTest: " + assertions + " checks");
    }
}
