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
 * Recovery candidates are immediately qualified. Routine successful patterns are
 * counted by package + Runtime rule key and become reviewable only every Nth
 * compatible occurrence. No transcript, screenshot, typed/search value, model
 * reply, or arbitrary tool argument is stored here.
 */
final class ExperienceEvidenceStore {
    private static final String PREFS = "crew_experience_evidence";
    private static final String KEY_ROUTINE = "routine_v1";
    private static final int MAX_ITEMS = 80;
    private static final Object LOCK = new Object();

    private ExperienceEvidenceStore() {}

    static List<ReflectionRuleEvidence.Candidate> qualify(
            Context context,
            String packageName,
            List<ReflectionRuleEvidence.Candidate> candidates) {
        ArrayList<ReflectionRuleEvidence.Candidate> qualified =
                new ArrayList<ReflectionRuleEvidence.Candidate>();
        if (context == null || candidates == null || candidates.isEmpty()) return qualified;

        String pkg = safePackage(packageName);
        if (pkg.isEmpty()) return qualified;

        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray items = readArray(prefs.getString(KEY_ROUTINE, "[]"));
            long now = System.currentTimeMillis();
            boolean changed = false;

            for (ReflectionRuleEvidence.Candidate candidate : candidates) {
                if (candidate == null) continue;

                if (ExperienceTriggerPolicy.isImmediate(candidate.kind)) {
                    qualified.add(candidate);
                    continue;
                }
                if (!ReflectionRuleEvidence.KIND_ROUTINE.equals(candidate.kind)) continue;

                JSONObject item = find(items, pkg, candidate.ruleKey);
                int count;
                try {
                    if (item == null) {
                        item = new JSONObject()
                                .put("package", pkg)
                                .put("ruleKey", safeRuleKey(candidate.ruleKey))
                                .put("count", 1)
                                .put("updatedAt", now);
                        items.put(item);
                        count = 1;
                    } else {
                        count = Math.max(0, item.optInt("count", 0)) + 1;
                        item.put("count", count).put("updatedAt", now);
                    }
                    changed = true;
                } catch (Exception ignored) {
                    continue;
                }

                if (ExperienceTriggerPolicy.shouldReviewRoutineAtCount(count)) {
                    qualified.add(candidate);
                }
            }

            if (changed) {
                prefs.edit().putString(KEY_ROUTINE, trim(items).toString()).apply();
            }
        }
        return qualified;
    }

    static String buildReport(Context context) {
        int tracked = 0;
        int highest = 0;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray items = readArray(prefs.getString(KEY_ROUTINE, "[]"));
                tracked = items.length();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) highest = Math.max(highest, item.optInt("count", 0));
                }
            } catch (Exception ignored) {}
        }

        int threshold = ExperienceTriggerPolicy.ROUTINE_REPEAT_THRESHOLD;
        int progress = highest <= 0 ? 0 : highest % threshold;
        if (highest > 0 && progress == 0) progress = threshold;

        StringBuilder out = new StringBuilder();
        out.append("Crew Experience triggers\n")
                .append("Recovery: immediate after proven failure -> correction -> success\n")
                .append("Routine success: review every ").append(threshold)
                .append(" matching occurrences\n")
                .append("Tracked routine patterns: ").append(tracked).append("\n")
                .append("Closest routine evidence: ").append(progress)
                .append("/").append(threshold);
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

    private static String safeRuleKey(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 260) text = text.substring(0, 260);
        return text.replaceAll("[^A-Za-z0-9:_*|= .>-]", "");
    }
}
