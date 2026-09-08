package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Persistent deterministic plans recorded by the user. */
final class ShortcutPlanStore {
    static final String ACTION_PREFIX = "runtime_plan:";
    private static final String PREFS = "crew_shortcut_plans";
    private static final String KEY_PLANS = "plans_v1";
    private static final int MAX_PLANS = 100;

    static final class Plan {
        String id = "";
        String trigger = "";
        JSONArray steps = new JSONArray();
        long createdAt;

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try {
                out.put("id", id)
                        .put("trigger", trigger)
                        .put("steps", steps)
                        .put("createdAt", createdAt);
            } catch (Exception ignored) {}
            return out;
        }

        static Plan fromJson(JSONObject source) {
            Plan out = new Plan();
            if (source == null) return out;
            out.id = source.optString("id", "");
            out.trigger = source.optString("trigger", "");
            JSONArray sourceSteps = source.optJSONArray("steps");
            if (sourceSteps != null) {
                try { out.steps = new JSONArray(sourceSteps.toString()); }
                catch (Exception ignored) {}
            }
            out.createdAt = source.optLong("createdAt", 0L);
            return out;
        }
    }

    private final SharedPreferences prefs;

    ShortcutPlanStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Plan save(String trigger, JSONArray steps) {
        String cleanTrigger = trigger == null ? "" : trigger.trim();
        if (cleanTrigger.length() < 2 || steps == null || steps.length() < 1) return null;

        JSONArray plans = loadArray();
        Plan plan = new Plan();
        plan.id = UUID.randomUUID().toString();
        plan.trigger = cleanTrigger;
        plan.createdAt = System.currentTimeMillis();
        try { plan.steps = new JSONArray(steps.toString()); }
        catch (Exception error) { return null; }

        JSONArray next = new JSONArray();
        next.put(plan.toJson());
        for (int i = 0; i < plans.length() && next.length() < MAX_PLANS; i++) {
            JSONObject existing = plans.optJSONObject(i);
            if (existing != null) next.put(existing);
        }
        prefs.edit().putString(KEY_PLANS, next.toString()).apply();
        return plan;
    }

    synchronized Plan get(String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray plans = loadArray();
        for (int i = 0; i < plans.length(); i++) {
            Plan plan = Plan.fromJson(plans.optJSONObject(i));
            if (id.equals(plan.id)) return plan;
        }
        return null;
    }

    synchronized void delete(String id) {
        if (id == null || id.isEmpty()) return;
        JSONArray plans = loadArray();
        JSONArray next = new JSONArray();
        for (int i = 0; i < plans.length(); i++) {
            JSONObject raw = plans.optJSONObject(i);
            Plan plan = Plan.fromJson(raw);
            if (!id.equals(plan.id) && raw != null) next.put(raw);
        }
        prefs.edit().putString(KEY_PLANS, next.toString()).apply();
    }

    synchronized void clearAll() {
        prefs.edit().putString(KEY_PLANS, "[]").apply();
    }

    static boolean isPlanAction(String action) {
        return action != null && action.startsWith(ACTION_PREFIX)
                && action.length() > ACTION_PREFIX.length();
    }

    static String planIdFromAction(String action) {
        return isPlanAction(action) ? action.substring(ACTION_PREFIX.length()) : "";
    }

    static String actionForPlan(String id) {
        return ACTION_PREFIX + (id == null ? "" : id);
    }

    static String describeSteps(JSONArray steps) {
        if (steps == null || steps.length() == 0) return "沒有可執行步驟";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            if (out.length() > 0) out.append("\n");
            String type = step.optString("type", "");
            if ("OPEN_APP".equals(type)) {
                out.append(i + 1).append(". 開啟 ")
                        .append(step.optString("label",
                                step.optString("packageName", "App")));
            } else if ("TAP_ELEMENT".equals(type)) {
                out.append(i + 1).append(". 點擊 ")
                        .append(step.optString("summary", "畫面元素"));
            } else {
                out.append(i + 1).append(". ").append(type);
            }
        }
        return out.toString();
    }

    private JSONArray loadArray() {
        try { return new JSONArray(prefs.getString(KEY_PLANS, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
}
