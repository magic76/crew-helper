package com.crewpocket.helper;

/** Supervisor is advisory only, never a new source of execution authority. */
public final class DualEngineSupervisorPolicyTest {
    private static int n;
    private static void check(boolean v, String reason) {
        n++; if (!v) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        check(DualEngineSupervisorPolicy.completionRequirement("APP:OPEN")
                .contains("foreground"), "app open verified by foreground");
        check(DualEngineSupervisorPolicy.completionRequirement("MEDIA:PLAY")
                .contains("playback"), "media requires actual playback");
        check(DualEngineSupervisorPolicy.completionRequirement("NAVIGATION:START")
                .contains("navigation"), "navigation has domain gate");
        check(DualEngineSupervisorPolicy.safeFailureCode("search_needs_query")
                .equals("SEARCH_NEEDS_QUERY"), "enum normalized");
        check(DualEngineSupervisorPolicy.safeFailureCode("private user text")
                .isEmpty(), "raw errors never leak");
        check(DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", false,
                false, false, false, 0, "SEARCH_NEEDS_QUERY"),
                "safe public playback failure can initiate single repair");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", true,
                false, false, false, 0, "SEARCH_NEEDS_QUERY"),
                "successful tool never triggers repair");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", false, "phone_action", false,
                false, false, false, 0, "SEARCH_NEEDS_QUERY"),
                "private task cannot be sent");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", false,
                false, false, false, 1, "SEARCH_NEEDS_QUERY"),
                "one retry maximum");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", false,
                true, false, false, 0, "SEARCH_NEEDS_QUERY"),
                "user choice must not be bypassed");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", false,
                false, true, false, 0, "SEARCH_NEEDS_QUERY"),
                "Runtime block is authoritative");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", false,
                false, false, true, 0, "SEARCH_NEEDS_QUERY"),
                "cancelled task cannot replan");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "NAVIGATION:START", true, "phone_action", false,
                false, false, false, 0, "SEARCH_NEEDS_QUERY"),
                "address/navigation never sent");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "send_text", false,
                false, false, false, 0, "SEARCH_NEEDS_QUERY"),
                "message tool excluded");
        check(!DualEngineSupervisorPolicy.shouldRequestRepair(
                "MEDIA:PLAY", true, "phone_action", false,
                false, false, false, 0, "secret diagnostic string"),
                "unknown code not forwarded");
        check(DualEngineSupervisorPolicy.isTerminalState("DONE"),
                "terminal done");
        check(!DualEngineSupervisorPolicy.isTerminalState("EVIDENCE_AVAILABLE"),
                "step evidence is not goal completion");
        System.out.println("PASS DualEngineSupervisorPolicyTest: " + n);
    }
}
