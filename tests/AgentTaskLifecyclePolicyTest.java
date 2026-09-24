package com.crewpocket.helper;

public final class AgentTaskLifecyclePolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    private static AgentTaskLifecyclePolicy.StepDecision step(
            long nowMs,
            long startedAtMs,
            int steps,
            int maxSteps,
            String name,
            String signature,
            String lastSignature,
            int toolCount,
            int mutationActions,
            int observationActions) {
        return AgentTaskLifecyclePolicy.evaluateStep(
                nowMs,
                startedAtMs,
                steps,
                maxSteps,
                name,
                toolCount,
                mutationActions,
                observationActions);
    }

    public static void main(String[] args) {
        check(AgentTaskLifecyclePolicy.isObservationTool("inspect_ui"),
                "inspect_ui is observation");
        check(AgentTaskLifecyclePolicy.isObservationTool("wait"),
                "wait is observation");
        check(!AgentTaskLifecyclePolicy.isObservationTool("tap_screen"),
                "tap_screen is not observation");
        check(AgentTaskLifecyclePolicy.isMutationTool("tap_screen"),
                "tap_screen is mutation");
        check(AgentTaskLifecyclePolicy.isMutationTool("send_text"),
                "send_text remains mutation");
        check(!AgentTaskLifecyclePolicy.isMutationTool("inspect_ui"),
                "inspect_ui is not mutation");

        check(step(1_000L, 0L, 0, 20,
                        "inspect_ui", "same", "same", 4, 0, 0).allowed,
                "repeated observation remains allowed");
        check(step(1_000L, 0L, 0, 20,
                        "swipe_screen", "same", "same", 1, 1, 0).allowed,
                "same low-risk mutation may repeat; dedupe belongs to RuntimeV2/screen evidence");
        check(!step(AgentTaskLifecyclePolicy.TASK_TIMEOUT_MS + 1L, 0L, 0, 20,
                        "inspect_ui", "inspect:1", "", 0, 0, 0).allowed,
                "task timeout blocks next step");
        check(step(1_000L, 0L, 20, 20,
                        "inspect_ui", "inspect:2", "", 0, 0, 1).allowed,
                "observation does not consume action step budget");
        check(!step(1_000L, 0L, 20, 20,
                        "tap_screen", "tap:budget", "", 0, 0, 1).allowed,
                "action step budget still blocks actions");
        check(step(1_000L, 0L, 3, 20,
                        "inspect_ui", "inspect:repeat", "",
                        6, 0, 3).allowed,
                "inspect_ui uses only the shared observation budget");
        check(!step(1_000L, 0L, 3, 20,
                        "wait", "wait:cap", "", 3, 0,
                        AgentTaskLifecyclePolicy.MAX_OBSERVATION_ACTIONS).allowed,
                "observation budget is separate and bounded");
        check(!step(1_000L, 0L, 0, 20,
                        "take_screenshot", "shot:4", "shot:3",
                        AgentTaskLifecyclePolicy.MAX_SCREENSHOTS, 0, 0).allowed,
                "screenshot cap is preserved");
        check(!step(1_000L, 0L, 0, 20,
                        "tap_screen", "tap:16", "tap:15", 0,
                        AgentTaskLifecyclePolicy.MAX_MUTATION_ACTIONS, 0).allowed,
                "mutation cap is preserved");
        check(AgentTaskLifecyclePolicy.isOneShotCompletionTool("create_note"),
                "create_note is terminal one-shot success");
        check(AgentTaskLifecyclePolicy.isOneShotCompletionTool("remember_app_guidance"),
                "remember_app_guidance is terminal one-shot success");
        check(AgentTaskLifecyclePolicy.isOneShotCompletionTool("cancel_schedule"),
                "cancel_schedule is terminal one-shot success");
        check(!AgentTaskLifecyclePolicy.isOneShotCompletionTool("start_conversation_loop"),
                "conversation start remains a continuing task");
        check(!AgentTaskLifecyclePolicy.isOneShotCompletionTool("search_current_app"),
                "phone search remains a continuing task");

        AgentTaskLifecyclePolicy.StabilityDecision observe =
                AgentTaskLifecyclePolicy.evaluateStability(
                        false, false, "tap_screen",
                        true, false,
                        "tap:1", "", "", "screen-a");
        check(observe.blocked
                        && "OBSERVE_REQUIRED_AFTER_FAILURE".equals(observe.code),
                "failed mutation requires observation barrier");

        AgentTaskLifecyclePolicy.StabilityDecision repeat =
                AgentTaskLifecyclePolicy.evaluateStability(
                        false, false, "tap_screen",
                        false, false,
                        "tap:1", "tap:1", "screen-a", "screen-a");
        check(repeat.blocked
                        && "REPEAT_FAILED_ACTION_ON_UNCHANGED_SCREEN".equals(repeat.code),
                "same failed mutation on unchanged screen is blocked");

        AgentTaskLifecyclePolicy.StabilityDecision changedScreen =
                AgentTaskLifecyclePolicy.evaluateStability(
                        false, false, "tap_screen",
                        false, false,
                        "tap:1", "tap:1", "screen-a", "screen-b");
        check(!changedScreen.blocked,
                "same mutation may proceed after screen changes");

        check(AgentTaskLifecyclePolicy.shouldStopAfterStabilityBlock(2),
                "two stability blocks stop loop");
        check(!AgentTaskLifecyclePolicy.shouldStopAfterStabilityBlock(1),
                "one stability block does not stop loop");
        check(AgentTaskLifecyclePolicy.shouldStopAfterMutationFailure(3),
                "three mutation failures stop loop");
        check(!AgentTaskLifecyclePolicy.shouldStopAfterMutationFailure(2),
                "two mutation failures do not stop loop");

        check(!AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "IN_PROGRESS", "tap_screen", false, false, 1),
                "mutation in progress cannot finish from model speech");
        check(!AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "EVIDENCE_AVAILABLE", "tap_screen", false, false, 1),
                "fresh after mutation is step evidence, not whole-task completion");
        check(!AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "EVIDENCE_AVAILABLE", "inspect_ui", false, false, 1),
                "inspect after mutation is still only step evidence");
        check(AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "EVIDENCE_AVAILABLE", "inspect_ui", false, false, 0),
                "read-only observation may close the goal");
        check(AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "DONE", "tap_screen", false, false, 1),
                "runtime DONE may close the goal");
        check(AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "ANSWER_READY", "inspect_ui", false, false, 2),
                "answer-ready state may close a mutation goal");
        check(AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "BLOCKED", "tap_screen", false, false, 1),
                "explicit blocked state may conclude the goal");
        check(!AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "DONE", "tap_screen", true, false, 1),
                "required post-action inspection still blocks completion");
        check(AgentTaskLifecyclePolicy.isObservationTool("take_screenshot"),
                "take_screenshot should be observation");
        check(AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        "EVIDENCE_AVAILABLE",
                        "take_screenshot",
                        false,
                        false,
                        0),
                "read-only screenshot task may finish from fresh evidence");

        System.out.println("PASS AgentTaskLifecyclePolicyTest: " + assertions + " checks");
    }
}
