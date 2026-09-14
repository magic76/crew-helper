package com.crewpocket.helper;

/**
 * One-shot parser for explicit App teaching commands.
 *
 * Runtime owns whether a rule is persisted. Gemini may still understand the
 * conversation, but it is not allowed to decide list-vs-write for an armed
 * teaching turn.
 */
final class AppTeachIntent {
    enum Kind { NONE, ARM, SAVE, CANCEL }

    final Kind kind;
    final String guidance;

    private AppTeachIntent(Kind kind, String guidance) {
        this.kind = kind == null ? Kind.NONE : kind;
        this.guidance = guidance == null ? "" : guidance.trim();
    }

    static AppTeachIntent parse(String rawText, boolean alreadyArmed) {
        String clean = collapse(rawText);
        if (clean.isEmpty()) return none();

        String folded = TextMatch.caseFold(clean);
        String compact = folded.replaceAll("\\s+", "");

        if (alreadyArmed) {
            if (isCancel(compact, folded)) return new AppTeachIntent(Kind.CANCEL, "");
            return new AppTeachIntent(Kind.SAVE, clean);
        }

        if (looksLikeQuestionOrDiscussion(compact, folded)) return none();

        String remainder = stripChinesePrefix(clean);
        if (remainder == null) remainder = stripEnglishPrefix(clean, folded);
        if (remainder == null) return none();

        remainder = remainder.replaceFirst("^[\\s，,。！？!：:；;、\\-]+", "").trim();
        if (remainder.isEmpty()) return new AppTeachIntent(Kind.ARM, "");
        return new AppTeachIntent(Kind.SAVE, remainder);
    }

    private static String stripChinesePrefix(String text) {
        String[] prefixes = new String[]{
                "我要教你一個規則", "我要教你一个规则",
                "教你一個規則", "教你一个规则",
                "幫我記住一個規則", "帮我记住一个规则",
                "記住一個規則", "记住一个规则",
                "記住這個 App", "记住这个 App",
                "記住這個app", "记住这个app",
                "記住這個應用", "记住这个应用",
                "記住這個程式", "记住这个程序",
                "記住這個操作", "记住这个操作",
                "記住這個流程", "记住这个流程",
                "這個 App 要記住", "这个 App 要记住",
                "這個app要記住", "这个app要记住"
        };
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return text.substring(prefix.length());
        }
        return null;
    }

    private static String stripEnglishPrefix(String original, String folded) {
        String[] prefixes = new String[]{
                "let me teach you a rule",
                "teach you a rule",
                "remember a rule for this app",
                "remember this app rule",
                "learn this app rule"
        };
        for (String prefix : prefixes) {
            if (folded.startsWith(prefix)) return original.substring(prefix.length());
        }
        return null;
    }

    private static boolean isCancel(String compact, String folded) {
        return compact.matches("(?:取消|取消教學|取消教学|算了|不用記了|不用记了|不要記了|不要记了)")
                || folded.matches("(?:cancel|never mind|nevermind)(?: app teaching| teaching)?");
    }

    private static boolean looksLikeQuestionOrDiscussion(String compact, String folded) {
        if (compact.startsWith("怎麼") || compact.startsWith("怎么")
                || compact.startsWith("如何") || compact.startsWith("為什麼")
                || compact.startsWith("为什么") || compact.startsWith("能不能")
                || compact.startsWith("可不可以") || compact.startsWith("不要記")
                || compact.startsWith("不要记") || compact.startsWith("不用記")
                || compact.startsWith("不用记") || compact.startsWith("別記")
                || compact.startsWith("别记")) {
            return true;
        }
        return folded.startsWith("how ") || folded.startsWith("why ")
                || folded.startsWith("can you ") || folded.startsWith("could you ")
                || folded.startsWith("don't remember") || folded.startsWith("do not remember");
    }

    private static AppTeachIntent none() {
        return new AppTeachIntent(Kind.NONE, "");
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
