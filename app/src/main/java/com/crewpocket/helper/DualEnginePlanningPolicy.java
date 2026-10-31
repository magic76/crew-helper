package com.crewpocket.helper;

import java.util.Locale;

/**
 * Privacy and task-selection boundary for the optional second Gemini engine.
 * This policy never grants phone, notebook, messaging or payment authority.
 */
final class DualEnginePlanningPolicy {
    private DualEnginePlanningPolicy() {}

    static String eligiblePrompt(String text, String goal) {
        if (!"MEDIA:PLAY".equals(goal) || text == null) return "";
        String value = text.trim();
        if (value.length() < 8 || value.length() > 260) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        boolean search = contains(lower, "找", "搜尋", "搜索", "search", "find", "查");
        boolean play = contains(lower, "播放", "播歌", "play", "放音樂", "放音乐");
        if (!search || !play) return "";
        // Do not forward personal chat, identifiers, account instructions, or
        // text containing numeric secrets. A narrow public-media pilot only.
        if (contains(lower, "http", "www.", "@", "line", "whatsapp", "訊息",
                "消息", "message", "密碼", "密码", "otp", "驗證", "验证",
                "付款", "支付", "購買", "购买", "刪除", "删除", "帳號",
                "账号", "email", "電話", "电话", "地址", "address",
                "私人", "private", "記事本", "记事本", "筆記", "笔记",
                "存檔", "儲存", "保存", "save", "note")) return "";
        if (lower.matches(".*[0-9]{4,}.*")) return "";
        return value;
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
