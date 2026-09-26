package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure policy for distilled operational memory.
 *
 * Raw user values are used only transiently to classify a step; persisted
 * patterns contain semantic tokens such as SEARCH_QUERY or TAP_GOAL_ENTITY.
 */
final class RefinedMemoryPolicy {
    static final String TYPE_PROCEDURE = "PROCEDURE";
    static final String STATE_CANDIDATE = "CANDIDATE";
    static final String STATE_VERIFIED = "VERIFIED";
    static final String STATE_TRUSTED = "TRUSTED";
    static final String STATE_STALE = "STALE";
    static final String STATE_SUSPECT = "SUSPECT";
    static final int MAX_PATTERN_STEPS = 7;

    static final class Step {
        final String tool;
        final String target;

        Step(String tool, String target) {
            this.tool = clean(tool);
            this.target = clean(target);
        }
    }

    private RefinedMemoryPolicy() {}

    static boolean isEligibleScope(String scope) {
        String value = clean(scope);
        return value.startsWith("MEDIA:")
                || "NAVIGATION:START".equals(value)
                || "SEARCH:RESULT".equals(value)
                || "APP:OPEN".equals(value);
    }

    static String patternFor(
            String scope,
            String goalText,
            List<Step> steps) {
        if (!isEligibleScope(scope)
                || steps == null
                || steps.isEmpty()) {
            return "";
        }

        ArrayList<String> tokens = new ArrayList<String>();
        for (Step step : steps) {
            if (step == null) continue;
            String token = tokenFor(scope, goalText, step);
            if (token.isEmpty()) continue;
            if (!tokens.isEmpty()
                    && token.equals(tokens.get(tokens.size() - 1))) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() >= MAX_PATTERN_STEPS) break;
        }

