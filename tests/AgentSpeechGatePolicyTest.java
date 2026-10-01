package com.crewpocket.helper;

public final class AgentSpeechGatePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "WAITING_USER", "tap_screen",
                        false, false, 2),
                "WAITING_USER question must be audible");

        check(!AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "NEED_USER", "inspect_ui",
                        false, false, 1),
                "NEED_USER question must be audible");

        check(AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "IN_PROGRESS", "tap_screen",
                        false, false, 1),
                "in-progress mutation narration stays silent");

        check(AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "EVIDENCE_AVAILABLE", "inspect_ui",
                        false, false, 1),
                "step evidence after mutation stays silent");

        check(!AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "DONE", "tap_screen",
                        false, false, 1),
                "DONE may speak");

        check(!AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "ANSWER_READY", "inspect_ui",
                        false, false, 1),
                "ANSWER_READY may speak");

        check(!AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "BLOCKED", "tap_screen",
                        false, true, 1),
                "BLOCKED may explain the blocker");

        check(AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "WAITING_BACKGROUND", "wait",
                        false, false, 0),
                "background wait stays silent");

        check(AgentSpeechGatePolicy.shouldWithhold(
                        true, true, "IN_PROGRESS", "tap_screen",
                        true, false, 1),
                "required post-action verification suppresses speech");

        check(!AgentSpeechGatePolicy.shouldWithhold(
                        false, true, "IN_PROGRESS", "tap_screen",
                        false, false, 1),
                "no active task means no Agent speech gate");

        check(AgentSpeechGatePolicy.isUserInputBoundary(" waiting_user "),
                "state normalization handles waiting-user");
        check(!AgentSpeechGatePolicy.isUserInputBoundary("WAITING_BACKGROUND"),
                "background wait is not a user-input boundary");

        System.out.println(
                "PASS AgentSpeechGatePolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
