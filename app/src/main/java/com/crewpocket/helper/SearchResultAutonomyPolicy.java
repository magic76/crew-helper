package com.crewpocket.helper;

import java.util.List;
import java.util.Locale;

/**
 * Pure decision policy for whether Runtime may choose a visible search result
 * without interrupting the user.
 *
 * Search ranking remains owned by the app (for example Google Maps). Runtime
 * only checks that the ranked candidate is textually plausible and that the
 * continuation is low-risk/reversible.
 */
final class SearchResultAutonomyPolicy {
    static final class Candidate {
        final String label;
        final boolean exactMatch;
        final boolean strongMatch;

        Candidate(String label, boolean exactMatch, boolean strongMatch) {
            this.label = label == null ? "" : label;
            this.exactMatch = exactMatch;
            this.strongMatch = strongMatch;
        }
    }

    static final class Decision {
        final boolean autoSelect;
        final int index;
        final String reason;

        Decision(boolean autoSelect, int index, String reason) {
            this.autoSelect = autoSelect;
            this.index = index;
            this.reason = reason == null ? "" : reason;
        }
    }

    private SearchResultAutonomyPolicy() {}

    static Decision decide(
            String query,
            String continuation,
            List<Candidate> candidates) {
        if (!isLowRiskContinuation(continuation)) {
            return ask("CONTINUATION_REQUIRES_USER");
        }
        if (candidates == null || candidates.isEmpty()) {
            return ask("NO_CANDIDATES");
        }

        int bestIndex = -1;
        int bestScore = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            int score = score(query, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0 && bestScore >= 70) {
            return new Decision(
                    true,
                    bestIndex,
                    bestScore >= 100
                            ? "EXACT_MATCH"
                            : (bestScore >= 85
                                ? "STRONG_MATCH"
                                : "QUERY_CONTAINMENT"));
        }
        return ask("LOW_MATCH_CONFIDENCE");
    }

    static boolean isLowRiskContinuation(String continuation) {
        String value = continuation == null
                ? ""
                : continuation.trim().toUpperCase(Locale.ROOT);
        return "NAVIGATE".equals(value)
                || "OPEN_RESULT".equals(value)
                || "RESULT_DETAILS".equals(value);
    }

    private static int score(String query, Candidate candidate) {
        if (candidate == null) return 0;
        if (candidate.exactMatch) return 100;
        if (candidate.strongMatch) return 85;

        String q = normalize(query);
        String label = normalize(candidate.label);
        if (q.isEmpty() || label.isEmpty()) return 0;

        // A ranked app result that fully contains the user's query is enough
        // for low-risk autonomy. Example: 大皇宮 -> 曼谷大皇宮.
        if (label.contains(q) || q.contains(label)) {
            return 70;
        }
        return 0;
    }

    private static Decision ask(String reason) {
        return new Decision(false, -1, reason);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()\\-_/]+", "")
                        .trim();
    }
}
