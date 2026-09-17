package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores qualified Crew Experience rules separately from active App Playbooks.
 *
 * Runtime owns rule identity, trigger eligibility, and evidence confidence.
 * Gemini only compresses qualified evidence into a human-readable lesson. Two
 * compatible confirmations are required before App Playbook promotion.
 */
final class ReflectionLessonStore {
    private static final String PREFS = "crew_reflection_learning";
    private static final String KEY_ITEMS = "rules_v2";
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

    JSONObject recordRules(String packageName,
                           String appLabel,
                           List<ReflectionRuleEvidence.Candidate> candidates,
                           JSONObject reflection) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                if (prefs == null || reflection == null || candidates == null
                        || candidates.isEmpty()) {
                    return out.put("storedCount", 0).put("reason", "NOT_REMEMBERED");
                }

                JSONArray selected = reflection.optJSONArray("rules");
                if (selected == null || selected.length() == 0) {
                    return out.put("storedCount", 0).put("reason", "NOT_REMEMBERED");
                }

                String pkg = cleanPackage(packageName);
                if (pkg.isEmpty()) {
                    return out.put("storedCount", 0).put("reason", "POLICY_REJECTED");
                }

                JSONArray items = load();
                HashSet<String> processed = new HashSet<String>();
                int storedCount = 0;
                boolean policyRejected = false;
                String aggregateState = "";

                for (int i = 0; i < selected.length() && storedCount < 2; i++) {
                    JSONObject choice = selected.optJSONObject(i);
                    if (choice == null) continue;

                    String candidateId = collapse(choice.optString("candidate_id", ""));
                    if (candidateId.isEmpty() || processed.contains(candidateId)) continue;
                    processed.add(candidateId);

                    ReflectionRuleEvidence.Candidate candidate =
                            ReflectionRuleEvidence.findById(candidates, candidateId);
                    if (candidate == null) continue;

                    String lesson = collapse(choice.optString("lesson", ""));
                    double confidence = experienceConfidence(candidate);
                    if (!ReflectionLearningPolicy.isSafeRuleLesson(lesson, confidence)) {
                        policyRejected = true;
                        continue;
                    }

                    JSONObject item = recordOne(
                            items,
                            pkg,
                            collapse(appLabel),
                            candidate,
                            lesson,
                            confidence);
                    if (item == null) continue;

                    storedCount++;
                    aggregateState = aggregateState(
                            aggregateState,
                            item.optString("state", STATE_CANDIDATE));
                }

                if (storedCount == 0) {
                    return out.put("storedCount", 0)
                            .put("reason", policyRejected
                                    ? "POLICY_REJECTED" : "NOT_REMEMBERED");
                }

