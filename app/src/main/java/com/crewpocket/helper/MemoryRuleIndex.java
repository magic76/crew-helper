package com.crewpocket.helper;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** 0032: conservative Runtime-owned Shortcut matcher. */
final class MemoryRuleIndex {
    static final class Match {
        final MemoryRuleStore.Rule rule;
        final String mode;
        final double confidence;
        final String matchedPhrase;

        Match(MemoryRuleStore.Rule rule, String mode, double confidence, String matchedPhrase) {
            this.rule = rule;
            this.mode = mode == null ? "" : mode;
            this.confidence = confidence;
            this.matchedPhrase = matchedPhrase == null ? "" : matchedPhrase;
        }
    }

    private final MemoryRuleStore store;
    private volatile List<MemoryRuleStore.Rule> rules =
            new ArrayList<MemoryRuleStore.Rule>();

    MemoryRuleIndex(Context context) {
        store = new MemoryRuleStore(context.getApplicationContext());
        refresh();
    }

    synchronized void refresh() {
        rules = new ArrayList<MemoryRuleStore.Rule>(store.list());
    }

    int count() {
        List<MemoryRuleStore.Rule> snapshot = rules;
        return snapshot == null ? 0 : snapshot.size();
    }

    MemoryRuleStore.Rule findExact(String spokenText) {
        Match match = findBest(spokenText);
        if (match == null) return null;
        return "EXACT".equals(match.mode) || "ALIAS_EXACT".equals(match.mode)
                ? match.rule : null;
    }

    Match findBest(String spokenText) {
        if (looksNonExecutable(spokenText)) return null;

        String input = collapseImmediateRepetition(normalize(spokenText));
        if (input.length() < 2) return null;
        String strippedInput = stripConversationalWrappers(input);

        Match best = null;
        List<MemoryRuleStore.Rule> snapshot = rules;
        if (snapshot == null) return null;

        for (MemoryRuleStore.Rule rule : snapshot) {
            if (rule == null || !rule.enabled) continue;

            best = better(best, scorePhrase(rule, input, strippedInput, rule.trigger, false));
            for (String alias : rule.aliases) {
                best = better(best, scorePhrase(rule, input, strippedInput, alias, true));
            }
        }
        return best;
    }

    /**
     * True only for direct command-shaped speech.  This is deliberately not a
     * semantic matcher: it lets the UI explain a missed recorded shortcut
     * without turning normal conversation into a phone action.
     */
    static boolean looksLikeRecordedShortcut(String spokenText) {
        if (looksNonExecutable(spokenText)) return false;
        String value = stripConversationalWrappers(
                collapseImmediateRepetition(normalize(spokenText)));
        return startsWithAny(value, "打開", "打开", "開啟", "开启", "啟動", "启动", "執行", "执行");
    }

    private static Match scorePhrase(MemoryRuleStore.Rule rule,
                                     String input,
                                     String strippedInput,
                                     String rawPhrase,
                                     boolean alias) {
        String phrase = normalize(rawPhrase);
        if (phrase.length() < 2) return null;

        if (input.equals(phrase)) {
            return new Match(rule, alias ? "ALIAS_EXACT" : "EXACT", 1.0, rawPhrase);
        }

        String strippedPhrase = stripConversationalWrappers(phrase);
        if (!strippedInput.isEmpty()
                && strippedInput.equals(strippedPhrase)
                && !input.equals(phrase)) {
            return new Match(rule, alias ? "ALIAS_WRAPPED" : "WRAPPED", 0.97, rawPhrase);
        }

        if (strippedInput.length() >= 6
                && strippedPhrase.length() >= 6
                && Math.abs(strippedInput.length() - strippedPhrase.length()) <= 1
                && levenshteinAtMostOne(strippedInput, strippedPhrase)) {
            return new Match(rule, alias ? "ALIAS_ASR_1" : "ASR_1", 0.92, rawPhrase);
        }
        return null;
    }

    private static Match better(Match current, Match candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        if (candidate.confidence > current.confidence) return candidate;
        if (candidate.confidence < current.confidence) return current;
        return candidate.matchedPhrase.length() > current.matchedPhrase.length()
                ? candidate : current;
    }

    private static boolean looksNonExecutable(String spokenText) {
        String value = normalize(spokenText);
        if (value.isEmpty()) return true;
        return startsWithAny(value,
                "不要", "別", "别", "不用", "取消", "停止",
                "如果", "假如", "假設", "假设",
                "怎麼", "怎么", "如何", "為什麼", "为什么",
                "dont", "donot", "never", "cancel", "stop", "howto", "whatif");
    }

    private static String stripConversationalWrappers(String value) {
        String out = value == null ? "" : value;
        boolean changed = true;
        while (changed && !out.isEmpty()) {
            changed = false;
            String next = stripPrefix(out,
                    "好", "好的", "請", "请", "麻煩", "麻烦",
                    "可以", "你可以", "請你", "请你", "你幫我", "你帮我",
                    "幫我", "帮我", "請幫我", "请帮我", "我要", "我想",
                    "麻煩幫我", "麻烦帮我", "麻煩你", "麻烦你");
            if (!next.equals(out)) {
                out = next;
                changed = true;
            }

            next = stripSuffix(out,
                    "一下", "一下吧", "吧", "啦", "了", "喔", "哦",
                    "可以嗎", "可以吗", "嗎", "吗", "呢", "謝謝", "谢谢",
                    "這個app", "这个app", "這個應用程式", "这个应用程序",
                    "這個程式", "这个程序");
            if (!next.equals(out)) {
                out = next;
                changed = true;
            }
        }
        return out;
    }

    private static String stripPrefix(String value, String... prefixes) {
        for (String prefix : prefixes) {
            String p = normalize(prefix);
            if (!p.isEmpty() && value.startsWith(p) && value.length() > p.length()) {
                return value.substring(p.length());
            }
        }
        return value;
    }

    /** Gemini may occasionally emit the same finalized phrase twice. */
    private static String collapseImmediateRepetition(String value) {
        if (value == null || value.length() < 4 || (value.length() & 1) != 0) return value;
        int half = value.length() / 2;
        String first = value.substring(0, half);
        return first.equals(value.substring(half)) ? first : value;
    }

    private static String stripSuffix(String value, String... suffixes) {
        for (String suffix : suffixes) {
            String s = normalize(suffix);
            if (!s.isEmpty() && value.endsWith(s) && value.length() > s.length()) {
                return value.substring(0, value.length() - s.length());
            }
        }
        return value;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            String p = normalize(prefix);
            if (!p.isEmpty() && value.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean levenshteinAtMostOne(String a, String b) {
        if (a.equals(b)) return true;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;

        int i = 0, j = 0, edits = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            edits++;
            if (edits > 1) return false;
            if (la == lb) {
                i++;
                j++;
            } else if (la > lb) {
                i++;
            } else {
                j++;
            }
        }
        if (i < la || j < lb) edits++;
        return edits <= 1;
    }

    private static String normalize(String text) {
        return TextMatch.caseFold(text == null ? "" : text)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
    }
}
