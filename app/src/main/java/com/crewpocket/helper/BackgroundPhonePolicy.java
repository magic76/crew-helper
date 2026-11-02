package com.crewpocket.helper;

import java.util.Locale;

/**
 * A separate opt-in authorization boundary for the experimental virtual screen.
 * A Live model tool is never itself permission to change screens.
 */
final class BackgroundPhonePolicy {
    private BackgroundPhonePolicy() {}

    static boolean explicitlyBackground(String text) {
        String v = normalize(text);
        // "背景音樂" means playback in the background, not a request to
        // control a separate display. Never infer permission from that alone.
        if (hasAny(v, "不要在背景", "別在背景", "不要用背景", "不要開背景",
                "不是背景", "不用背景", "do not use background",
                "don't use background", "not in the background"))
            return false;
        return hasAny(v, "在背景", "背景螢幕", "背景屏幕", "虛擬螢幕",
                "虚拟屏", "背景操作", "背景執行", "背景開啟",
                "背景打開", "背景點", "背景輸入", "背景搜尋",
                "virtual screen", "virtual display", "background display",
                "in the background", "background screen");
    }

    static boolean explicitlyHandoff(String text) {
        String v = normalize(text);
        if (hasAny(v, "不要切到前景", "不要到前景", "不要移到前景",
                "不要切到主螢幕", "不要切到主屏幕", "別移到前景",
                "不要交給我", "不要顯示給我", "不要給我看",
                "don't show", "do not hand over", "don't hand over",
                "do not move to foreground", "not foreground")) return false;
        // "給我看" alone is an inspection request, not consent to
        // move an Android task to the user's physical screen.
        return hasAny(v, "前景", "主螢幕", "主屏幕",
                "交給我", "我來接手", "切到手機",
                "hand over", "handoff", "foreground");
    }

    static boolean explicitlyClose(String text) {
        String v = normalize(text);
        if (hasAny(v, "不要關掉背景", "不要關閉背景", "不要停止背景",
                "別關背景", "別關閉背景", "不要關掉虛擬", "不要關閉虛擬",
                "don't close background", "do not close background",
                "don't stop background")) return false;
        return hasAny(v, "關掉背景", "關閉背景", "停止背景", "結束背景",
                "關掉虛擬", "關閉虛擬", "close background", "stop background");
    }

    static String deny(String action, String text, boolean enabled,
                       boolean active, boolean sameGeneration) {
        if (!enabled) return "BACKGROUND_LIVE_DISABLED";
        if (text == null || text.trim().isEmpty()) return "NO_FINALIZED_USER_REQUEST";
        boolean explicit = explicitlyBackground(text);
        if ("STATUS".equals(action)) return explicit ? "" : "BACKGROUND_REQUEST_REQUIRED";
        if ("OPEN_APP".equals(action)) {
            if (!explicit) return "BACKGROUND_REQUEST_REQUIRED";
            return active ? "BACKGROUND_ALREADY_ACTIVE" : "";
        }
        if (!active) return "BACKGROUND_NOT_ACTIVE";
        if ("HANDOFF".equals(action))
            return explicitlyHandoff(text) ? "" : "HANDOFF_REQUIRES_USER_REQUEST";
        if ("CLOSE".equals(action))
            return explicitlyClose(text) ? "" : "CLOSE_REQUIRES_USER_REQUEST";
        if ("INSPECT".equals(action) || "TAP".equals(action)
                || "SCROLL".equals(action) || "TYPE".equals(action))
            return explicit || sameGeneration ? "" : "BACKGROUND_REQUEST_REQUIRED";
        return "UNKNOWN_BACKGROUND_ACTION";
    }

    static boolean isSensitiveTarget(String text) {
        String value = normalize(text);
        return value.contains("付款") || value.contains("支付") ||
                value.contains("購買") || value.contains("下單") ||
                value.contains("結帳") || value.contains("刪除") ||
                value.contains("清除資料") || value.contains("發送") ||
                value.contains("送出") || value.contains("傳送") ||
                value.contains("提交") || value.contains("登入") ||
                value.contains("密碼") || value.contains("驗證") ||
                value.contains("轉帳") || value.contains("transfer money") ||
                value.contains("checkout") || value.contains("purchase") ||
                value.contains("delete") || value.contains("send message") ||
                value.contains("submit") || value.contains("password") ||
                value.contains("otp") || value.contains("payment");
    }

    static boolean blocksForegroundTool(String tool, String finalizedText) {
        return explicitlyBackground(finalizedText)
                && hasAny(normalize(tool), "phone_action", "send_text",
                        "run_app_capability", "wait_then_action");
    }

    private static boolean hasAny(String v, String... snippets) {
        for (String s : snippets) if (v.contains(s)) return true;
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
