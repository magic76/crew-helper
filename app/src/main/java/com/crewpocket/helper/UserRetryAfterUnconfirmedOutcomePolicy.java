package com.crewpocket.helper;

import java.util.Locale;

/**
 * Detects a narrow class of human retries after a recent successful mutation
 * whose whole-goal outcome was never confirmed.
 *
 * Raw utterances are used only transiently for comparison and are never
 * persisted. The returned intent family is a bounded diagnostic token.
 */
final class UserRetryAfterUnconfirmedOutcomePolicy {
    static final String CATEGORY = "USER_RETRY_AFTER_UNCONFIRMED_OUTCOME";
    static final String CONDITION = "USER_RETRY_AFTER_UNCONFIRMED_OUTCOME";
    static final long RETRY_WINDOW_MS = 8_000L;

    static final class Decision {
        final boolean retry;
        final String intentFamily;

        Decision(boolean retry, String intentFamily) {
            this.retry = retry;
            this.intentFamily = safeFamily(intentFamily);
        }

        static Decision none() {
            return new Decision(false, "");
        }
    }

    private UserRetryAfterUnconfirmedOutcomePolicy() {}

    static Decision evaluate(
            String previousUserTurn,
            String newUserTurn,
            long nowMs,
            long lastSuccessfulMutationAtMs,
            int mutationActions,
            String lastTaskState) {
        if (mutationActions <= 0 || lastSuccessfulMutationAtMs <= 0L) {
            return Decision.none();
        }

        long age = nowMs - lastSuccessfulMutationAtMs;
        if (age < 0L || age > RETRY_WINDOW_MS) {
            return Decision.none();
        }

        if (isTerminalOrExternalWait(lastTaskState)) {
            return Decision.none();
        }

        String family = sharedIntentFamily(previousUserTurn, newUserTurn);
        if (family.isEmpty()) return Decision.none();
        return new Decision(true, family);
    }

    static String sharedIntentFamily(String first, String second) {
        String aFamily = controlFamily(first);
        String bFamily = controlFamily(second);
        if (!aFamily.isEmpty() && aFamily.equals(bFamily)) {
            return aFamily;
        }

        String a = normalizedComparable(first);
        String b = normalizedComparable(second);
        if (!a.isEmpty() && a.equals(b)) {
            return "EXACT_REPEAT";
        }
        return "";
    }

    static String controlFamily(String text) {
        String value = normalizedCommand(text);
        if (value.isEmpty()) return "";

        if (equalsAny(value,
                "暫停", "暂停", "暫停音樂", "暂停音乐",
                "暫停播放", "暂停播放", "pause", "pausemusic",
                "pauseplayback")) {
            return "CONTROL:PAUSE";
        }
        if (equalsAny(value,
                "播放", "播放音樂", "播放音乐", "繼續播放", "继续播放",
                "play", "playmusic", "resume", "resumeplayback")) {
            return "CONTROL:PLAY";
        }
        if (equalsAny(value,
                "下一首", "下一曲", "下一個", "下一个",
                "next", "nexttrack", "skip", "skiptrack")) {
            return "CONTROL:NEXT";
        }
        if (equalsAny(value,
                "上一首", "上一曲", "上一個", "上一个",
                "previous", "previoustrack", "prev", "backtrack")) {
            return "CONTROL:PREVIOUS";
        }
        return "";
    }

    private static boolean isTerminalOrExternalWait(String taskState) {
        String state = taskState == null
                ? "" : taskState.trim().toUpperCase(Locale.ROOT);
        return "DONE".equals(state)
                || "ANSWER_READY".equals(state)
                || "BLOCKED".equals(state)
                || "WAITING_USER".equals(state)
                || "NEED_USER".equals(state)
                || "WAITING_BACKGROUND".equals(state);
    }

    private static String normalizedCommand(String text) {
        String value = normalizedComparable(text);
        if (value.isEmpty()) return "";

        value = stripPrefix(value,
                "請幫我", "请帮我", "幫我", "帮我", "麻煩", "麻烦", "請", "请",
                "please");
        value = stripSuffix(value,
                "一下", "一下吧", "吧", "please");
        return value;
    }

    private static String normalizedComparable(String text) {
        return text == null ? "" : text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()._-]+", "")
                .trim();
    }

    private static String stripPrefix(String value, String... prefixes) {
        String out = value;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                String p = normalizedComparable(prefix);
                if (!p.isEmpty() && out.startsWith(p) && out.length() > p.length()) {
                    out = out.substring(p.length());
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    private static String stripSuffix(String value, String... suffixes) {
        String out = value;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : suffixes) {
                String s = normalizedComparable(suffix);
                if (!s.isEmpty() && out.endsWith(s) && out.length() > s.length()) {
                    out = out.substring(0, out.length() - s.length());
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(normalizedComparable(candidate))) return true;
        }
        return false;
    }

    private static String safeFamily(String value) {
        String family = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return family.matches("[A-Z0-9:_-]{1,64}") ? family : "";
    }
}
