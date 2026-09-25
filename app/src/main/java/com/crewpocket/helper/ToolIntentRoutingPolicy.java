package com.crewpocket.helper;

/**
 * Pure model-tool intent correction before Runtime preflight.
 *
 * Runtime may repair a weak model's reversible tool choice when the terminal
 * goal makes the intended semantic action unambiguous. This must happen before
 * execution so Inspector, verification and model-facing contracts all describe
 * the same action.
 */
final class ToolIntentRoutingPolicy {
    private ToolIntentRoutingPolicy() {}

    static boolean shouldRemapTypeToSearch(
            String runtimeName,
            boolean explicitSearchIntent,
            String goalIntent,
            boolean hasText) {
        if (!hasText) return false;
        if (!"type_text".equals(runtimeName)) return false;
        if (explicitSearchIntent) return true;
        return "MEDIA:PLAY".equals(goalIntent);
    }
}
