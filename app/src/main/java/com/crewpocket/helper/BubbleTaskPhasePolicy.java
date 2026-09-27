package com.crewpocket.helper;

import java.util.Locale;

/** Pure, user-facing visual phase classifier for the floating Crew bubble. */
final class BubbleTaskPhasePolicy {
    enum Phase {
        NONE,
        THINKING,
        ACTING,
        WAITING,
        STUCK
    }

    private BubbleTaskPhasePolicy() {}

    static Phase classify(String rawStatus, boolean activeTask) {
        if (!activeTask) return Phase.NONE;

        String raw = rawStatus == null ? "" : rawStatus.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        if (containsAny(lower,
                "等待外部",
                "等待檢查",
                "等待畫面",
                "等待頁面",
                "等待載入",
                "正在驗證上一個操作",
                "正在確認結果",
                "正在確認最終狀態",
                "wait_then_action",
                "waiting_background")) {
            return Phase.WAITING;
        }

        if (raw.contains("正在執行「")
                || containsAny(lower,
                        "runtime 連續執行",
                        "正在操作",
                        "正在搜尋",
                        "正在送出",
                        "正在開啟 app",
                        "正在看畫面",
                        "執行工具")) {
            return Phase.ACTING;
        }

        return Phase.THINKING;
    }

    static String progressKey(String rawStatus) {
        if (rawStatus == null) return "";
        return rawStatus.replaceAll("\\s+", " ").trim();
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
