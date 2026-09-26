package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts verified low-risk episodes into abstract procedure memory.
 *
 * Task completion is intentionally not enough to learn. Only domain-specific
 * terminal evidence can add an independent success vote. Recipe replay can
 * refresh supporting evidence but can never self-promote a memory.
 */
final class RefinedMemoryRefinery {
    private final RefinedMemoryStore store;

    RefinedMemoryRefinery(RefinedMemoryStore store) {
        this.store = store;
    }

    RefinedMemoryStore.Entry observeCompletedTask(
            String goalText,
            String goalIntent,
            String primaryPackage,
            String startPackage,
            List<RefinedMemoryPolicy.Step> steps,
            String taskState,
            String completionEvidence,
            boolean verified,
            boolean mediaPlaybackActive,
            String taskId) {
        if (store == null) return null;
        String scope = safe(goalIntent);
        if (scope.isEmpty()) scope = GoalIntentKey.derive(goalText);
        if (!RefinedMemoryPolicy.isEligibleScope(scope)) return null;

        RefinedMemoryEvidencePolicy.Verdict verdict =
                RefinedMemoryEvidencePolicy.normalTaskVerdict(
                        scope,
                        taskState,
                        completionEvidence,
                        verified,
                        mediaPlaybackActive);
        if (verdict
                != RefinedMemoryEvidencePolicy.Verdict
                        .INDEPENDENT_SUCCESS) {
            return null;
        }

        String pattern =
                RefinedMemoryPolicy.patternFor(
                        scope, goalText, steps);
        String guidance =
                RefinedMemoryPolicy.guidanceFor(
                        scope, pattern);
        if (pattern.isEmpty() || guidance.isEmpty()) return null;

        return store.observeIndependentSuccess(
                scope,
                safe(primaryPackage),
                safe(startPackage),
                pattern,
                guidance,
                safe(taskId));
    }

    RefinedMemoryStore.Entry observeRecipeOutcome(
            JSONObject recipe,
            JSONObject runtimeResult,
            boolean runtimeSuccess,
            String taskId) {
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

        JSONObject result =
                runtimeResult == null
                        ? new JSONObject()
                        : runtimeResult;
        RefinedMemoryEvidencePolicy.Verdict verdict =
                RefinedMemoryEvidencePolicy.recipeReplayVerdict(
                        runtimeSuccess,
                        scope,
                        result.optString("terminalTaskState", ""),
                        result.optString(
                                "terminalCompletionEvidence", ""),
                        result.optBoolean("terminalVerified", false),
                        result.optBoolean(
                                "terminalMediaPlaybackActive", false));

        String primaryPackage =
                safe(result.optString("terminalPackage", ""));
        if (primaryPackage.isEmpty()) {
            primaryPackage =
                    primaryPackageFromRecipe(
                            recipe.optJSONArray("steps"));
        }
        String startPackage =
                safe(recipe.optString("startPackage", ""));

        if (verdict
                == RefinedMemoryEvidencePolicy.Verdict
                        .SUPPORTING_SUCCESS) {
            return store.observeSupportingReplay(
                    scope,
                    primaryPackage,
                    startPackage,
                    pattern,
                    guidance,
                    taskId);
        }
        if (verdict
                == RefinedMemoryEvidencePolicy.Verdict.NEGATIVE) {
            return store.observeFailure(
                    scope,
                    primaryPackage,
                    startPackage,
                    pattern,
                    guidance,
                    taskId,
                    "RECIPE_REPLAY_FAILED");
        }
        return null;
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

    static String primaryPackageFromRecipe(JSONArray steps) {
        if (steps == null) return "";
        HashMap<String, Integer> counts =
                new HashMap<String, Integer>();
        String terminal = "";

        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String tool = safe(step.optString("tool", ""));
            String pkg;
            if ("launch_app".equals(tool)) {
                pkg = safe(step.optString(
                        "expectedAfterPackage", ""));
            } else {
                pkg = safe(step.optString("expectedPackage", ""));
                if (pkg.isEmpty()) {
                    pkg = safe(step.optString(
                            "expectedAfterPackage", ""));
                }
            }
            if (pkg.isEmpty()) continue;
            terminal = pkg;
            Integer value = counts.get(pkg);
            counts.put(pkg, value == null ? 1 : value + 1);
        }

        String best = terminal;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int value = entry.getValue() == null
                    ? 0
                    : entry.getValue();
            if (value > bestCount
                    || (value == bestCount
                        && entry.getKey().equals(terminal))) {
                best = entry.getKey();
                bestCount = value;
            }
        }
        return safe(best);
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
