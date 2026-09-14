package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Stores post-task reflection candidates separately from active App Playbooks.
 *
 * A model reflection never becomes executable knowledge immediately. Two
 * compatible high-confidence observations are required before promotion into
 * AppPlaybookStore. Later contradictory reflections can demote/remove it.
 */
final class ReflectionLessonStore {
    private static final String PREFS = "crew_reflection_learning";
    private static final String KEY_ITEMS = "lessons_v1";
    private static final int MAX_ITEMS = 80;
    private static final Object LOCK = new Object();

    static final String STATE_CANDIDATE = "CANDIDATE";
    static final String STATE_VERIFIED = "VERIFIED";
    static final String STATE_SUSPECT = "SUSPECT";

    private final Context context;
    private final SharedPreferences prefs;
    private final AppPlaybookStore playbookStore;

    ReflectionLessonStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        this.prefs = this.context == null
                ? null
                : this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.playbookStore = new AppPlaybookStore(this.context);
    }

    JSONObject record(String packageName, String appLabel, JSONObject reflection) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                if (prefs == null || reflection == null
                        || !reflection.optBoolean("should_remember", false)) {
                    return out.put("stored", false).put("reason", "NOT_REMEMBERED");
                }

                String pkg = cleanPackage(packageName);
                String goal = ReflectionLearningPolicy.normalizeGoalPattern(
                        reflection.optString("goal_pattern", ""));
                String lesson = collapse(reflection.optString("lesson", ""));
                double confidence = Math.max(0d,
                        Math.min(1d, reflection.optDouble("confidence", 0d)));

                if (pkg.isEmpty() || !ReflectionLearningPolicy.isSafeCandidate(
                        goal, lesson, confidence)) {
                    return out.put("stored", false).put("reason", "POLICY_REJECTED");
                }

                JSONArray items = load();
                JSONObject existing = find(items, pkg, goal);
                long now = System.currentTimeMillis();
                String state;
                int confirmations;
                int contradictions;
                double average;
                String effectiveLesson;
                String ruleId = "";

                if (existing == null) {
                    existing = new JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("package", pkg)
                            .put("app", collapse(appLabel))
                            .put("goalPattern", goal)
                            .put("lesson", lesson)
                            .put("confirmations", 1)
                            .put("contradictions", 0)
                            .put("avgConfidence", confidence)
                            .put("state", STATE_CANDIDATE)
                            .put("createdAt", now)
                            .put("updatedAt", now);
                    items.put(existing);
                } else {
                    state = existing.optString("state", STATE_CANDIDATE);
                    confirmations = Math.max(1, existing.optInt("confirmations", 1));
                    contradictions = Math.max(0, existing.optInt("contradictions", 0));
                    average = existing.optDouble("avgConfidence", confidence);
                    effectiveLesson = existing.optString("lesson", "");
                    ruleId = existing.optString("playbookRuleId", "");

                    boolean compatible = ReflectionLearningPolicy.lessonsCompatible(
                            effectiveLesson, lesson);
                    if (STATE_VERIFIED.equals(state) || STATE_SUSPECT.equals(state)) {
                        if (compatible) {
                            confirmations++;
                            average = rollingAverage(average, confirmations - 1, confidence);
                            contradictions = 0;
                            state = STATE_VERIFIED;
                            existing.put("lesson", lesson);
                        } else {
                            contradictions++;
                            state = STATE_SUSPECT;
                            if (contradictions >= 2) {
                                if (!ruleId.isEmpty()) playbookStore.delete(pkg, ruleId);
                                confirmations = 1;
                                contradictions = 0;
                                average = confidence;
                                state = STATE_CANDIDATE;
                                ruleId = "";
                                existing.put("lesson", lesson);
                            }
                        }
                    } else {
                        if (compatible) {
                            confirmations++;
                            average = rollingAverage(average, confirmations - 1, confidence);
                            existing.put("lesson", lesson);
                        } else {
                            confirmations = 1;
                            average = confidence;
                            existing.put("lesson", lesson);
                        }
                    }

                    existing.put("confirmations", confirmations)
                            .put("contradictions", contradictions)
                            .put("avgConfidence", average)
                            .put("state", state)
                            .put("updatedAt", now);
                    if (ruleId.isEmpty()) existing.remove("playbookRuleId");
                }

                state = existing.optString("state", STATE_CANDIDATE);
                confirmations = existing.optInt("confirmations", 1);
                average = existing.optDouble("avgConfidence", confidence);

                if (!STATE_VERIFIED.equals(state)
                        && confirmations >= ReflectionLearningPolicy.CONFIRMATIONS_TO_VERIFY
                        && average >= ReflectionLearningPolicy.MIN_VERIFIED_CONFIDENCE) {
                    JSONObject promoted = playbookStore.remember(
                            pkg,
                            collapse(appLabel),
                            "Auto learned · " + goal,
                            existing.optString("lesson", lesson),
                            AppPlaybookStore.SOURCE_REFLECTION);
                    if (promoted.optBoolean("success", false)) {
                        existing.put("state", STATE_VERIFIED)
                                .put("playbookRuleId", promoted.optString("ruleId", ""))
                                .put("verifiedAt", now)
                                .put("contradictions", 0);
                        state = STATE_VERIFIED;
                    }
                }

                save(trim(items));
                return out.put("stored", true)
                        .put("state", state)
                        .put("goalPattern", goal)
                        .put("confirmations", existing.optInt("confirmations", 1))
                        .put("avgConfidence", existing.optDouble("avgConfidence", confidence));
            } catch (Exception error) {
                try {
                    return out.put("stored", false)
                            .put("reason", "STORE_ERROR");
                } catch (Exception ignored) {
                    return new JSONObject();
                }
            }
        }
    }

    private JSONArray load() {
        if (prefs == null) return new JSONArray();
        try { return new JSONArray(prefs.getString(KEY_ITEMS, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void save(JSONArray items) {
        if (prefs == null) return;
        prefs.edit().putString(KEY_ITEMS, items.toString()).apply();
    }

    private static JSONObject find(JSONArray items, String pkg, String goal) {
        if (items == null) return null;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (pkg.equals(item.optString("package", ""))
                    && goal.equals(item.optString("goalPattern", ""))) {
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

    private static double rollingAverage(double previous, int previousCount, double next) {
        if (previousCount <= 0) return next;
        return ((previous * previousCount) + next) / (previousCount + 1d);
    }

    private static String cleanPackage(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 180) text = text.substring(0, 180);
        return text.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String collapse(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() > 220 ? text.substring(0, 220) : text;
    }
}
