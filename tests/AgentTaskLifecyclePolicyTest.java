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
            int mutationActions) {
        return AgentTaskLifecyclePolicy.evaluateStep(
                nowMs,
                startedAtMs,
                steps,
                maxSteps,
                name,
                signature,
                lastSignature,
                toolCount,
                mutationActions);
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
                        "inspect_ui", "same", "same", 4, 0).allowed,
                "repeated observation remains allowed");
        check(!step(1_000L, 0L, 0, 20,
                        "tap_screen", "same", "same", 0, 0).allowed,
                "repeated mutation is blocked");
        check(!step(AgentTaskLifecyclePolicy.TASK_TIMEOUT_MS + 1L, 0L, 0, 20,
                        "inspect_ui", "inspect:1", "", 0, 0).allowed,
                "task timeout blocks next step");
        check(!step(1_000L, 0L, 20, 20,
                        "inspect_ui", "inspect:2", "", 0, 0).allowed,
                "step budget blocks next step");
        check(!step(1_000L, 0L, 0, 20,
                        "take_screenshot", "shot:4", "shot:3",
                        AgentTaskLifecyclePolicy.MAX_SCREENSHOTS, 0).allowed,
                "screenshot cap is preserved");
        check(!step(1_000L, 0L, 0, 20,
                        "tap_screen", "tap:16", "tap:15", 0,
                        AgentTaskLifecyclePolicy.MAX_MUTATION_ACTIONS).allowed,
                "mutation cap is preserved");
        check(AgentTaskLifecyclePolicy.maxRunsForTool("advance_deck")
                        == AgentTaskLifecyclePolicy.DECK_NAV_MAX_RUNS,
                "deck navigation keeps expanded run limit");
        check(AgentTaskLifecyclePolicy.maxRunsForTool("tap_screen")
                        == AgentTaskLifecyclePolicy.MAX_TOOL_RUNS,
                "normal tool run limit is preserved");

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

        System.out.println("PASS AgentTaskLifecyclePolicyTest: " + assertions + " checks");
    }
}
