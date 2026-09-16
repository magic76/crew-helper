package com.crewpocket.helper;

/** Pure task-id suffix matching for privacy-bounded Inspector traces. */
final class InspectorTraceScope {
    private InspectorTraceScope() {}

    static boolean matches(String inspectorTaskId, String traceTaskId) {
        String expected = sanitize(inspectorTaskId);
        String actual = sanitize(traceTaskId);
        if (expected.isEmpty()) return true;
        if (actual.isEmpty()) return false;
        return actual.equals(expected)
                || actual.endsWith(expected)
                || expected.endsWith(actual);
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "";
        String clean = value.replaceAll("[^A-Za-z0-9_]", "");
        if (clean.length() <= 12) return clean;
        return clean.substring(clean.length() - 12);
    }
}
