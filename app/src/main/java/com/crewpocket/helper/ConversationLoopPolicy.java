package com.crewpocket.helper;

import java.util.Locale;

/** Pure intent/stop policy for the persistent conversation recipe. */
final class ConversationLoopPolicy {
    private ConversationLoopPolicy() {}

    static boolean isExplicitStartIntent(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty() || isStopIntent(rawText)) return false;
        boolean conversation =
                text.contains("聊天")
                        || text.contains("聊一下")
                        || text.contains("聊聊")
                        || text.contains("聊")
                        || text.contains("代聊")
                        || text.contains("對談")
                        || text.contains("对谈")
                        || text.contains("對話")
                        || text.contains("对话")
                        || text.contains("持續回")
                        || text.contains("持续回")
                        || text.contains("一直回")
                        || text.contains("自動回")
                        || text.contains("自动回")
                        || text.matches(".*\\b(chat|conversation|reply|talk|converse)\\b.*");
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

    static String extractRecipient(String rawText) {
        String raw = rawText == null ? "" : rawText.trim();
        if (raw.isEmpty() || !isExplicitStartIntent(raw)) return "";

        String[] patterns = new String[]{
                "(?:跟|和|與|与)\\s*([^，,。！？!：:\\n]{1,40}?)\\s*(?:聊天|聊一下|聊聊|聊|對談|对谈|對話|对话)",
                "(?:幫我|帮我|代我|替我)\\s*(?:跟|和|與|与)?\\s*([^，,。！？!：:\\n]{1,40}?)\\s*(?:持續|持续|一直|自動|自动)?\\s*(?:回覆|回复|回)",
                "\\b(?:chat|talk|converse)\\s+(?:with\\s+)?([^,.!?;:]{1,40})"
        };

        for (String pattern : patterns) {
            try {
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile(
                                pattern,
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(raw);
                if (!matcher.find()) continue;
                String recipient = cleanRecipient(matcher.group(1));
                if (isUsableRecipient(recipient)) return recipient;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String cleanRecipient(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ")
                .replaceAll("^(?:你自己|自己)\\s*", "")
                .replaceAll("\\s*(?:自己|持續|持续|一直|自動|自动)$", "")
                .trim();
    }

    private static boolean isUsableRecipient(String value) {
        String target = normalize(value);
        if (target.isEmpty()) return false;
        return !target.matches(
                "^(?:他|她|它|對方|对方|他們|他们|她們|她们|那個人|那个人|someone|them|him|her)$");
    }

    static boolean hasDelegatedSendAuthority(
            boolean activeLoopCanSend,
            boolean latestTurnCanSend) {
        return activeLoopCanSend || latestTurnCanSend;
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
                || text.matches(".*\\b(stop|end|cancel)\\b.*\\b(chat|conversation|replies|replying)\\b.*");
    }

    private static String normalize(String raw) {
        return raw == null
                ? ""
                : raw.toLowerCase(Locale.ROOT)
                        .replaceAll("[，,。！？!「」『』\"'：:；;（）()]", "")
                        .replaceAll("\\s+", " ")
                        .trim();
    }
}
