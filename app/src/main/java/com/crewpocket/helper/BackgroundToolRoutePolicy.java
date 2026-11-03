package com.crewpocket.helper;

/**
 * One-turn routing decision for explicit virtual-display requests.
 *
 * A background preference in Settings is NOT permission to redirect ordinary
 * foreground requests. Only the finalized current user turn grants scope.
 * A model-selected foreground OPEN_APP can be remapped to the same low-risk
 * background OPEN_APP, while every other foreground tool fails closed.
 */
final class BackgroundToolRoutePolicy {
    enum Decision {
        FOREGROUND, BACKGROUND_TOOL, REDIRECT_BACKGROUND_OPEN,
        BLOCK_BACKGROUND_DISABLED, BLOCK_BACKGROUND_ROUTE_MISMATCH
    }

    private BackgroundToolRoutePolicy() {}

    static Decision decide(String finalizedUserText, boolean enabled,
                           String tool, String action, String target) {
        if (!BackgroundPhonePolicy.explicitlyBackground(finalizedUserText))
            return Decision.FOREGROUND;
        if ("background_phone".equals(tool))
            return Decision.BACKGROUND_TOOL; // runtime separately checks enabled
        if (!isForegroundOperation(tool))
            return Decision.FOREGROUND; // read-only diagnostics remain usable
        if (!enabled)
            return Decision.BLOCK_BACKGROUND_DISABLED;
        if ("phone_action".equals(tool) && "OPEN_APP".equalsIgnoreCase(action)
                && target != null && !target.trim().isEmpty())
            return Decision.REDIRECT_BACKGROUND_OPEN;
        return Decision.BLOCK_BACKGROUND_ROUTE_MISMATCH;
    }

    static boolean isForegroundOperation(String tool) {
        if (tool == null) return false;
        switch (tool) {
            case "phone_action":
            case "inspect_ui":
            case "send_text":
            case "run_app_capability":
            case "wait_then_action":
            case "launch_app":
            case "tap_screen":
            case "tap_element":
            case "search_current_app":
            case "commit_search":
            case "type_text":
            case "take_screenshot":
            case "press_back":
            case "press_home":
            case "scroll_screen":
                return true;
            default: return false;
        }
    }

    static String expectedAction(String tool, String action) {
        if ("inspect_ui".equals(tool) || "take_screenshot".equals(tool))
            return "INSPECT";
        if (action == null) return "UNKNOWN";
        switch (action.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "OPEN_APP": return "OPEN_APP";
            case "TAP": return "TAP";
            case "SCROLL": return "SCROLL";
            case "TYPE": return "TYPE";
            default: return "UNKNOWN";
        }
    }

    static String safeReceivedTool(String tool) {
        return isForegroundOperation(tool) || "background_phone".equals(tool)
                ? tool : "OTHER";
    }
    static String safeReceivedAction(String action) {
        if (action == null) return "NONE";
        String a = action.trim().toUpperCase(java.util.Locale.ROOT);
        switch (a) {
            case "OPEN_APP": case "TAP": case "SCROLL": case "TYPE":
            case "INSPECT": case "VISION": case "STATUS": case "HANDOFF":
            case "CLOSE": case "VISUAL_TAP":
                return a;
            default: return "NONE";
        }
    }
}
