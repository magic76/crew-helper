package com.crewpocket.helper;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small outbound-only scrubber; never accepts free-form screenshots or transcripts. */
final class CoachDataRedactor {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile(
            "(?<!\\d)(?:\\+?\\d[\\s().-]?){7,16}(?!\\d)");
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private CoachDataRedactor() {}

    static String safeGoal(String goal, String goalIntent) {
        // Natural-language goals often contain the exact search, recipient,
        // address, or message content. Only emit the Runtime's stable intent
        // category; never use the free-form task text as a fallback.
        String intent = clean(goalIntent, 80).toUpperCase(Locale.ROOT);
        return intent.matches("[A-Z0-9:_-]{1,80}") ? intent : "";
    }

    static String safeLabel(String label) {
        String text = clean(label, 120);
        if (text.isEmpty() || SensitiveDataGuard.containsSensitiveFieldMarker(text)) {
            return "";
        }
        if (EMAIL.matcher(text).find() || LONG_NUMBER.matcher(text).find()
                || URL.matcher(text).find()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("message") || lower.contains("conversation")
                || text.contains("訊息") || text.contains("對話")) {
            return "";
        }
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    static String safeFreeText(String value, int maxLength) {
        String text = clean(value, maxLength * 2);
        if (text.isEmpty() || SensitiveDataGuard.containsSensitiveFieldMarker(text)) {
            return "";
        }
        return redactValues(text).length() > maxLength
                ? redactValues(text).substring(0, maxLength)
                : redactValues(text);
    }

    static String safeErrorCode(String value) {
        String text = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!text.matches("[A-Z0-9_]{1,80}")) return "RUNTIME_ACTION_FAILED";
        return text;
    }

    static boolean isSensitivePackage(String packageName) {
        return ReflectionLearningPolicy.isSensitivePackage(packageName);
    }

    private static String redactValues(String value) {
        String out = EMAIL.matcher(value).replaceAll("[REDACTED]");
        out = LONG_NUMBER.matcher(out).replaceAll("[REDACTED]");
        out = URL.matcher(out).replaceAll("[REDACTED]");
        return out;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        String text = CONTROL.matcher(value).replaceAll("")
                .replaceAll("\\s+", " ").trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
