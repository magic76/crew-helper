package com.crewpocket.helper;

/**
 * Presentation timings and labels for the floating bubble's Action Chip.
 *
 * The orb owns persistent state. Text is intentionally transient unless the
 * same action has taken long enough that silence could look like a stalled task.
 */
final class BubbleActionChipPolicy {
    static final long TRANSIENT_MS = 1_600L;
    static final long LONG_ACTION_DELAY_MS = 3_000L;
    static final long DONE_MS = 1_000L;
    static final long ERROR_MS = 2_600L;
    static final int HISTORY_LIMIT = 3;

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

    static boolean stillSameAction(
            String expectedProgressKey,
            String latestRawStatus) {
        String expected = expectedProgressKey == null
                ? ""
                : expectedProgressKey;
        return !expected.isEmpty()
                && expected.equals(
                        BubbleTaskPhasePolicy.progressKey(latestRawStatus));
    }
}
