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

        JSONObject terminalResult = null;
        String terminalPackage = "";

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

            terminalResult = result;
            JSONObject terminalAfter = result.optJSONObject("after");
            if (terminalAfter != null
                    && !terminalAfter.optString("package", "").trim().isEmpty()) {
                terminalPackage =
                        terminalAfter.optString("package", "").trim();
            }

            String expectedAfterPackage =
                    step.optString("expectedAfterPackage", "");
            String expectedAfterStable =
                    step.optString("expectedAfterStableScreen", "");
            if (!expectedAfterPackage.isEmpty()
                    || !expectedAfterStable.isEmpty()) {
                JSONObject after = result.optJSONObject("after");
                String actualAfterPackage = after == null
                        ? "" : after.optString("package", "");
                String actualAfterStable = after == null
                        ? "" : after.optString("stableScreenKey", "");

                if ((!expectedAfterPackage.isEmpty()
                                && !expectedAfterPackage.equals(
                                        actualAfterPackage))
                        || (!expectedAfterStable.isEmpty()
                                && !expectedAfterStable.equals(
                                        actualAfterStable))) {
                    return fallback(
                            "TASK_RECIPE_AFTER_STATE_MISMATCH",
                            i,
                            recipeId);
                }
            }
        }

        String goalIntent =
                GoalIntentKey.derive(
                        recipe.optString("goal", ""));
        String terminalTaskState =
                terminalResult == null
                        ? ""
                        : terminalResult.optString(
                                "taskState", "");
        String terminalCompletionEvidence =
                terminalResult == null
                        ? ""
                        : terminalResult.optString(
                                "completionEvidence", "");
        boolean terminalVerified =
                terminalResult != null
                        && (terminalResult.optBoolean(
                                    "verified", false)
                            || "VERIFIED".equalsIgnoreCase(
                                    terminalResult.optString(
                                            "verificationStatus", ""))
                            || "VERIFIED".equalsIgnoreCase(
                                    terminalResult.optString(
                                            "verification", "")));
        boolean terminalMediaPlaybackActive =
                terminalResult != null
                        && terminalResult.optBoolean(
                                "mediaPlaybackActive", false);
        String terminalVisibleEvidence =
                terminalResult == null
                        ? ""
                        : terminalResult.toString();

        boolean goalTerminalVerified =
                TaskRecipeCompletionPolicy
                        .isGoalTerminalVerified(
                                goalIntent,
                                terminalTaskState,
                                terminalCompletionEvidence,
                                terminalVerified,
                                terminalMediaPlaybackActive,
                                terminalPackage,
                                terminalVisibleEvidence);

        String resolvedTerminalState =
                goalTerminalVerified
                        ? "DONE"
                        : terminalTaskState;
        String resolvedTerminalEvidence =
                terminalCompletionEvidence;
        if (goalTerminalVerified
                && "NAVIGATION:START".equals(goalIntent)) {
            resolvedTerminalEvidence =
                    "MAPS_NAVIGATION_ACTIVE_VERIFIED";
        } else if (goalTerminalVerified
                && resolvedTerminalEvidence.isEmpty()) {
            resolvedTerminalEvidence =
                    "TASK_RECIPE_TERMINAL_VERIFIED";
        }
        boolean resolvedTerminalVerified =
                terminalVerified || goalTerminalVerified;

        JSONObject out = new JSONObject()
                .put("success", true)
                .put("recipeExecutionSuccess", true)
                .put("goalTerminalVerified", goalTerminalVerified)
                .put("fastPath", true)
                .put(
                        "fastPathState",
                        goalTerminalVerified
                                ? "VERIFIED_COMPLETED"
                                : "EXECUTED_NEEDS_VERIFICATION")
                .put("recipeId", recipeId)
                .put("recipeStepCount", steps.length())
                .put(
                        "taskState",
                        goalTerminalVerified
                                ? "DONE"
                                : "EVIDENCE_AVAILABLE")
                .put(
                        "completionEvidence",
                        goalTerminalVerified
                                ? resolvedTerminalEvidence
                                : "TASK_RECIPE_EXECUTED")
                .put(
                        "nextRequirement",
                        goalTerminalVerified
                                ? "NONE"
                                : "INSPECT_UI")
                .put("semanticAction", "TASK_RECIPE")
                .put("resolvedByRuntime", "task_recipe")
                .put(
                        "message",
                        goalTerminalVerified
                                ? "熟悉流程已執行，且最終狀態已驗證。"
                                : "熟悉流程已執行，但尚未證明整個任務完成；請先檢查目前畫面再作結論。");

        out.put("terminalTaskState", resolvedTerminalState)
                .put(
                        "terminalCompletionEvidence",
                        resolvedTerminalEvidence)
                .put(
                        "terminalVerified",
                        resolvedTerminalVerified)
                .put(
                        "terminalMediaPlaybackActive",
                        terminalMediaPlaybackActive);
        if (!terminalPackage.isEmpty()) {
            out.put("terminalPackage", terminalPackage);
        }
        return out;
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
