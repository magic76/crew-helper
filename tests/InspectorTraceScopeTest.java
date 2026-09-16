package com.crewpocket.helper;

public final class InspectorTraceScopeTest {
    public static void main(String[] args) {
        expect(true, InspectorTraceScope.matches("…36787250", "task_123436787250"),
                "safe Inspector suffix should match full trace id");
        expect(false, InspectorTraceScope.matches("…36787250", "task_99991234"),
                "different task must not match");
        expect(true, InspectorTraceScope.matches("", "task_99991234"),
                "empty Inspector scope keeps generic report behavior");
        expect(false, InspectorTraceScope.matches("…36787250", ""),
                "missing trace id must not match a scoped report");
        System.out.println("InspectorTraceScopeTest passed");
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
