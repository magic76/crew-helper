package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/** Compact in-memory audit record. Raw payloads deliberately never enter transcripts. */
final class AgentTaskRecord {
    final String taskId;
    String goalId = "";
    int goalTaskIndex = 0;
    long intentGeneration = -1L;
    final long startedAt = System.currentTimeMillis();
    final ArrayList<String> stepsSummary = new ArrayList<String>();
    final ArrayList<JSONObject> stepDiagnostics = new ArrayList<JSONObject>();
    final ArrayList<JSONObject> recipeSteps = new ArrayList<JSONObject>();
    String recipeGoal = "";
    String recipeStartPackage = "";
    boolean recipeEligible = true;
    String recipeIneligibleReason = "";
    final HashMap<String, Integer> toolCounts = new HashMap<String, Integer>();
    int steps;
    int mutationActions;
    int observationActions;
    int consecutiveVisualObservations;
    int consecutiveMutationFailures;
    int stabilityBlocks;
    boolean requireObservationAfterFailure;
    String lastFailedMutationSignature = "";
    String failedMutationScreenFingerprint = "";
    String lastSignature = "";
    String status = "";
    String blockedReason;
    String endReason = "";
    String finalReply = "";
    boolean awaitingModel;
    boolean watchdogPrompted;
    boolean userVisibleReplyProducedSinceLastAction;
    int finalSpeechRetryCount;
    String lastToolName = "";
    String lastTaskState = "";
    String lastCompletionEvidence = "";
    long lastSuccessfulMutationAtMs;
    String retryIntentFamily = "";
    int prematureModelReplies;
    boolean requiresPostActionInspection;
    boolean postActionInspectionPrompted;
    boolean cancelled;
    String cancelCategory = "";
    boolean suspended;
    String suspensionReason = "";
    long suspendedAtMs;
    long accumulatedSuspendedMs;
    boolean finished;

    AgentTaskRecord(String id) { taskId = id; }

    void suspendForExternalWait(String reason) {
        if (finished || cancelled || suspended) return;
        suspended = true;
        suspensionReason = safe(reason);
        suspendedAtMs = System.currentTimeMillis();
        awaitingModel = false;
        watchdogPrompted = false;
        userVisibleReplyProducedSinceLastAction = false;
        finalSpeechRetryCount = 0;
    }

    void resumeFromExternalWait(String status) {
        if (finished || cancelled || !suspended) return;
        long now = System.currentTimeMillis();
        accumulatedSuspendedMs =
                AgentTaskLifecycleClock.accumulatedAfterResume(
                        accumulatedSuspendedMs,
                        suspendedAtMs,
                        now);
        suspended = false;
        suspendedAtMs = 0L;
        suspensionReason = "";
        awaitingModel = true;
        watchdogPrompted = false;
        userVisibleReplyProducedSinceLastAction = false;
        finalSpeechRetryCount = 0;
        this.status = safe(status);
    }

    long effectiveStartedAt(long nowMs) {
        return AgentTaskLifecycleClock.effectiveStartedAt(
                startedAt,
                accumulatedSuspendedMs,
                suspendedAtMs,
                suspended,
                nowMs);
    }

    int getToolCount(String name) {
        Integer value = toolCounts.get(name);
        return value == null ? 0 : value;
    }

    void incrementTool(String name) {
        toolCounts.put(name, getToolCount(name) + 1);
    }

    void setRecipeContext(String goal, String startPackage) {
        recipeGoal = safe(goal);
        recipeStartPackage = safe(startPackage);
    }

