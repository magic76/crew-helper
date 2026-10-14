package com.crewpocket.helper;

import java.util.List;
import java.util.Locale;

/**
 * Narrow repair for a Live model returning PLAY instead of phone_action(TAP).
 * Requires the user's actual MEDIA:PLAY goal, the live Apple Music screen,
 * and an unambiguous current Accessibility target. Does not execute a tap:
 * standard Runtime preflight, locator and playback verification still apply.
 */
final class MediaPlayFlowRecoveryPolicy {
    private MediaPlayFlowRecoveryPolicy() {}

    static boolean isUnsupportedPlayAction(
            String action, String goalIntent, String packageName) {
        String value = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        return "MEDIA:PLAY".equals(goalIntent)
                && MediaPlaybackCompletionPolicy.isDefaultTrustedPackage(packageName)
                && ("PLAY".equals(value)
                    || "PLAY_MUSIC".equals(value)
                    || "START_PLAYBACK".equals(value)
                    || "RESUME_PLAYBACK".equals(value));
    }

    static String safeNextTarget(
            String goalText,
            String completedTarget,
            List<MediaTapRecoveryPolicy.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        String next = MediaTapRecoveryPolicy.recoverTarget(
                goalText, completedTarget, candidates);
        if (next.isEmpty()) return "";

        // The goal explicitly included searching. Never activate an unrelated
        // persistent mini-player merely because it exposes a Play button.
        // Require matching current-screen artist/song evidence as well.
        if (isSearchAndPlayGoal(goalText)
                && MediaPlaybackCompletionPolicy.isPlayControl(next)) {
            boolean artistVisible = false;
            for (MediaTapRecoveryPolicy.Candidate item : candidates) {
                if (item == null || item.sensitive || !item.enabled) continue;
                if (MediaGoalUiPolicy.isGoalEntityLabel(item.label, goalText)) {
                    artistVisible = true;
                    break;
                }
            }
            if (!artistVisible) return "";
        }
        return next;
    }

    static boolean isSearchAndPlayGoal(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return (value.contains("搜尋") || value.contains("搜索")
                    || value.contains("尋找") || value.contains("找")
                    || value.contains("search") || value.contains("find"))
                && (value.contains("播放") || value.contains("播歌")
                    || value.contains("play"));
    }
}
