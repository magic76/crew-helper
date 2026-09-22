package com.crewpocket.helper;

import java.util.Locale;

/** Pure ranking policy for model-facing screen items. */
final class ScreenItemPriorityPolicy {
    private ScreenItemPriorityPolicy() {}

    static int score(
            String role,
            String label,
            String semanticHint,
            String can) {
        String r = normalize(role);
        String l = normalize(label);
        String h = normalize(semanticHint);
        String c = normalize(can);
        String all = r + " " + l + " " + h + " " + c;

        int score = 0;

        if (containsAny(all,
                "directions", "navigation", "route",
                "路線", "路线", "導航", "导航")) {
            score += 120;
        }
        if (containsAny(all,
                "start", "begin", "go",
                "開始", "开始", "出發", "出发")) {
            score += 110;
        }
        if (containsAny(all,
                "send", "submit", "confirm", "next",
                "發送", "发送", "送出", "確認", "确认", "下一步")) {
            score += 100;
        }
        if (containsAny(r, "button")
                || containsAny(c, "tap", "click", "press", "activate")) {
            score += 70;
        }
        if (containsAny(h,
                "navigation", "directions", "start",
                "send", "confirm", "next")) {
            score += 60;
        }
        if (containsAny(r,
                "text", "label", "image", "metadata")) {
            score += 10;
        }
        if (!l.isEmpty()) score += 5;
        return score;
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
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
