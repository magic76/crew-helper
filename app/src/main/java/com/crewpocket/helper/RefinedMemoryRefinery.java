package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts successful low-risk episodes into abstract procedure memory.
 *
 * This class never persists raw query text or UI labels. Those values exist
 * only long enough for RefinedMemoryPolicy to classify semantic step tokens.
 */
final class RefinedMemoryRefinery {
    private final RefinedMemoryStore store;

    RefinedMemoryRefinery(RefinedMemoryStore store) {
        this.store = store;
    }

    RefinedMemoryStore.Entry observeCompletedTask(
            String goalText,
            String goalIntent,
            String packageName,
            List<RefinedMemoryPolicy.Step> steps) {
        if (store == null) return null;
        String scope = safe(goalIntent);
        if (scope.isEmpty()) scope = GoalIntentKey.derive(goalText);
        if (!RefinedMemoryPolicy.isEligibleScope(scope)) return null;

        String pattern =
                RefinedMemoryPolicy.patternFor(
                        scope, goalText, steps);
        String guidance =
                RefinedMemoryPolicy.guidanceFor(
                        scope, pattern);
        if (pattern.isEmpty() || guidance.isEmpty()) return null;

        return store.observeProcedure(
                scope,
                packageName,
                pattern,
                guidance,
                true);
    }

    RefinedMemoryStore.Entry observeRecipeOutcome(
            JSONObject recipe,
            boolean success) {
        if (store == null || recipe == null) return null;
        String goal = safe(recipe.optString("goal", ""));
        String scope = GoalIntentKey.derive(goal);
        if (!RefinedMemoryPolicy.isEligibleScope(scope)) return null;

        List<RefinedMemoryPolicy.Step> steps =
                stepsFromRecipe(recipe.optJSONArray("steps"));
        String pattern =
                RefinedMemoryPolicy.patternFor(
                        scope, goal, steps);
        String guidance =
                RefinedMemoryPolicy.guidanceFor(
                        scope, pattern);
        if (pattern.isEmpty() || guidance.isEmpty()) return null;

        return store.observeProcedure(
                scope,
                safe(recipe.optString("startPackage", "")),
                pattern,
                guidance,
                success);
    }

    static List<RefinedMemoryPolicy.Step> stepsFromRecipe(
            JSONArray steps) {
        ArrayList<RefinedMemoryPolicy.Step> out =
                new ArrayList<RefinedMemoryPolicy.Step>();
        if (steps == null) return out;

        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String tool = safe(step.optString("tool", ""));
            JSONObject args = step.optJSONObject("args");
            out.add(new RefinedMemoryPolicy.Step(
                    tool,
                    targetFromArgs(tool, args)));
        }
        return out;
    }

    static String targetFromArgs(
            String tool,
            JSONObject args) {
        if (args == null) return "";
        String name = safe(tool);
        if ("tap_screen".equals(name)
                || "tap_element".equals(name)) {
            String label = safe(args.optString("label", ""));
            if (!label.isEmpty()) return label;
            return safe(args.optString("semantic_target", ""));
        }
        if ("press_key".equals(name)) {
            return safe(args.optString("key", ""));
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
