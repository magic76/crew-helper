package com.crewpocket.helper;

import java.util.Locale;

/**
 * Privacy and task-selection boundary for the optional second Gemini engine.
 * This policy never grants phone, notebook, messaging or payment authority.
 */
final class DualEnginePlanningPolicy {
    private DualEnginePlanningPolicy() {}

    /** Only public, bounded media requests may leave the primary Live socket. */
    static String safePublicMediaPrompt(String text, String goal) {
        if (!"MEDIA:PLAY".equals(goal) || text == null) return "";
        String value = text.trim();
        if (value.length() < 5 || value.length() > 260) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        if (!contains(lower, "播放", "播歌", "play", "放音樂", "放音乐"))
            return "";
        // Never forward potentially identifying search text or private app
        // content; repair prompts reuse exactly the same privacy boundary.
        if (contains(lower, "http", "www.", "@", "line", "whatsapp", "訊息",
                "消息", "message", "密碼", "密码", "otp", "驗證", "验证",
                "付款", "支付", "購買", "购买", "刪除", "删除", "帳號",
                "账号", "email", "電話", "电话", "地址", "address",
                "私人", "private", "記事本", "记事本", "筆記", "笔记",
                "存檔", "儲存", "保存", "save", "note")) return "";
        if (lower.matches(".*[0-9]{4,}.*")) return "";
        return value;
    }

    /** Proactive thinking is limited to multi-step public media requests. */
    static String eligiblePrompt(String text, String goal) {
        String safe = safePublicMediaPrompt(text, goal);
        if (safe.isEmpty()) return "";
        String lower = safe.toLowerCase(Locale.ROOT);
        return contains(lower, "找", "搜尋", "搜索", "search", "find", "查")
                ? safe : "";
    }

    static boolean eligible(String text, String goal) {
        return !eligiblePrompt(text, goal).isEmpty();
    }

    static boolean safeAdvice(String step) {
        if (step == null || step.trim().isEmpty() || step.length() > 160) return false;
        String v = step.toLowerCase(Locale.ROOT);
        return !contains(v, "send_text", "message", "otp", "password",
                "credential", "payment", "delete", "purchase",
                "密碼", "密码", "驗證碼", "验证码", "發送", "发送",
                "付款", "支付", "刪除", "删除", "購買", "购买",
                "bypass", "ignore runtime", "skip verification",
                "繞過", "绕过", "跳過驗證", "跳过验证");
    }

    private static boolean contains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