        if (tokens.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append(" > ");
            out.append(token);
        }
        return canonicalPattern(out.toString());
    }

    static String canonicalPattern(String pattern) {
        String raw = clean(pattern);
        if (raw.isEmpty()) return "";
        String[] parts = raw.split(">");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String token = clean(part)
                    .toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9_:-]", "");
            if (token.isEmpty()) continue;
            if (out.length() > 0) out.append(" > ");
            out.append(token);
            if (out.toString().split(" > ").length >= MAX_PATTERN_STEPS) break;
        }
        return out.toString();
    }

    static boolean patternsEquivalent(String left, String right) {
        String a = canonicalPattern(left);
        String b = canonicalPattern(right);
        return !a.isEmpty() && a.equals(b);
    }

    static boolean isSelectable(boolean enabled, String state) {
        return enabled && isInjectable(state);
    }

    static String guidanceFor(String scope, String pattern) {
        String s = clean(scope);
        String p = canonicalPattern(pattern);
        if (!isEligibleScope(s) || p.isEmpty()) return "";
        return s + " usual verified procedure: " + p
                + ". Use only when current screen evidence agrees.";
    }

    static String stateFor(int successCount, int failureCount) {
        int success = Math.max(0, successCount);
        int failure = Math.max(0, failureCount);
        int evidence = success + failure;
        if (evidence == 0) return STATE_CANDIDATE;

        double rate = success / (double) evidence;
        if (failure >= 3 && rate < 0.60d) {
            return STATE_STALE;
        }
        if (success >= 6 && rate >= 0.90d) {
            return STATE_TRUSTED;
        }
        if (success >= 3 && rate >= 0.75d) {
            return STATE_VERIFIED;
        }
        return STATE_CANDIDATE;
    }

    static double confidenceFor(int successCount, int failureCount) {
        int success = Math.max(0, successCount);
        int failure = Math.max(0, failureCount);
        int evidence = success + failure;
        if (evidence == 0) return 0.0d;

        double bayes = (success + 1.0d) / (evidence + 2.0d);
        double maturity =
                0.58d + 0.42d * Math.min(8, evidence) / 8.0d;
        return clamp(bayes * maturity, 0.05d, 0.98d);
    }

    static boolean isInjectable(String state) {
        return STATE_VERIFIED.equals(clean(state))
                || STATE_TRUSTED.equals(clean(state));
    }

    static int packageAffinityScore(
            String memoryPrimaryPackage,
            String memoryStartPackage,
            String currentPackage,
            String taskStartPackage) {
        String primary = clean(memoryPrimaryPackage);
        String learnedStart = clean(memoryStartPackage);
        String current = clean(currentPackage);
        String currentStart = clean(taskStartPackage);

        if (!primary.isEmpty() && primary.equals(current)) {
            return 220;
        }
        if (!learnedStart.isEmpty()
                && !currentStart.isEmpty()
                && learnedStart.equals(currentStart)) {
            return 140;
        }
        if (primary.isEmpty()) {
            return 40;
        }
        return Integer.MIN_VALUE;
    }

    static int relevanceScore(
            String state,
            double confidence,
            boolean exactScope,
            boolean exactPackage,
            long lastVerifiedAt,
            long now) {
        if (!isInjectable(state) || !exactScope) return Integer.MIN_VALUE;
        int score = STATE_TRUSTED.equals(state) ? 500 : 320;
        score += exactPackage ? 220 : 40;
        score += (int) Math.round(clamp(confidence, 0.0d, 1.0d) * 100.0d);

        long age = Math.max(0L, now - Math.max(0L, lastVerifiedAt));
        long day = 24L * 60L * 60L * 1000L;
        if (age <= 7L * day) score += 80;
        else if (age <= 30L * day) score += 40;
        else if (age > 120L * day) score -= 180;
        return score;
    }

    static boolean targetAppearsInGoal(String target, String goalText) {
        String t = normalize(target);
        String g = normalize(goalText);
        return t.length() >= 2
                && !g.isEmpty()
                && g.contains(t);
    }

    private static String tokenFor(
            String scope,
            String goalText,
            Step step) {
        String tool = clean(step.tool);
        String target = clean(step.target);
        if ("launch_app".equals(tool)) return "OPEN_APP";
        if ("search_current_app".equals(tool)) return "SEARCH_QUERY";
        if ("commit_search".equals(tool)) return "COMMIT_SEARCH";
        if ("swipe_screen".equals(tool)) return "SCROLL";
        if ("press_key".equals(tool)) {
            String key = target.toUpperCase(Locale.ROOT);
            if ("BACK".equals(key)) return "BACK";
            if ("HOME".equals(key)) return "HOME";
            return "";
        }
        if ("tap_screen".equals(tool) || "tap_element".equals(tool)) {
            return tapToken(scope, goalText, target);
        }
        return "";
    }

    private static String tapToken(
            String scope,
            String goalText,
            String target) {
        if ("MEDIA:PLAY".equals(scope)
                && MediaPlaybackCompletionPolicy.isPlayControl(target)) {
            return "TAP_PLAY_CONTROL";
        }
        if ("MEDIA:PAUSE".equals(scope)
                && containsAny(normalize(target),
                        "pause", "暫停", "暂停")) {
            return "TAP_MEDIA_CONTROL";
        }
        if (("MEDIA:NEXT".equals(scope)
                || "MEDIA:PREVIOUS".equals(scope))
                && !target.isEmpty()) {
            return "TAP_MEDIA_CONTROL";
        }
        if (targetAppearsInGoal(target, goalText)) {
            return "TAP_GOAL_ENTITY";
        }
        if ("NAVIGATION:START".equals(scope)
                && containsAny(
                        normalize(target),
                        "start", "go", "開始", "开始",
                        "導航", "导航")) {
            return "TAP_TERMINAL_CONTROL";
        }
        return "TAP_ACTION";
    }

    private static boolean containsAny(
            String value,
            String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            String n = normalize(needle);
            if (!n.isEmpty() && value.contains(n)) return true;
        }
        return false;
    }

    private static double clamp(
            double value,
            double min,
            double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