    void captureRecipeStep(
            String name,
            JSONObject args,
            JSONObject result,
            JSONObject beforeContext) {
        if (!recipeEligible) return;
        if ("task_recipe".equals(name)) {
            recipeEligible = false;
            recipeIneligibleReason = "FAST_PATH_REPLAY";
            recipeSteps.clear();
            return;
        }
        if (TaskRecipePolicy.isIgnorableObservation(name)) return;
        if (!TaskRecipePolicy.isAllowedTool(name)) {
            recipeEligible = false;
            recipeIneligibleReason = "UNSUPPORTED_TOOL:" + safe(name);
            recipeSteps.clear();
            return;
        }
        if (result == null || !result.optBoolean("success", false)) {
            recipeEligible = false;
            recipeIneligibleReason = "FAILED_STEP";
            recipeSteps.clear();
            return;
        }
        String taskState = result.optString("taskState", "");
        if ("WAITING_USER".equals(taskState) || "BLOCKED".equals(taskState)) {
            recipeEligible = false;
            recipeIneligibleReason = "INTERACTIVE_STEP";
            recipeSteps.clear();
            return;
        }
        if (recipeSteps.size() >= TaskRecipePolicy.MAX_STEPS) {
            recipeEligible = false;
            recipeIneligibleReason = "TOO_MANY_STEPS";
            recipeSteps.clear();
            return;
        }

        JSONObject safeArgs = new JSONObject();
        JSONObject source = args == null ? new JSONObject() : args;
        try {
            if ("launch_app".equals(name)) {
                copyString(source, safeArgs, "app");
                copyString(source, safeArgs, "package_name");
            } else if ("tap_screen".equals(name)) {
                String label = source.optString(
                        "label",
                        source.optString("text", source.optString("name", ""))).trim();
                String id = source.optString("id", "").trim();
                String target = label.isEmpty() ? id : label;
                if (TaskRecipePolicy.isUnsafeTapTarget(target)) {
                    recipeEligible = false;
                    recipeIneligibleReason = "UNSAFE_TAP";
                    recipeSteps.clear();
                    return;
                }
                if (!label.isEmpty()) safeArgs.put("label", label);
                if (!id.isEmpty()) safeArgs.put("id", id);
            } else if ("search_current_app".equals(name)) {
                String text = source.optString("text", "").trim();
                if (!TaskRecipePolicy.canPersistSearch(text)) {
                    recipeEligible = false;
                    recipeIneligibleReason = "UNSAFE_SEARCH";
                    recipeSteps.clear();
                    return;
                }
                safeArgs.put("text", text);
            } else if ("swipe_screen".equals(name)) {
                copyString(source, safeArgs, "direction");
                copyString(source, safeArgs, "distance");
            } else if ("press_key".equals(name)) {
                String key = source.optString("key", "").trim().toUpperCase();
                if (!"BACK".equals(key) && !"HOME".equals(key)) {
                    recipeEligible = false;
                    recipeIneligibleReason = "UNSAFE_KEY";
                    recipeSteps.clear();
                    return;
                }
                safeArgs.put("key", key);
            }

            JSONObject step = new JSONObject()
                    .put("tool", name)
                    .put("args", safeArgs);
            String beforeStable = "";
            if (beforeContext != null) {
                String pkg = beforeContext.optString("currentApp", "").trim();
                beforeStable = beforeContext.optString("stableScreen", "").trim();
                if (!pkg.isEmpty()) step.put("expectedPackage", pkg);
                if (!beforeStable.isEmpty()) {
                    step.put("expectedStableScreen", beforeStable);
                }
            }

            JSONObject after = result.optJSONObject("after");
            String afterStable = after == null
                    ? "" : after.optString("stableScreenKey", "").trim();
            String afterPackage = after == null
                    ? "" : after.optString("package", "").trim();

            // A replayable TAP must be proven navigation, not a same-screen
            // switch/toggle/settings mutation. Stable-screen transition is a
            // deliberately conservative first-version boundary.
            if ("tap_screen".equals(name)
                    && !TaskRecipePolicy.isProvenNavigationTap(
                            beforeStable, afterStable)) {
                recipeEligible = false;
                recipeIneligibleReason = "TAP_NOT_PROVEN_NAVIGATION";
                recipeSteps.clear();
                return;
            }

            if (!afterPackage.isEmpty()) {
                step.put("expectedAfterPackage", afterPackage);
            }
            if (!afterStable.isEmpty()) {
                step.put("expectedAfterStableScreen", afterStable);
            }
            recipeSteps.add(step);
        } catch (Exception error) {
            recipeEligible = false;
            recipeIneligibleReason = "SANITIZE_FAILED";
            recipeSteps.clear();
        }
    }

    boolean canSaveRecipe() {
        return recipeEligible
                && recipeGoal.length() >= 3
                && recipeSteps.size() >= 2
                && recipeSteps.size() <= TaskRecipePolicy.MAX_STEPS;
    }

    JSONArray recipeStepsJson() {
        JSONArray out = new JSONArray();
        for (JSONObject step : recipeSteps) {
            try { out.put(new JSONObject(step.toString())); }
            catch (Exception ignored) {}
        }
        return out;
    }

