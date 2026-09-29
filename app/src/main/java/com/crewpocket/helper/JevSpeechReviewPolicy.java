package com.crewpocket.helper;

import java.util.Locale;

/**
 * Pure trigger/application policy for optional Jev speech arbitration.
 *
 * Jev never rewrites the transcript. It only decides whether Runtime may trust
 * the finalized ASR text directly or should verify critical entities first.
 */
final class JevSpeechReviewPolicy {
    private static final double REVIEW_CONFIDENCE = 0.88d;
    private static final double APPLY_CONFIDENCE = 0.55d;

    private JevSpeechReviewPolicy() {}

    static boolean shouldReview(
            String text,
            double transcriptConfidence,
            boolean keyConfigured) {
        if (!keyConfigured) return false;

        String value = clean(text);
        if (value.isEmpty()
                || VoiceCommandQualityPolicy.isAffirmative(value)
                || VoiceCommandQualityPolicy.isNegative(value)) {
            return false;
        }

        boolean lowConfidence =
                transcriptConfidence >= 0.0d
                        && transcriptConfidence < REVIEW_CONFIDENCE;
        boolean criticalEntity =
                !VoiceCommandQualityPolicy.criticalEntities(value).isEmpty();
        boolean actionable = containsAny(
                value,
                "搜尋", "搜索", "找", "導航", "导航", "帶我去", "带我去",
                "播放", "播", "打開", "打开", "開啟", "开启",
                "傳給", "传给", "發給", "发给", "傳訊息", "传信息",
                "send", "message", "search", "find", "navigate", "play", "open");
        boolean mixedScript =
                value.matches(".*[A-Za-z].*")
                        && value.matches(".*[\\p{IsHan}].*");

        return lowConfidence || criticalEntity || actionable || mixedScript;
    }

    static boolean shouldApply(String strategy, double confidence) {
        String value = clean(strategy).toUpperCase(Locale.ROOT);
        return confidence >= APPLY_CONFIDENCE
                && ("VERIFY_WITH_UI".equals(value)
                    || "ASK_USER".equals(value));
    }

    private static boolean containsAny(String value, String... needles) {
        String folded = clean(value).toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (folded.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
