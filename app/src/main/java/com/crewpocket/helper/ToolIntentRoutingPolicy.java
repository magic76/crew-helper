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
            String goalIntent,
            String toolName,
            String action,
            String finalizedUserText) {
        if (!"APP:OPEN".equals(goalIntent)
                || !"phone_action".equals(toolName)
                || "OPEN_APP".equalsIgnoreCase(
                        action == null ? "" : action.trim())) {
            return false;
        }
        // APP:OPEN also describes opening a document/link, so only enforce
        // this ordering for an explicit, recognizable application launch.
        String userText = finalizedUserText == null ? ""
                : finalizedUserText.trim().toLowerCase(
                        java.util.Locale.ROOT);
        boolean explicitOpen = userText.contains("打開")
                || userText.contains("打开")
                || userText.contains("開啟")
                || userText.contains("开启")
                || userText.contains("open");
        boolean namedApp = userText.contains("google maps")
                || userText.contains("google map")
                || userText.contains("google 地圖")
                || userText.contains("谷歌地圖")
                || userText.contains("谷歌地图")
                || userText.contains("地圖")
                || userText.contains("地图")
                || userText.contains("apple music")
                || userText.contains("蘋果音樂")
                || userText.contains("chrome")
                || userText.contains("youtube")
                || userText.contains("line app");
        return explicitOpen && namedApp;
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