                save(trim(items));
                return out.put("storedCount", storedCount)
                        .put("state", aggregateState.isEmpty()
                                ? STATE_CANDIDATE : aggregateState);
            } catch (Exception error) {
                try {
                    return out.put("storedCount", 0).put("reason", "STORE_ERROR");
                } catch (Exception ignored) {
                    return new JSONObject();
                }
            }
        }
    }

    private JSONObject recordOne(JSONArray items,
                                 String pkg,
                                 String appLabel,
                                 ReflectionRuleEvidence.Candidate candidate,
                                 String lesson,
                                 double confidence) throws Exception {
        JSONObject existing = find(items, pkg, candidate.ruleKey);
        long now = System.currentTimeMillis();

        if (existing == null) {
            existing = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("package", pkg)
                    .put("app", appLabel)
                    .put("ruleKey", candidate.ruleKey)
                    .put("kind", candidate.kind)
                    .put("scope", candidate.scope)
                    .put("condition", candidate.condition)
                    .put("response", candidate.response)
                    .put("lesson", lesson)
                    .put("confirmations", 1)
                    .put("contradictions", 0)
                    .put("avgConfidence", confidence)
                    .put("state", STATE_CANDIDATE)
                    .put("createdAt", now)
                    .put("updatedAt", now);
            items.put(existing);
        } else {
            String state = existing.optString("state", STATE_CANDIDATE);
            int confirmations = Math.max(1, existing.optInt("confirmations", 1));
            int contradictions = Math.max(0, existing.optInt("contradictions", 0));
            double average = existing.optDouble("avgConfidence", confidence);
            String effectiveLesson = existing.optString("lesson", "");
            String ruleId = existing.optString("playbookRuleId", "");
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

            existing.put("kind", candidate.kind)
                    .put("scope", candidate.scope)
                    .put("condition", candidate.condition)
                    .put("response", candidate.response)
                    .put("confirmations", confirmations)
                    .put("contradictions", contradictions)
                    .put("avgConfidence", average)
                    .put("state", state)
                    .put("updatedAt", now);
            if (ruleId.isEmpty()) existing.remove("playbookRuleId");
        }

        String state = existing.optString("state", STATE_CANDIDATE);
        int confirmations = existing.optInt("confirmations", 1);
        double average = existing.optDouble("avgConfidence", confidence);
        if (!STATE_VERIFIED.equals(state)
                && confirmations >= ReflectionLearningPolicy.CONFIRMATIONS_TO_VERIFY
                && average >= ReflectionLearningPolicy.MIN_VERIFIED_CONFIDENCE) {
            String title = "Crew Experience · " + candidate.scope
                    + " · " + candidate.condition + " -> " + candidate.response;
            JSONObject promoted = playbookStore.remember(
                    pkg,
                    appLabel,
                    title,
                    existing.optString("lesson", lesson),
                    AppPlaybookStore.SOURCE_MANUAL);
            if (promoted.optBoolean("success", false)) {
                existing.put("state", STATE_VERIFIED)
                        .put("playbookRuleId", promoted.optString("ruleId", ""))
                        .put("verifiedAt", now)
                        .put("contradictions", 0);
            }
        }
        return existing;
    }

    /** Human-readable local-only view of sanitized Crew Experience rules. */
    static String buildReport(Context context) {
        JSONArray items = new JSONArray();
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                items = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            } catch (Exception ignored) {}
        }

        StringBuilder out = new StringBuilder();
        out.append("Crew Experience\n");
        if (items.length() == 0) {
            out.append("No learned experiences yet. Normal successful tasks stay quiet; Crew learns from proven recovery or repeated successful patterns.");
            return out.toString();
        }

        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String app = collapse(item.optString("app", ""));
            String pkg = cleanPackage(item.optString("package", ""));
            String state = item.optString("state", STATE_CANDIDATE);
            String kind = collapse(item.optString("kind", "LEGACY"));
            int confirmations = Math.max(0, item.optInt("confirmations", 0));
            int contradictions = Math.max(0, item.optInt("contradictions", 0));
            double confidence = Math.max(0d,
                    Math.min(1d, item.optDouble("avgConfidence", 0d)));

            if (out.length() > 0) out.append("\n\n");
            out.append(app.isEmpty() ? pkg : app);
            if (!app.isEmpty() && !pkg.isEmpty()) out.append(" · ").append(pkg);
            out.append("\n")
                    .append(state)
                    .append(" · ").append(kind)
                    .append(" · confirmations ")
                    .append(confirmations)
                    .append("/")
                    .append(ReflectionLearningPolicy.CONFIRMATIONS_TO_VERIFY)
                    .append(" · confidence ")
                    .append(String.format(Locale.US, "%.2f", confidence));
            if (contradictions > 0) {
                out.append(" · contradictions ").append(contradictions);
            }
            out.append("\nRule: ").append(collapse(item.optString("scope", "")))
                    .append("\nWhen: ").append(collapse(item.optString("condition", "")))
                    .append("\nDo: ").append(collapse(item.optString("response", "")))
                    .append("\nLesson: ")
                    .append(collapse(item.optString("lesson", "")));

            long updatedAt = item.optLong("updatedAt", 0L);
            if (updatedAt > 0L) {
                out.append("\nUpdated: ").append(formatTime(updatedAt));
            }
            if (STATE_VERIFIED.equals(state)) {
                out.append("\nPromoted to App Playbook");
            } else if (STATE_CANDIDATE.equals(state)) {
                int remaining = Math.max(0,
                        ReflectionLearningPolicy.CONFIRMATIONS_TO_VERIFY - confirmations);
                out.append("\nNeeds ").append(remaining)
                        .append(" more compatible confirmation")
                        .append(remaining == 1 ? "" : "s");
            }
        }
        return out.toString().trim();
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

    private static String aggregateState(String current, String next) {
        if (STATE_VERIFIED.equals(current) || STATE_VERIFIED.equals(next)) {
            return STATE_VERIFIED;
        }
        if (STATE_SUSPECT.equals(current) || STATE_SUSPECT.equals(next)) {
            return STATE_SUSPECT;
        }
        return STATE_CANDIDATE;
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

    private static double experienceConfidence(ReflectionRuleEvidence.Candidate candidate) {
        return candidate != null
                && ReflectionRuleEvidence.KIND_RECOVERY.equals(candidate.kind)
                ? 0.86d : 0.80d;
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

    private static String formatTime(long at) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(at));
        } catch (Exception ignored) {
            return String.valueOf(at);
        }
    }
}
