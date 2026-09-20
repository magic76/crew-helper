package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Local-only storage for successful low-risk deterministic task recipes. */
final class TaskRecipeStore {
    static final class Match {
        final String id;
        final String goal;
        final String startPackage;
        final int stepCount;

        Match(String id, String goal, String startPackage, int stepCount) {
            this.id = safe(id);
            this.goal = safe(goal);
            this.startPackage = safe(startPackage);
            this.stepCount = stepCount;
        }
    }

    private static final String PREFS = "crew_task_recipes";
    private static final String KEY_DATA = "recipes_v1";
    private static final int MAX_RECIPES = 32;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    TaskRecipeStore(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        prefs = app == null ? null
                : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    JSONObject rememberSuccessful(
            String goal,
            String startPackage,
            JSONArray safeSteps) {
        synchronized (LOCK) {
            String goalKey = TaskRecipePolicy.canonicalGoal(goal);
            if (prefs == null
                    || goalKey.length() < 3
                    || safeSteps == null
                    || safeSteps.length() < 2
                    || safeSteps.length() > TaskRecipePolicy.MAX_STEPS) {
                return failure("RECIPE_NOT_ELIGIBLE");
            }

            JSONArray recipes = loadLocked();
            JSONObject existing = null;
            for (int i = 0; i < recipes.length(); i++) {
                JSONObject item = recipes.optJSONObject(i);
                if (item == null) continue;
                if (goalKey.equals(item.optString("goalKey", ""))
                        && safe(startPackage).equals(
                                item.optString("startPackage", ""))) {
                    existing = item;
                    break;
                }
            }

            long now = System.currentTimeMillis();
            if (existing == null) {
                existing = new JSONObject();
                put(existing, "id", UUID.randomUUID().toString());
                put(existing, "createdAt", now);
                put(existing, "runSuccessCount", 0);
                put(existing, "runFailureCount", 0);
                put(existing, "enabled", true);
                recipes.put(existing);
            }

            put(existing, "goal", clip(goal, 240));
            put(existing, "goalKey", goalKey);
            put(existing, "startPackage", safe(startPackage));
            put(existing, "steps", copyArray(safeSteps));
            put(existing, "updatedAt", now);
            put(existing, "learnCount",
                    existing.optInt("learnCount", 0) + 1);

            recipes = trimOldest(recipes);
            saveLocked(recipes);

            JSONObject out = success("RECIPE_SAVED");
            put(out, "recipeId", existing.optString("id", ""));
            put(out, "stepCount", safeSteps.length());
            put(out, "recipeCount", recipes.length());
            return out;
        }
    }

    Match findMatching(String goal, String currentPackage) {
        synchronized (LOCK) {
            if (prefs == null) return null;
            String goalKey = TaskRecipePolicy.canonicalGoal(goal);
            if (goalKey.length() < 3) return null;

            JSONArray recipes = loadLocked();
            JSONObject best = null;
            long bestAt = -1L;
            for (int i = 0; i < recipes.length(); i++) {
                JSONObject item = recipes.optJSONObject(i);
                if (item == null || !item.optBoolean("enabled", true)) continue;
                if (!goalKey.equals(item.optString("goalKey", ""))) continue;

                JSONArray steps = item.optJSONArray("steps");
                if (steps == null || steps.length() < 2) continue;
                String firstTool = firstTool(steps);
                String startPackage = safe(item.optString("startPackage", ""));
                if (!"launch_app".equals(firstTool)
                        && !startPackage.isEmpty()
                        && !startPackage.equals(safe(currentPackage))) {
                    continue;
                }

                long updatedAt = item.optLong("updatedAt", 0L);
                if (best == null || updatedAt > bestAt) {
                    best = item;
                    bestAt = updatedAt;
                }
            }
            if (best == null) return null;
            JSONArray steps = best.optJSONArray("steps");
            return new Match(
                    best.optString("id", ""),
                    best.optString("goal", ""),
                    best.optString("startPackage", ""),
                    steps == null ? 0 : steps.length());
        }
    }

    JSONObject getRecipe(String recipeId) {
        synchronized (LOCK) {
            if (prefs == null) return null;
            String id = safe(recipeId);
            JSONArray recipes = loadLocked();
            for (int i = 0; i < recipes.length(); i++) {
                JSONObject item = recipes.optJSONObject(i);
                if (item != null && id.equals(item.optString("id", ""))) {
                    return copyObject(item);
                }
            }
            return null;
        }
    }

    void recordRun(String recipeId, boolean success) {
        synchronized (LOCK) {
            if (prefs == null) return;
            String id = safe(recipeId);
            JSONArray recipes = loadLocked();
            for (int i = 0; i < recipes.length(); i++) {
                JSONObject item = recipes.optJSONObject(i);
                if (item == null || !id.equals(item.optString("id", ""))) continue;
                String key = success ? "runSuccessCount" : "runFailureCount";
                put(item, key, item.optInt(key, 0) + 1);
                put(item, "lastRunAt", System.currentTimeMillis());
                saveLocked(recipes);
                return;
            }
        }
    }

    int count() {
        synchronized (LOCK) {
            return prefs == null ? 0 : loadLocked().length();
        }
    }

    private JSONArray trimOldest(JSONArray source) {
        JSONArray out = copyArray(source);
        while (out.length() > MAX_RECIPES) {
            int oldest = 0;
            long oldestAt = Long.MAX_VALUE;
            for (int i = 0; i < out.length(); i++) {
                JSONObject item = out.optJSONObject(i);
                long at = item == null ? 0L : item.optLong("updatedAt", 0L);
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = i;
                }
            }
            JSONArray next = new JSONArray();
            for (int i = 0; i < out.length(); i++) {
                if (i != oldest) next.put(out.opt(i));
            }
            out = next;
        }
        return out;
    }

    private JSONArray loadLocked() {
        if (prefs == null) return new JSONArray();
        String raw = prefs.getString(KEY_DATA, "[]");
        try { return new JSONArray(raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void saveLocked(JSONArray data) {
        if (prefs != null) {
            prefs.edit().putString(KEY_DATA, data.toString()).apply();
        }
    }

    private static String firstTool(JSONArray steps) {
        JSONObject first = steps == null ? null : steps.optJSONObject(0);
        return first == null ? "" : first.optString("tool", "");
    }

    private static JSONArray copyArray(JSONArray source) {
        if (source == null) return new JSONArray();
        try { return new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject copyObject(JSONObject source) {
        if (source == null) return null;
        try { return new JSONObject(source.toString()); }
        catch (Exception ignored) { return null; }
    }

    private static JSONObject success(String state) {
        JSONObject out = new JSONObject();
        put(out, "success", true);
        put(out, "state", state);
        return out;
    }

    private static JSONObject failure(String error) {
        JSONObject out = new JSONObject();
        put(out, "success", false);
        put(out, "error", error);
        return out;
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception ignored) {}
    }

    private static String clip(String value, int max) {
        String text = safe(value).replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
