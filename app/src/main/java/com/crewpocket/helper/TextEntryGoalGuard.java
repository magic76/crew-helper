package com.crewpocket.helper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ephemeral latest-turn constraint for user-requested text length.
 *
 * This stores only a numeric count/mode for at most two minutes. It never stores
 * the user's requested text, generated text, app content, or transcript.
 */
final class TextEntryGoalGuard {
    private static final long TTL_MS = 120_000L;
    private static final int MAX_EXPECTED = 5000;

    private static final Pattern ARABIC_COUNT = Pattern.compile(
            "(\\d{1,4})\\s*(?:個|个)?\\s*(?:字|字符|characters?|chars?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_COUNT = Pattern.compile(
            "([零〇一二兩两三四五六七八九十百千]{1,8})\\s*(?:個|个)?\\s*(?:字|字符)");

    enum Mode { NONE, APPROXIMATE, EXACT, MINIMUM }

    static final class Validation {
        final boolean constrained;
        final boolean allowed;
        final int expected;
        final int actual;
        final Mode mode;

        Validation(boolean constrained,
                   boolean allowed,
                   int expected,
                   int actual,
                   Mode mode) {
            this.constrained = constrained;
            this.allowed = allowed;
            this.expected = expected;
            this.actual = actual;
            this.mode = mode == null ? Mode.NONE : mode;
        }

        String errorCode() {
            if (allowed || !constrained) return "";
            return "TYPE_LENGTH_MISMATCH_EXPECTED_" + expected + "_ACTUAL_" + actual;
        }
    }

    private static int expectedCount;
    private static Mode mode = Mode.NONE;
    private static long updatedAtMs;

    private TextEntryGoalGuard() {}

    static synchronized void updateFromUserText(String rawText) {
        clearLocked();
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty() || !hasTextEntryIntent(text) || looksNonExecuting(text)) return;

        int parsed = parseRequestedCount(text);
        if (parsed <= 0 || parsed > MAX_EXPECTED) return;

        expectedCount = parsed;
        String folded = text.toLowerCase(Locale.ROOT);
        if (containsAny(folded, "至少", "不少於", "不少于", "at least", "minimum of")) {
            mode = Mode.MINIMUM;
        } else if (containsAny(folded, "剛好", "刚好", "正好", "恰好", "exactly", "exact ")) {
            mode = Mode.EXACT;
        } else {
            mode = Mode.APPROXIMATE;
        }
        updatedAtMs = System.currentTimeMillis();
    }

    static synchronized Validation validate(String text) {
        expireLocked();
        int actual = codePointLength(text == null ? "" : text.trim());
        if (expectedCount <= 0 || mode == Mode.NONE) {
            return new Validation(false, true, 0, actual, Mode.NONE);
        }

        boolean allowed;
        if (mode == Mode.EXACT) {
            allowed = actual == expectedCount;
        } else if (mode == Mode.MINIMUM) {
            allowed = actual >= expectedCount;
        } else {
            int min = Math.max(1, (int) Math.floor(expectedCount * 0.90d));
            int max = Math.max(min, (int) Math.ceil(expectedCount * 1.10d));
            allowed = actual >= min && actual <= max;
        }
        return new Validation(true, allowed, expectedCount, actual, mode);
    }

    static synchronized void clear() {
        clearLocked();
    }

    private static void expireLocked() {
        long age = System.currentTimeMillis() - updatedAtMs;
        if (updatedAtMs == 0L || age < 0L || age > TTL_MS) clearLocked();
    }

    private static void clearLocked() {
        expectedCount = 0;
        mode = Mode.NONE;
        updatedAtMs = 0L;
    }

    private static boolean hasTextEntryIntent(String raw) {
        String folded = raw.toLowerCase(Locale.ROOT);
        return containsAny(folded,
                "打字", "輸入", "输入", "寫", "写", "填入", "填上", "貼上", "贴上",
                "type", "write", "enter", "input", "paste");
    }

    private static boolean looksNonExecuting(String raw) {
        String folded = raw.toLowerCase(Locale.ROOT);
        return containsAny(folded,
                "不要", "別", "别", "不用", "取消", "停止", "如果", "假如",
                "怎麼", "怎么", "如何", "能不能",
                "don't", "dont", "do not", "never", "cancel", "stop", "how to", "if ");
    }

    private static int parseRequestedCount(String raw) {
        Matcher arabic = ARABIC_COUNT.matcher(raw);
        if (arabic.find()) {
            try { return Integer.parseInt(arabic.group(1)); }
            catch (Exception ignored) {}
        }

        Matcher chinese = CHINESE_COUNT.matcher(raw);
        if (chinese.find()) return parseChineseNumber(chinese.group(1));
        return 0;
    }

    private static int parseChineseNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        int total = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit = chineseDigit(c);
            if (digit >= 0) {
                current = digit;
                continue;
            }

            int unit = chineseUnit(c);
            if (unit > 0) {
                if (current == 0) current = 1;
                total += current * unit;
                current = 0;
            }
        }
        return total + current;
    }

    private static int chineseDigit(char c) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '兩': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private static int chineseUnit(char c) {
        switch (c) {
            case '十': return 10;
            case '百': return 100;
            case '千': return 1000;
            default: return 0;
        }
    }

    private static int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && value.contains(needle)) return true;
        }
        return false;
    }
}
