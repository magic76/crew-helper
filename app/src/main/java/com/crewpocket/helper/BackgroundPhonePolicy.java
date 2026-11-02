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
        return v.contains("背景") || v.contains("虛擬螢幕") ||
                v.contains("虚拟屏") || v.contains("virtual screen") ||
                v.contains("virtual display") || v.contains("background display") ||
                v.contains("in the background");
    }

    static boolean explicitlyHandoff(String text) {
        String v = normalize(text);
        return v.contains("前景") || v.contains("主螢幕") ||
                v.contains("主屏幕") || v.contains("交給我") ||
                v.contains("我來接手") || v.contains("給我看") ||
                v.contains("顯示給我") || v.contains("切到手機") ||
                v.contains("show me") || v.contains("hand over") ||
                v.contains("foreground");
    }

    static boolean explicitlyClose(String text) {
        String v = normalize(text);
        return v.contains("關掉背景") || v.contains("關閉背景") ||
                v.contains("停止背景") || v.contains("結束背景") ||
                v.contains("關掉虛擬") || v.contains("關閉虛擬") ||
                v.contains("close background") || v.contains("stop background");
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

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
