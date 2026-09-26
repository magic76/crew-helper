package com.crewpocket.helper;

import java.util.Locale;

/** Pure goal-aware UI hints for MEDIA:PLAY without app-specific selectors. */
final class MediaGoalUiPolicy {
    private MediaGoalUiPolicy() {}

    static boolean isMediaPlayGoal(String goalIntent) {
        return "MEDIA:PLAY".equals(clean(goalIntent));
    }

    static int scoreItem(
            String role,
            String label,
            String semanticHint,
            String can,
            String goalIntent,
            String goalText) {
        return scoreItem(
                role, label, semanticHint, can,
                goalIntent, goalText, "");
    }

    static int scoreItem(
            String role,
            String label,
            String semanticHint,
            String can,
            String goalIntent,
            String goalText,
            String completedTarget) {
        int score = ScreenItemPriorityPolicy.score(
                role, label, semanticHint, can);
        if (!isMediaPlayGoal(goalIntent)) return score;

        boolean actionable =
                ScreenItemPriorityPolicy.isActionable(
                        role, label, semanticHint, can);
        String metadata = clean(label) + " "
                + clean(semanticHint) + " " + clean(can);

        // MEDIA:PLAY model projection is intentionally tiered:
        // Play/Resume > goal-mentioned artist/song > other media result
        // > ordinary actionable > scene context.
        if (MediaPlaybackCompletionPolicy.isPlayControl(metadata)
                || MediaPlaybackCompletionPolicy.isPlayControl(label)
                || MediaPlaybackCompletionPolicy.isPlayControl(semanticHint)) {
            return score + 1200;
        }

        if (isGoalEntityLabel(label, goalText)) {
            score += actionable ? 900 : 90;
            if (sameSemanticLabel(label, completedTarget)) {
                score -= 1000;
            }
            return score;
        }

        if (actionable && isActionableMediaResult(
                role, label, semanticHint, can)) {
            return score + 600;
        }

        if (actionable) {
            return score + 300;
        }
        return score;
    }

    static boolean isGoalEntityLabel(
            String label,
            String goalText) {
        String normalizedLabel = normalize(label);
        String normalizedGoal = normalize(goalText);
        return normalizedLabel.length() >= 2
                && !MediaPlaybackCompletionPolicy.isPlayControl(label)
                && !normalizedGoal.isEmpty()
                && normalizedGoal.contains(normalizedLabel);
    }

    static boolean isActionableMediaResult(
            String role,
            String label,
            String semanticHint,
            String can) {
        if (!ScreenItemPriorityPolicy.isActionable(
                role, label, semanticHint, can)) {
            return false;
        }
        String metadata =
                normalize(role) + " "
                        + normalize(label) + " "
                        + normalize(semanticHint) + " "
                        + normalize(can);
        return containsAny(
                metadata,
                "media", "music", "song", "track", "artist",
                "album", "playlist", "episode",
                "音樂", "音乐", "歌曲", "歌手", "藝人", "艺人",
                "專輯", "专辑", "播放清單", "播放列表");
    }

    static boolean isEligiblePlayCandidate(
            String role,
            String label,
            String semanticHint,
            boolean clickable,
            boolean enabled,
            boolean sensitive,
            double confidence) {
        if (sensitive || !enabled || confidence < 0.85) {
            return false;
        }
        boolean actionable = clickable
                || "button".equals(clean(role))
                || "icon_button".equals(clean(role));
        if (!actionable) return false;
        return MediaPlaybackCompletionPolicy.isPlayControl(label)
                || MediaPlaybackCompletionPolicy.isPlayControl(semanticHint)
                || MediaPlaybackCompletionPolicy.isPlayControl(
                        clean(label) + " " + clean(semanticHint));
    }

    static boolean sameSemanticLabel(
            String left,
            String right) {
        String a = normalize(left);
        String b = normalize(right);
        return !a.isEmpty() && a.equals(b);
    }

    static boolean looksLikeNumericOrdinalTarget(String target) {
        String value = clean(target);
        return !value.isEmpty() && value.matches("[0-9]{1,3}");
    }

    static boolean shouldRejectNumericTarget(
            String target,
            boolean elementReferenceActive) {
        return looksLikeNumericOrdinalTarget(target)
                && !elementReferenceActive;
    }

    private static boolean containsAny(
            String value,
            String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null
                    && !needle.isEmpty()
                    && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
