package com.crewpocket.helper;

import java.util.Locale;

/**
 * Pure reliability policy for deciding when operational memory may learn.
 *
 * Task completion and memory learning are intentionally different boundaries:
 * a task may be good enough to finish without being strong enough to promote
 * reusable memory.
 */
final class RefinedMemoryEvidencePolicy {
    enum Verdict {
        NONE,
        INDEPENDENT_SUCCESS,
        SUPPORTING_SUCCESS,
        NEGATIVE
    }

    private static final double MIN_CORRECTION_CONFIDENCE = 0.72d;

    private RefinedMemoryEvidencePolicy() {}

    static Verdict normalTaskVerdict(
            String scope,
            String taskState,
            String completionEvidence,
            boolean verified,
            boolean mediaPlaybackActive) {
        return hasStrongTerminalEvidence(
                scope,
                taskState,
                completionEvidence,
                verified,
                mediaPlaybackActive)
                ? Verdict.INDEPENDENT_SUCCESS
                : Verdict.NONE;
    }

    static Verdict recipeReplayVerdict(
            boolean runtimeSuccess,
            String scope,
            String taskState,
            String completionEvidence,
            boolean verified,
            boolean mediaPlaybackActive) {
        if (!runtimeSuccess) return Verdict.NEGATIVE;
        return hasStrongTerminalEvidence(
                scope,
                taskState,
                completionEvidence,
                verified,
                mediaPlaybackActive)
                ? Verdict.SUPPORTING_SUCCESS
                : Verdict.NONE;
    }

    static boolean hasStrongTerminalEvidence(
            String scope,
            String taskState,
            String completionEvidence,
            boolean verified,
            boolean mediaPlaybackActive) {
        String intent = clean(scope).toUpperCase(Locale.ROOT);
        String state = clean(taskState).toUpperCase(Locale.ROOT);
        String evidence = clean(completionEvidence).toUpperCase(Locale.ROOT);

        boolean terminal =
                "DONE".equals(state)
                        || "ANSWER_READY".equals(state);
        if (!terminal) return false;

        if ("MEDIA:PLAY".equals(intent)) {
            if (!mediaPlaybackActive) return false;
            return "MEDIA_UI_PLAYING".equals(evidence)
                    || "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(evidence);
        }

        if ("NAVIGATION:START".equals(intent)) {
            return "MAPS_START_NAVIGATION_SCREEN_CHANGED".equals(evidence);
        }

        if ("SEARCH:RESULT".equals(intent)) {
            return "ANSWER_READY".equals(state)
                    && "SEARCH_RESULT_SCREEN_INSPECTED".equals(evidence);
        }

        if ("APP:OPEN".equals(intent)) {
            return verified
                    && ("APP_OPENED".equals(evidence)
                        || "APP_LAUNCHED".equals(evidence)
                        || "LAUNCH_APP_VERIFIED".equals(evidence));
        }

        if (intent.startsWith("MEDIA:")) {
            return verified
                    && evidence.startsWith("MEDIA_")
                    && !evidence.contains("CONTROL_EFFECT")
                    && !evidence.contains("CONTROL_VERIFIED");
        }

        return false;
    }

    /**
     * Only an explicit correction is negative memory evidence. Generic cancel,
     * stop, interruption, or a low-confidence ASR transcript is not.
     */
    static boolean looksLikeReliableCorrection(
            String text,
            double transcriptConfidence) {
        if (transcriptConfidence >= 0.0d
                && transcriptConfidence < MIN_CORRECTION_CONFIDENCE) {
            return false;
        }

        String value = normalize(text);
        if (value.isEmpty()) return false;
        return containsAny(
                value,
                "不是這首", "不是这首",
                "不是這個", "不是这个",
                "不是那個", "不是那个",
                "點錯", "点错",
                "按錯", "按错",
                "選錯", "选错",
                "錯了", "错了",
                "不對", "不对",
                "搞錯", "搞错",
                "你做錯", "你做错",
                "我是說", "我是说",
                "我說的是", "我说的是",
                "我要的是",
                "應該是", "应该是",
                "wrong",
                "notthat",
                "notthis",
                "imeant",
                "youclickedwrong",
                "youpickedwrong");
    }

    private static boolean containsAny(
            String value,
            String... terms) {
        for (String term : terms) {
            String needle = normalize(term);
            if (!needle.isEmpty() && value.contains(needle)) {
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
