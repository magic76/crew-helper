package com.crewpocket.helper;

import java.util.Locale;

/** Pure intent/stop policy for the persistent conversation recipe. */
final class ConversationLoopPolicy {
    private ConversationLoopPolicy() {}

    static boolean hasDelegatedSendAuthority(
            boolean activeLoopCanSend,
            boolean latestTurnCanSend) {
        return activeLoopCanSend || latestTurnCanSend;
    }

    /**
     * Any fresh human turn owns the foreground over a retained conversation loop.
     * Runtime does not re-interpret conversation intent here. If the user wants
     * ongoing delegated chat again, Gemini can call start_conversation_loop for
     * the same fresh turn after the old lease is released.
     */
    static boolean shouldYieldToUserTurn(String rawText) {
        return !normalize(rawText).isEmpty();
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
