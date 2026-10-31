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

    /**
     * An APP:OPEN instruction cannot authorize a TAP/SEARCH/PLAY mutation.
     * With non-blocking Live calls, a late media action may otherwise be
     * attributed to the new app-open generation. Ask the model for OPEN_APP
     * instead; do not invent an application target in the Runtime.
     */
    static boolean requiresOpenAppAction(
            String goalIntent, String toolName, String action) {
        return "APP:OPEN".equals(goalIntent)
                && "phone_action".equals(toolName)
                && !"OPEN_APP".equalsIgnoreCase(
                        action == null ? "" : action.trim());
    }

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
