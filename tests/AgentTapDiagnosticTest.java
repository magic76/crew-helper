package com.crewpocket.helper;

public final class AgentTapDiagnosticTest {
    public static void main(String[] args) {
        assertEquals("開車", AgentTapDiagnostic.targetFromRuntimeSignature(
                "tap_screen", "TAP",
                "tap_screen:{\"label\":\"開車\",\"semantic_action\":\"TAP\",\"semantic_target\":\"route_mode:DRIVING\"}"));
        assertEquals("route_mode:DRIVING", AgentTapDiagnostic.semanticTargetFromRuntimeSignature(
                "tap_screen", "TAP",
                "tap_screen:{\"label\":\"開車\",\"semantic_action\":\"TAP\",\"semantic_target\":\"route_mode:DRIVING\"}"));
        assertEquals("結束", AgentTapDiagnostic.targetFromRuntimeSignature(
                "tap_screen", "TAP",
                "tap_screen:{\"label\":\"結束\",\"semantic_action\":\"TAP\"}"));

        assertEquals("", AgentTapDiagnostic.targetFromRuntimeSignature(
                "type_text", "TYPE",
                "type_text:{\"text\":\"private message\"}"));
        assertEquals("", AgentTapDiagnostic.targetFromRuntimeSignature(
                "search_current_app", "SEARCH",
                "search_current_app:{\"text\":\"private query\"}"));
        assertEquals("", AgentTapDiagnostic.targetFromRuntimeSignature(
                "tap_screen", "TAP",
                "tap_screen:{\"label\":\"https://example.com/private\"}"));
        assertEquals("", AgentTapDiagnostic.targetFromRuntimeSignature(
                "tap_screen", "TAP",
                "tap_screen:{\"label\":\"0912345678\"}"));

        System.out.println("AgentTapDiagnosticTest passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=[" + expected + "] actual=[" + actual + "]");
        }
    }
}
