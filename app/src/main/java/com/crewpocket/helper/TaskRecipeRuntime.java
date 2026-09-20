package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Executes one previously verified low-risk recipe without model round-trips. */
final class TaskRecipeRuntime {
    interface Host {
        boolean isCurrentIntent();
        JSONObject observeCurrentScreen() throws Exception;
        JSONObject executeStep(String tool, JSONObject args) throws Exception;
    }

    private TaskRecipeRuntime() {}

    static JSONObject run(JSONObject recipe, Host host) throws Exception {
        if (recipe == null || host == null) {
            return fallback("TASK_RECIPE_UNAVAILABLE", -1, "");
        }
        String recipeId = recipe.optString("id", "");
        JSONArray steps = recipe.optJSONArray("steps");
        if (steps == null
                || steps.length() < 2
                || steps.length() > TaskRecipePolicy.MAX_STEPS) {
            return fallback("TASK_RECIPE_INVALID", -1, recipeId);
        }

        for (int i = 0; i < steps.length(); i++) {
            if (!host.isCurrentIntent()) {
                return fallback("TASK_RECIPE_CANCELLED", i, recipeId);
            }

            JSONObject step = steps.optJSONObject(i);
            String tool = step == null ? "" : step.optString("tool", "");
            JSONObject args = step == null ? null : step.optJSONObject("args");
            if (!TaskRecipePolicy.isAllowedTool(tool)) {
                return fallback("TASK_RECIPE_UNSAFE_STEP", i, recipeId);
            }

            if ("tap_screen".equals(tool)) {
                String tapTarget = safe(args == null ? "" :
                        args.optString("label", args.optString("id", "")));
                if (TaskRecipePolicy.isUnsafeTapTarget(tapTarget)) {
                    return fallback("TASK_RECIPE_UNSAFE_TAP", i, recipeId);
                }
            }

            if (TaskRecipePolicy.needsScreenGuard(tool)) {
                JSONObject screen = host.observeCurrentScreen();
                String expectedPackage = step.optString("expectedPackage", "");
                String expectedStable = step.optString("expectedStableScreen", "");
                String actualPackage = screen == null ? ""
                        : screen.optString("package", "");
                String actualStable = screen == null ? ""
                        : screen.optString("stableScreenKey", "");

                if (!expectedPackage.isEmpty()
                        && !expectedPackage.equals(actualPackage)) {
                    return fallback("TASK_RECIPE_PACKAGE_MISMATCH", i, recipeId);
                }
                if (!expectedStable.isEmpty()
                        && (actualStable.isEmpty()
                            || !expectedStable.equals(actualStable))) {
                    return fallback("TASK_RECIPE_SCREEN_MISMATCH", i, recipeId);
                }
            }

            JSONObject result = host.executeStep(
                    tool,
                    args == null ? new JSONObject() : new JSONObject(args.toString()));
            if (result == null || !result.optBoolean("success", false)) {
                return fallback(
                        result == null
                                ? "TASK_RECIPE_STEP_FAILED"
                                : result.optString("error", "TASK_RECIPE_STEP_FAILED"),
                        i,
                        recipeId);
            }

            String taskState = result.optString("taskState", "");
            String searchTransaction = result.optString("searchTransaction", "");
            String verification = result.optString("verificationStatus",
                    result.optString("verification", ""));
            if ("WAITING_USER".equals(taskState)
                    || "BLOCKED".equals(taskState)
                    || "PENDING_RESULTS".equals(searchTransaction)
                    || "PENDING".equals(verification)) {
                return fallback("TASK_RECIPE_NEEDS_MODEL", i, recipeId);
            }
        }

        return new JSONObject()
                .put("success", true)
                .put("fastPath", true)
                .put("fastPathState", "COMPLETED")
                .put("recipeId", recipeId)
                .put("recipeStepCount", steps.length())
                .put("taskState", "EVIDENCE_AVAILABLE")
                .put("completionEvidence", "TASK_RECIPE_COMPLETED")
                .put("semanticAction", "TASK_RECIPE")
                .put("resolvedByRuntime", "task_recipe")
                .put("message", "已用熟悉流程完成；直接簡短回覆完成，不要再呼叫工具。");
    }

    private static JSONObject fallback(
            String error,
            int failedStep,
            String recipeId) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("fastPath", true)
                .put("fastPathState", "FALLBACK")
                .put("recipeId", safe(recipeId))
                .put("failedStep", failedStep)
                .put("error", safe(error))
                .put("semanticAction", "TASK_RECIPE")
                .put("resolvedByRuntime", "task_recipe")
                .put("message", "熟悉流程與目前畫面不完全一致；改回一般方式繼續，不要重跑 Recipe。");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
