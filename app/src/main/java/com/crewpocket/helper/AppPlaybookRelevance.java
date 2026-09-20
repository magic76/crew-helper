package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure deterministic ranking for app-local guidance relevance. */
final class AppPlaybookRelevance {
    static final class Entry {
        final int index;
        final String title;
        final String guidance;
        final long updatedAt;

        Entry(int index, String title, String guidance, long updatedAt) {
            this.index = index;
            this.title = safe(title);
            this.guidance = safe(guidance);
            this.updatedAt = updatedAt;
        }
    }

    private static final class Scored {
        final Entry entry;
        final int score;

        Scored(Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private AppPlaybookRelevance() {}

    static ArrayList<Integer> rank(
            ArrayList<Entry> entries,
            String query,
            int limit) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        if (entries == null || entries.isEmpty() || limit <= 0) return out;

        String q = normalize(query);
        if (q.isEmpty()) return out;

        Set<String> queryTokens = tokens(q);
        ArrayList<Scored> scored = new ArrayList<Scored>();
        for (Entry entry : entries) {
            if (entry == null) continue;
            int score = score(entry, q, queryTokens);
            if (score > 0) scored.add(new Scored(entry, score));
        }

        Collections.sort(scored, new Comparator<Scored>() {
            @Override public int compare(Scored a, Scored b) {
                if (a.score != b.score) return b.score - a.score;
                if (a.entry.updatedAt == b.entry.updatedAt) {
                    return a.entry.index - b.entry.index;
                }
                return a.entry.updatedAt > b.entry.updatedAt ? -1 : 1;
            }
        });

        for (Scored item : scored) {
            out.add(item.entry.index);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static int score(Entry entry, String query, Set<String> queryTokens) {
        String title = normalize(entry.title);
        String guidance = normalize(entry.guidance);
        if (guidance.isEmpty()) return 0;

        int score = 0;
        String compactQuery = compact(query);
        String compactTitle = compact(title);
        String compactGuidance = compact(guidance);

        if (!compactTitle.isEmpty() && compactTitle.length() >= 2) {
            if (compactQuery.contains(compactTitle)) score += 12;
            else if (compactTitle.contains(compactQuery) && compactQuery.length() >= 3) score += 8;
        }

        Set<String> titleTokens = tokens(title);
        Set<String> guidanceTokens = tokens(guidance);
        for (String token : queryTokens) {
            if (titleTokens.contains(token)) score += token.length() >= 3 ? 5 : 3;
            if (guidanceTokens.contains(token)) score += token.length() >= 3 ? 3 : 2;
        }

        score += Math.min(8, cjkBigramOverlap(compactQuery, compactTitle) * 2);
        score += Math.min(10, cjkBigramOverlap(compactQuery, compactGuidance));

        // Narrow phrase evidence is particularly useful for app-local rules.
        for (String token : queryTokens) {
            if (token.length() >= 4 && compactGuidance.contains(compact(token))) {
                score += 3;
            }
        }
        return score;
    }

    private static Set<String> tokens(String value) {
        HashSet<String> out = new HashSet<String>();
        String normalized = normalize(value);
        for (String token : normalized.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (containsCjk(token)) {
                if (token.length() <= 8) out.add(token);
                for (int i = 0; i + 1 < token.length(); i++) {
                    out.add(token.substring(i, i + 2));
                }
            } else if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    private static int cjkBigramOverlap(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        HashSet<String> left = new HashSet<String>();
        for (int i = 0; i + 1 < a.length(); i++) {
            String pair = a.substring(i, i + 2);
            if (containsCjk(pair)) left.add(pair);
        }
        int count = 0;
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i + 1 < b.length(); i++) {
            String pair = b.substring(i, i + 2);
            if (left.contains(pair) && seen.add(pair)) count++;
        }
        return count;
    }

    private static boolean containsCjk(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return TextMatch.caseFold(safe(value))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String compact(String value) {
        return normalize(value)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
