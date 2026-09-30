package com.crewpocket.helper;

import java.util.Locale;

/**
 * Pure presentation policy for the floating bubble Action Chip.
 * Runtime state remains authoritative; this class only maps user-facing labels
 * to small visual categories and timings.
 */
final class BubbleActionChipPolicy {
    static final long PHASE_DEBOUNCE_MS = 300L;
    static final long TRANSIENT_MS = 1_600L;
    static final long LONG_ACTION_DELAY_MS = 3_000L;
    static final long DONE_MS = 1_000L;
    static final long ERROR_MS = 2_600L;
    static final int HISTORY_LIMIT = 3;

    enum Kind {
        SEARCH,
        OPEN,
        TAP,
        SWIPE,
        TYPE,
        WAIT,
        THINK,
        MEDIA,
        GENERIC
    }

    private BubbleActionChipPolicy() {}

    static String label(
            String stage,
            BubbleTaskPhasePolicy.Phase phase) {
        String clean = stage == null
                ? ""
                : stage.replaceAll("\\s+", " ").trim();
        if (!clean.isEmpty()) return clean;

        BubbleTaskPhasePolicy.Phase resolved =
                phase == null
                        ? BubbleTaskPhasePolicy.Phase.NONE
                        : phase;
        if (resolved == BubbleTaskPhasePolicy.Phase.ACTING) {
            return "正在操作";
        }
        if (resolved == BubbleTaskPhasePolicy.Phase.WAITING) {
            return "等待畫面";
        }
        if (resolved == BubbleTaskPhasePolicy.Phase.STUCK) {
            return "需要你";
        }
        if (resolved == BubbleTaskPhasePolicy.Phase.THINKING) {
            return "正在思考";
        }
        return "";
    }

    static Kind kind(
            String label,
            BubbleTaskPhasePolicy.Phase phase) {
        String value = label == null
                ? ""
                : label.toLowerCase(Locale.ROOT);

        if (containsAny(value, "搜尋", "搜索", "search", "find")) {
            return Kind.SEARCH;
        }
        if (containsAny(value, "滑", "scroll", "swipe", "上一頁", "下一頁")) {
            return Kind.SWIPE;
        }
        if (containsAny(value, "輸入", "打字", "type", "input")) {
            return Kind.TYPE;
        }
        if (containsAny(value, "播放", "音樂", "media", "play")) {
            return Kind.MEDIA;
        }
        if (containsAny(value, "開啟", "打開", "open", "launch")) {
            return Kind.OPEN;
        }
        if (containsAny(value, "點擊", "按下", "tap", "click")) {
            return Kind.TAP;
        }
        if (containsAny(value, "等待", "確認", "驗證", "載入", "wait")) {
            return Kind.WAIT;
        }

        BubbleTaskPhasePolicy.Phase resolved =
                phase == null
                        ? BubbleTaskPhasePolicy.Phase.NONE
                        : phase;
        if (resolved == BubbleTaskPhasePolicy.Phase.WAITING) {
            return Kind.WAIT;
        }
        if (resolved == BubbleTaskPhasePolicy.Phase.THINKING) {
            return Kind.THINK;
        }
        return Kind.GENERIC;
    }

    static boolean stillSameAction(
            String expectedAction,
            String currentAction) {
        String expected = expectedAction == null
                ? ""
                : expectedAction.replaceAll("\\s+", " ").trim();
        String current = currentAction == null
                ? ""
                : currentAction.replaceAll("\\s+", " ").trim();
        return !expected.isEmpty() && expected.equals(current);
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty()
                    && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
