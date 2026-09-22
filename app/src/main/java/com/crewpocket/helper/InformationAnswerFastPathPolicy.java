package com.crewpocket.helper;

import java.util.Locale;

/**
 * Pure policy for surfacing an "answer now if sufficient" hint after an
 * informational lookup has produced a fresh visual observation.
 *
 * The fast path is goal-aware: search + inspect is NOT enough when the user's
 * goal still contains an executable continuation such as navigate/open/send.
 */
final class InformationAnswerFastPathPolicy {
    private InformationAnswerFastPathPolicy() {}

    static boolean shouldOffer(
            String toolName,
            boolean succeeded,
            int searchAttempts,
            int committedSearches,
            boolean blocked,
            boolean waitingUser,
            String goal,
            String rootGoal) {
        if (!succeeded || blocked || waitingUser) return false;
        if (!"inspect_ui".equals(toolName)
                && !"take_screenshot".equals(toolName)) {
            return false;
        }
        if (searchAttempts <= 0 && committedSearches <= 0) return false;
        return !hasExecutableContinuation(goal)
                && !hasExecutableContinuation(rootGoal);
    }

    // Compatibility for old pure-policy callers/tests. No goal means the
    // caller has no evidence of an executable continuation.
    static boolean shouldOffer(
            String toolName,
            boolean succeeded,
            int searchAttempts,
            int committedSearches,
            boolean blocked,
            boolean waitingUser) {
        return shouldOffer(
                toolName,
                succeeded,
                searchAttempts,
                committedSearches,
                blocked,
                waitingUser,
                "",
                "");
    }

    static boolean hasExecutableContinuation(String rawGoal) {
        String goal = normalize(rawGoal);
        if (goal.isEmpty()) return false;

        // Strong phone-action intents. False negatives are more dangerous here
        // than false positives: disabling the fast path only means Gemini keeps
        // evaluating the original goal.
        String[] signals = new String[] {
                "導航", "导航", "導航過去", "导航过去", "帶我去", "带我去",
                "路線", "路线", "開始導航", "开始导航",
                "打開", "打开", "開啟", "开启",
                "點擊", "点击", "按下", "按 ", "選擇", "选择",
                "傳送", "发送", "發送", "送出", "傳訊息", "发消息",
                "輸入", "输入", "填入", "設定", "设置", "播放",
                "navigate", "navigation", "directions", "route me",
                "start navigation", "open ", "tap ", "click ",
                "send ", "message ", "type ", "enter ", "set ", "play "
        };
        for (String signal : signals) {
            if (goal.contains(signal)) return true;
        }
        return false;
    }

    static String instruction() {
        return "ANSWER FAST PATH: this is a fresh post-search screen for an "
                + "informational goal. If the screenshot already contains the "
                + "requested information, answer immediately. Do not use this "
                + "fast path when the original goal still requires a phone action.";
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ")
                        .trim();
    }
}
