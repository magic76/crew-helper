package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists only bounded deterministic evidence counters used by Crew Experience.
 *
 * Stores only deterministic friction evidence.
 *
 * Strong friction is immediately reviewable. Medium friction is counted by
 * package + Runtime rule key and becomes reviewable only after the same pattern
 * repeats. Plain success is never accumulated here. No transcript, screenshot,
 * typed/search value, model reply, or arbitrary tool argument is stored.
 */
final class ExperienceEvidenceStore {
    private static final String PREFS = "crew_experience_evidence";
    private static final String KEY_FRICTION = "friction_v2";
    private static final int MAX_ITEMS = 80;
    private static final Object LOCK = new Object();

    private ExperienceEvidenceStore() {}

    static List<ReflectionRuleEvidence.Candidate> qualify(
            Context context,
            String packageName,
            List<ReflectionRuleEvidence.Candidate> candidates) {
        ArrayList<ReflectionRuleEvidence.Candidate> qualified =
                new ArrayList<ReflectionRuleEvidence.Candidate>();
        if (context == null || candidates == null || candidates.isEmpty()) {
            return qualified;
        }

        String pkg = safePackage(packageName);
        if (pkg.isEmpty()) return qualified;

        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray items = readArray(prefs.getString(KEY_FRICTION, "[]"));
            long now = System.currentTimeMillis();
            boolean changed = false;

            for (ReflectionRuleEvidence.Candidate candidate : candidates) {
                if (candidate == null
                        || !ReflectionRuleEvidence.KIND_FRICTION.equals(candidate.kind)
                        || !ExperienceTriggerPolicy.isTrackableFriction(
                                candidate.frictionScore)) {
                    continue;
                }

                JSONObject item = find(items, pkg, candidate.ruleKey);
                int occurrences = 1;
                try {
                    if (item == null) {
                        item = new JSONObject()
                                .put("package", pkg)
                                .put("ruleKey", safeRuleKey(candidate.ruleKey))
                                .put("occurrences", 1)
                                .put("maxScore", candidate.frictionScore)
                                .put("signals", safeSignals(candidate.frictionSignals))
                                .put("updatedAt", now);
                        items.put(item);
                    } else {
                        occurrences = Math.max(
                                0, item.optInt("occurrences", 0)) + 1;
                        item.put("occurrences", occurrences)
                                .put("maxScore", Math.max(
                                        item.optInt("maxScore", 0),
                                        candidate.frictionScore))
                                .put("signals", safeSignals(candidate.frictionSignals))
                                .put("updatedAt", now);
                    }
                    changed = true;
                } catch (Exception ignored) {
                    continue;
                }

                if (ExperienceTriggerPolicy.isImmediateFriction(
                                candidate.frictionScore)
                        || ExperienceTriggerPolicy.shouldReviewRepeatedFriction(
                                candidate.frictionScore,
                                occurrences)) {
                    qualified.add(candidate);
                }
            }

            if (changed) {
                prefs.edit()
                        .putString(KEY_FRICTION, trim(items).toString())
                        .apply();
            }
        }
        return qualified;
    }

    static String buildSummary(Context context) {
        int tracked = 0;
        int repeated = 0;
        int strongestScore = 0;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray items = readArray(prefs.getString(KEY_FRICTION, "[]"));
                tracked = items.length();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    int occurrences = item.optInt("occurrences", 0);
                    if (occurrences >= ExperienceTriggerPolicy.REPEATED_FRICTION_OCCURRENCES) {
                        repeated++;
                    }
                    strongestScore = Math.max(strongestScore, item.optInt("maxScore", 0));
                }
            } catch (Exception ignored) {}
        }
        return "Status: Healthy"
                + "\nFriction patterns: " + tracked
                + "\nRepeated patterns: " + repeated
                + "\nHighest friction score: " + strongestScore;
    }

    static String buildReport(Context context) {
        int tracked = 0;
        int strongestScore = 0;
        int repeated = 0;
        long latestAt = 0L;

        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray items = readArray(
                        prefs.getString(KEY_FRICTION, "[]"));
                tracked = items.length();

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    int occurrences = item.optInt("occurrences", 0);
                    strongestScore = Math.max(
                            strongestScore,
                            item.optInt("maxScore", 0));
                    if (occurrences >=
                            ExperienceTriggerPolicy.REPEATED_FRICTION_OCCURRENCES) {
                        repeated++;
                    }
                    latestAt = Math.max(
                            latestAt,
                            item.optLong("updatedAt", 0L));
                }
            } catch (Exception ignored) {}
        }

        StringBuilder out = new StringBuilder();
        out.append("Crew Experience triggers\n")
                .append("Plain success: never reviewed\n")
                .append("Strong friction: immediate at score >= ")
                .append(ExperienceTriggerPolicy.IMMEDIATE_FRICTION_SCORE)
                .append("\n")
                .append("Medium friction: repeat ")
                .append(ExperienceTriggerPolicy.REPEATED_FRICTION_OCCURRENCES)
                .append("x for the same pattern\n")
                .append("Tracked friction patterns: ").append(tracked).append("\n")
                .append("Repeated patterns: ").append(repeated).append("\n")
                .append("Highest friction score: ").append(strongestScore).append("\n")
                .append("Last friction evidence: ")
                .append(latestAt <= 0L ? "none" : String.valueOf(latestAt));
        return out.toString();
    }

    private static JSONObject find(JSONArray items, String pkg, String ruleKey) {
        if (items == null || ruleKey == null || ruleKey.isEmpty()) return null;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (pkg.equals(item.optString("package", ""))
                    && ruleKey.equals(item.optString("ruleKey", ""))) {
                return item;
            }
        }
        return null;
    }

    private static JSONArray trim(JSONArray source) {
        if (source == null || source.length() <= MAX_ITEMS) return source;
        JSONArray out = new JSONArray();
        for (int i = source.length() - MAX_ITEMS; i < source.length(); i++) {
            Object value = source.opt(i);
            if (value != null) out.put(value);
        }
        return out;
    }

    private static JSONArray readArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static String safePackage(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 180) text = text.substring(0, 180);
        return text.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String safeSignals(String value) {
        String text = value == null ? "" : value.trim().toUpperCase();
        if (text.length() > 120) text = text.substring(0, 120);
        return text.replaceAll("[^A-Z0-9_|:-]", "");
    }

    private static String safeRuleKey(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 260) text = text.substring(0, 260);
        return text.replaceAll("[^A-Za-z0-9:_*|= .>-]", "");
    }
}
