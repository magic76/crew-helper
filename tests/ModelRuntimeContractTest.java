package com.crewpocket.helper;

public final class ModelRuntimeContractTest {
    private static int checks;

    public static void main(String[] args) {
        actionSuccessIsNotGoalDone();
        explicitDoneFinishes();
        answerReadyAnswers();
        backgroundWaitWaits();
        inspectRequirementIsPreserved();
        blockedPolicyStops();
        recoverableFailureUsesAlternative();
        delegatedChatNamesRequiredTool();
        System.out.println(
                "ModelRuntimeContractTest passed " + checks + " checks");
    }

    private static void actionSuccessIsNotGoalDone() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "EVIDENCE_AVAILABLE", "", "DONE", "", "EVALUATE_GOAL");
        expect(ModelRuntimeContract.GOAL_IN_PROGRESS, g.state);
        expect(ModelRuntimeContract.NEXT_CONTINUE, g.next);
        expect("VERIFIED", ModelRuntimeContract.actionState("DONE"));
    }

    private static void explicitDoneFinishes() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "DONE", "NONE", "DONE", "", "EVALUATE_GOAL");
        expect(ModelRuntimeContract.GOAL_DONE, g.state);
        expect(ModelRuntimeContract.NEXT_FINISH, g.next);
    }

    private static void answerReadyAnswers() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "ANSWER_READY", "ANSWER_IF_SUFFICIENT", "DONE", "",
                "EVALUATE_GOAL");
        expect(ModelRuntimeContract.GOAL_ANSWER_READY, g.state);
        expect(ModelRuntimeContract.NEXT_ANSWER, g.next);
    }

    private static void backgroundWaitWaits() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "WAITING_BACKGROUND", "", "WAIT", "", "WAIT_FOR_RUNTIME");
        expect(ModelRuntimeContract.GOAL_WAITING_RUNTIME, g.state);
        expect(ModelRuntimeContract.NEXT_WAIT_RUNTIME, g.next);
    }

    private static void inspectRequirementIsPreserved() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "IN_PROGRESS", "inspect_ui once", "WAIT",
                "OBSERVE_REQUIRED", "OBSERVE");
        expect(ModelRuntimeContract.GOAL_IN_PROGRESS, g.state);
        expect(ModelRuntimeContract.NEXT_OBSERVE, g.next);
        expect("inspect_ui", g.requiredTool);
    }

    private static void blockedPolicyStops() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "", "", "FAILED", "POLICY_BLOCKED", "STOP");
        expect(ModelRuntimeContract.GOAL_BLOCKED, g.state);
        expect(ModelRuntimeContract.NEXT_STOP, g.next);
    }

    private static void recoverableFailureUsesAlternative() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "IN_PROGRESS", "", "FAILED", "TARGET_NOT_FOUND",
                "TRY_DIFFERENT_METHOD");
        expect(ModelRuntimeContract.GOAL_IN_PROGRESS, g.state);
        expect(ModelRuntimeContract.NEXT_TRY_ALTERNATIVE, g.next);
    }

    private static void delegatedChatNamesRequiredTool() {
        ModelRuntimeContract.Goal g = ModelRuntimeContract.deriveGoal(
                "IN_PROGRESS", "", "FAILED", "DELEGATED_SESSION_REQUIRED",
                "START_CONVERSATION_LOOP");
        expect(ModelRuntimeContract.NEXT_CONTINUE, g.next);
        expect("start_conversation_loop", g.requiredTool);
    }

    private static void expect(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "expected=" + expected + " actual=" + actual);
        }
    }
}
