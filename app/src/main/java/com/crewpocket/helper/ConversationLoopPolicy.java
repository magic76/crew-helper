package com.crewpocket.helper;

import java.util.Locale;

/** Pure intent/stop policy for the persistent conversation recipe. */
final class ConversationLoopPolicy {
    private ConversationLoopPolicy() {}

    static boolean isExplicitStartIntent(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty()) return false;
        boolean conversation =
                text.contains("聊天")
                        || text.contains("對談")
                        || text.contains("对谈")
                        || text.contains("對話")
                        || text.contains("对话")
                        || text.contains("持續回")
                        || text.contains("持续回")
                        || text.contains("一直回")
                        || text.contains("自動回")
                        || text.contains("自动回")
                        || text.matches(".*\b(chat|conversation|reply)\b.*");
        boolean delegation =
                text.contains("幫我")
                        || text.contains("帮我")
                        || text.contains("代我")
                        || text.contains("替我")
                        || text.contains("跟")
                        || text.contains("和")
                        || text.contains("with")
                        || text.contains("for me");
        return conversation && delegation;
    }

    static boolean mentionsRecipient(String rawText, String recipient) {
        String text = normalize(rawText);
        String target = normalize(recipient);
        return !target.isEmpty() && text.contains(target);
    }

    static boolean isStopIntent(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty()) return false;
        return text.matches(".*(停止|停掉|結束|结束|不要再|先停|別回了|别回了|不用回了).*(聊天|對話|对话|對談|对谈|回覆|回复|代聊|自動回|自动回).*")
                || text.matches(".*(停止聊天|結束聊天|结束聊天|停止對話|停止对话|別再回|别再回).*")
                || text.matches(".*\b(stop|end|cancel)\b.*\b(chat|conversation|replies|replying)\b.*");
    }

    private static String normalize(String raw) {
        return raw == null
                ? ""
                : raw.toLowerCase(Locale.ROOT)
                        .replaceAll("[\s，,。！？!「」『』\"'：:；;（）()]", "")
                        .trim();
    }
}