    void addStep(String name, JSONObject result) {
        String stepResult = result.optString("stepResult", "");
        String outcome = "STEP_PENDING".equals(stepResult)
                ? "待確認"
                : (result.optBoolean("success")
                        ? "成功"
                        : (result.optBoolean("cancelled") ? "已取消" : "失敗"));
        String detail = result.optString("message", result.optString("error", ""));
        stepsSummary.add(name + "：" + outcome
                + (detail.isEmpty() ? "" : "（" + detail + "）"));

        JSONObject diagnostic = new JSONObject();
        try {
            String semanticAction = result.optString("semanticAction", "").trim();
            String target = AgentTapDiagnostic.targetFromRuntimeSignature(
                    name, semanticAction, lastSignature);
            if (!target.isEmpty()) {
                diagnostic.put("requestedTool", "phone_action")
                        .put("semanticAction", "TAP")
                        .put("target", target)
                        .put("resolvedTool", name);
                String semanticTarget = AgentTapDiagnostic.semanticTargetFromRuntimeSignature(
                        name, semanticAction, lastSignature);
                if (!semanticTarget.isEmpty()) {
                    diagnostic.put("semanticTarget", semanticTarget);
                }

                String locatorDecision =
                        result.optString("decision", "").trim();
                String resolvedFrom =
                        result.optString("resolvedFrom", "").trim();
                String verificationStatus =
                        result.optString("verificationStatus", "").trim();
                String verificationCode =
                        result.optString("verificationCode", "").trim();
                if (!locatorDecision.isEmpty()) {
                    diagnostic.put("locatorDecision", locatorDecision);
                }
                if (!resolvedFrom.isEmpty()) {
                    diagnostic.put("resolvedFrom", resolvedFrom);
                }
                if (result.has("confidence")) {
                    diagnostic.put(
                            "locatorConfidence",
                            result.optDouble("confidence", 0.0));
                }
                if (result.has("confidenceMargin")) {
                    diagnostic.put(
                            "locatorMargin",
                            result.optDouble("confidenceMargin", 0.0));
                }
                if (!verificationStatus.isEmpty()) {
                    diagnostic.put(
                            "verificationStatus",
                            verificationStatus);
                }
                if (!verificationCode.isEmpty()) {
                    diagnostic.put(
                            "verificationCode",
                            verificationCode);
                }
            }
        } catch (Exception ignored) {}
        stepDiagnostics.add(diagnostic);
    }

    private static void copyString(
            JSONObject from,
            JSONObject to,
            String key) throws Exception {
        String value = from.optString(key, "").trim();
        if (!value.isEmpty()) to.put(key, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("taskId", taskId)
                    .put("goalId", goalId)
                    .put("goalTaskIndex", goalTaskIndex)
                    .put("startedAt", startedAt)
                    .put("steps", new JSONArray(stepsSummary))
                    .put("stepDiagnostics", new JSONArray(stepDiagnostics))
                    .put("stepCount", steps)
                    .put("mutationActions", mutationActions)
                    .put("consecutiveVisualObservations", consecutiveVisualObservations)
                    .put("blockedReason", blockedReason == null ? "" : blockedReason)
                    .put("endReason", endReason)
                    .put("finalReply", finalReply)
                    .put("status", status)
                    .put("cancelled", cancelled)
                    .put("finished", finished)
                    .put("userVisibleReplyProduced", userVisibleReplyProducedSinceLastAction)
                    .put("finalSpeechRetryCount", finalSpeechRetryCount)
                    .put("lastToolName", lastToolName)
                    .put("lastTaskState", lastTaskState)
                    .put("lastCompletionEvidence", lastCompletionEvidence)
                    .put("lastSuccessfulMutationAtMs", lastSuccessfulMutationAtMs)
                    .put("retryIntentFamily", retryIntentFamily)
                    .put("prematureModelReplies", prematureModelReplies)
                    .put("cancelCategory", cancelCategory)
                    .put("suspended", suspended)
                    .put("suspensionReason", suspensionReason)
                    .put("suspendedAtMs", suspendedAtMs)
                    .put("accumulatedSuspendedMs", accumulatedSuspendedMs)
                    .put("recipeEligible", recipeEligible)
                    .put("recipeStepCount", recipeSteps.size());
        } catch (Exception ignored) {}
        return json;
    }
}
