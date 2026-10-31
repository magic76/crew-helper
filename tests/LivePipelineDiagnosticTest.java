package com.crewpocket.helper;

public final class LivePipelineDiagnosticTest {
    public static void main(String[] args) {
        LivePipelineDiagnostic.startSession(100L);
        LivePipelineDiagnostic.onIntent(7L, "APP:OPEN");
        LivePipelineDiagnostic.onFrame("IN_PROGRESS", true, true, false);
        LivePipelineDiagnostic.onFrame("IDLE", false, true, false);
        String noTool = LivePipelineDiagnostic.report();
        check(noTool.contains("goal=APP:OPEN"), "goal sanitized");
        check(noTool.contains("IDLE-without-tool=1"), "no-tool completion recorded");
        check(noTool.contains("intermediate utterances=1"),
                "intermediate utterance is not tool completion");
        LivePipelineDiagnostic.onToolFrame("phone_action", "TAP");
        LivePipelineDiagnostic.onExecution();
        LivePipelineDiagnostic.onToolResponse();
        String withTool = LivePipelineDiagnostic.report();
        check(withTool.contains("toolCall frames=1"), "tool frame logged");
        check(withTool.contains("Last requested: phone_action/TAP"),
                "tool action sanitized");
        LivePipelineDiagnostic.onToolFrame("phone_action", "SECRET_TOKEN");
        check(LivePipelineDiagnostic.report().contains("phone_action/OTHER"),
                "unrecognized action not leaked");
        LivePipelineDiagnostic.startSession(200L);
        LivePipelineDiagnostic.onSupervisorIntent(true);
        LivePipelineDiagnostic.onSupervisorIntent(false);
        LivePipelineDiagnostic.onSupervisorRepairRequested();
        check(LivePipelineDiagnostic.report().contains(
                "Supervisor: intents=2 fast-path=1 recovery-requested=1"),
                "supervisor counters distinguish fast path and failure recovery");
        check(LivePipelineDiagnostic.report().contains("toolCall frames=0"),
                "fresh session clears historical counters");
        System.out.println("PASS LivePipelineDiagnosticTest");
    }
    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }
}
