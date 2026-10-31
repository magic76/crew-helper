package com.crewpocket.helper;

import java.util.Locale;

/** One bounded nudge when a clearly named app-open voice request ended with no tool. */
final class LiveAppOpenRecoveryPolicy {
    private LiveAppOpenRecoveryPolicy() {}

    static String requestedPackage(String rawText) {
        String text = rawText == null ? "" :
                rawText.toLowerCase(Locale.ROOT).trim();
        if (!"APP:OPEN".equals(GoalIntentKey.derive(rawText))) return "";
        if (text.contains("google maps") || text.contains("google map")
                || text.contains("google 地圖") || text.contains("google 地图")
                || text.contains("地圖") || text.contains("地图")) {
            return "com.google.android.apps.maps";
        }
        if (text.contains("apple music") || text.contains("蘋果音樂")) {
            return "com.apple.android.music";
        }
        if (text.contains("chrome")) return "com.android.chrome";
        if (text.contains("youtube")) return "com.google.android.youtube";
        return "";
    }

    static boolean shouldRecover(String goalIntent,
            String finalizedUserText, String foregroundPackage,
            String interactionStatus, boolean freshInput,
            int toolCallsForIntent, boolean alreadyNudged) {
        return shouldRecover(goalIntent, finalizedUserText, foregroundPackage,
                interactionStatus, freshInput, toolCallsForIntent,
                alreadyNudged, false);
    }

    /**
     * Live Extended Thinking sometimes never sends IDLE. A completed spoken
     * model turn with no tool is a bounded fallback signal for a *standalone*
     * named-app launch, not a general-purpose task/interaction completion.
     */
    static boolean shouldRecover(String goalIntent,
            String finalizedUserText, String foregroundPackage,
            String interactionStatus, boolean freshInput,
            int toolCallsForIntent, boolean alreadyNudged,
            boolean completedSpokenNoToolTurn) {
        if (alreadyNudged || freshInput || toolCallsForIntent != 0
                || !"APP:OPEN".equals(goalIntent)
                || !GoalIntentKey.isStandaloneAppOpen(finalizedUserText)
                || (!"IDLE".equalsIgnoreCase(
                        interactionStatus == null ? "" : interactionStatus.trim())
                    && !completedSpokenNoToolTurn)) {
            return false;
        }
        String target = requestedPackage(finalizedUserText);
        return !target.isEmpty() && !target.equals(foregroundPackage);
    }
}
