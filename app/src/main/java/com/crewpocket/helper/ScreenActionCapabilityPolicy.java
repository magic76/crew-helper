package com.crewpocket.helper;

import java.io.IOException;
import java.util.Locale;

/**
 * Fail closed only for operations that actually require the physical
 * Accessibility bridge. Speech, diagnosis, notes and app guidance still work.
 */
final class ScreenActionCapabilityPolicy {
    static final String UNAVAILABLE = "CAPABILITY_UNAVAILABLE";

    private ScreenActionCapabilityPolicy() {}

    static boolean requiresAccessibilityRequest(String name, String semanticAction) {
        if (!"phone_action".equals(name)) {
            return requiresAccessibilityTool(name);
        }
        String action = semanticAction == null
                ? "" : semanticAction.trim().toUpperCase(Locale.ROOT);
        switch (action) {
            case "OPEN_APP":
            case "SEARCH":
            case "COMMIT_SEARCH":
            case "TAP":
            case "TYPE":
            case "SCROLL":
            case "BACK":
            case "HOME":
            case "TAKE_PHOTO":
                return true;
            default:
                // Let the semantic resolver return the proper schema error.
                return false;
        }
    }

    static boolean requiresAccessibilityTool(String name) {
        if (name == null) return false;
        switch (name) {
            case "launch_app":
            case "inspect_ui":
            case "tap_element":
            case "tap_screen":
            case "swipe_screen":
            case "type_text":
            case "search_current_app":
            case "commit_search":
            case "send_text":
            case "press_key":
            case "list_apps":
            case "take_photo":
            case "wait_then_action":
            case "start_screen_monitor":
            case "start_conversation_loop":
            case "continue_conversation_loop":
            case "get_selected_region":
                return true;
            default:
                return false;
        }
    }

    static String launchTransportError(boolean accessibilityRunning, IOException error) {
        return accessibilityRunning
                ? LaunchFailureCodePolicy.transportFailure(error)
                : UNAVAILABLE;
    }
}
