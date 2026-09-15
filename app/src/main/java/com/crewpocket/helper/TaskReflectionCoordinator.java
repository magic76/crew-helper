package com.crewpocket.helper;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges sanitized Agent Inspector metadata to the post-task reviewer.
 *
 * Important: AgentInspectorStore strips transcript text, TYPE/SEARCH content,
 * model replies, screenshots, API keys and raw result details. Reflection sees
 * only bounded categories/counts, safe TAP labels, current app identity and the
 * existing App Playbook.
 */
final class TaskReflectionCoordinator {
    private static final String TAG = "CrewReflection";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static final Set<String> SCHEDULED = new HashSet<String>();
    private static final int MAX_TRACKED_TASKS = 80;

    private TaskReflectionCoordinator() {}

    static void maybeReflect(Context context,
                             String rawStatus,
                             JSONObject sanitizedTask,
                             boolean activeTask) {
        if (context == null || sanitizedTask == null || activeTask) return;
        if (!String.valueOf(rawStatus).contains("Agent 任務結束")) return;

        final Context appContext = context.getApplicationContext();
        final JSONObject task;
        try { task = new JSONObject(sanitizedTask.toString()); }
        catch (Exception ignored) { return; }

        final String key = task.optString("taskId", "") + "|"
                + task.optLong("startedAt", 0L);
        if (key.equals("|0")) return;
        synchronized (LOCK) {
            if (SCHEDULED.contains(key)) return;
            if (SCHEDULED.size() >= MAX_TRACKED_TASKS) SCHEDULED.clear();
            SCHEDULED.add(key);
        }

        final String statusSnapshot = rawStatus == null ? "" : rawStatus;
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                runReflection(appContext, statusSnapshot, task);
            }
        });
    }

    private static void runReflection(Context context,
                                      String rawStatus,
                                      JSONObject task) {
        final long startedAt = System.currentTimeMillis();
        GeminiTaskReflector reflector = null;
        try {
            String packageName = currentPackage();
            JSONArray currentSteps = task.optJSONArray("steps");
            int failedSteps = failedStepCount(currentSteps);
            boolean usedSendText = usedTool(currentSteps, "send_text");
            boolean cancelled = ReflectionLearningPolicy.looksCancelled(rawStatus);
            int mutations = task.optInt("mutationActions", 0);

            // Action-level success is not sufficient evidence of goal success.
            // Coarse, privacy-safe outcome signals may trigger review even for
            // a short task. A previous task from the SAME conversation goal can
            // also trigger review so a failed task followed by a short recovery
            // task is evaluated as one learning episode without sharing dialogue.
            int outcomeSignals = failedSteps;
            if (task.optBoolean("partialOutcome", false)) outcomeSignals++;
            if (task.optBoolean("modelRefusal", false)) outcomeSignals++;
            if (!task.optString("blockCategory", "").isEmpty()) outcomeSignals++;
            if (hasPreviousGoalFailureEvidence(task.optJSONObject("previousGoalTask"))) {
                outcomeSignals++;
            }

            if (!ReflectionLearningPolicy.shouldReflect(
                    mutations,
                    outcomeSignals,
                    false,
                    cancelled,
                    usedSendText,
                    packageName)) {
                ReflectionHistoryStore.record(
                        context, "SKIPPED", "POLICY", elapsed(startedAt));
                Log.d(TAG, "skip task reflection by policy");
                return;
            }

            String apiKey = AppConfig.getGeminiApiKey(context);
            if (apiKey == null || apiKey.trim().length() < 20) {
                ReflectionHistoryStore.record(
                        context, "SKIPPED", "NO_API_KEY", elapsed(startedAt));
                Log.d(TAG, "skip task reflection: api key unavailable");
                return;
            }

            AppPlaybookStore playbooks = new AppPlaybookStore(context);
            String appLabel = AppRuntimeRegistry.displayName(context, packageName);
            String outcome = AgentInspectorStore.isSuccessfulTaskEnd(rawStatus)
                    ? "SUCCESS" : "FAILED";
            if (task.optBoolean("partialOutcome", false)
                    || task.optBoolean("modelRefusal", false)) {
                outcome = "PARTIAL";
            }

            String goalCategory = runtimeGoalCategory(task, outcomeSignals > 0);
            JSONObject episode = new JSONObject()
                    .put("app", new JSONObject()
                            .put("package", packageName)
                            .put("label", appLabel))
                    .put("outcome", outcome)
                    .put("runtime_goal_category", goalCategory)
                    .put("task", task)
                    .put("existing_app_playbook", playbooks.modelContext(packageName));

            reflector = new GeminiTaskReflector(apiKey);
            JSONObject reflection = reflector.reflect(episode);
            if (!goalCategory.isEmpty()) {
                // Runtime owns lesson identity. Gemini's free-text goal_pattern
                // remains useful for human-readable description only.
                reflection.put("runtime_goal_category", goalCategory);
            }
            JSONObject stored = new ReflectionLessonStore(context)
                    .record(packageName, appLabel, reflection);

            String historyStatus;
            if (stored.optBoolean("stored", false)) {
                historyStatus = stored.optString("state", "STORED");
            } else {
                historyStatus = stored.optString("reason", "NOT_REMEMBERED");
            }
            ReflectionHistoryStore.record(
                    context,
                    "SUCCESS",
                    historyStatus,
                    elapsed(startedAt),
                    reflector.lastModel());

            Log.i(TAG, "post-task reflection complete: model="
                    + reflector.lastModel()
                    + " category=" + goalCategory
                    + " stored=" + stored.optBoolean("stored", false)
                    + " state=" + stored.optString("state", "SKIPPED")
                    + " confirmations=" + stored.optInt("confirmations", 0));
        } catch (Exception error) {
            // Reflection is best-effort and never allowed to affect Live latency,
            // execution, user-visible status, or task completion.
            String code = safeErrorCode(error);
            ReflectionHistoryStore.record(
                    context,
                    "ERROR",
                    code,
                    elapsed(startedAt),
                    reflector == null ? GeminiTaskReflector.MODEL : reflector.lastModel());
            Log.d(TAG, "post-task reflection skipped: " + code);
        }
    }

    static String runtimeGoalCategory(JSONObject task, boolean hasFailureOrPartial) {
        if (task == null) return "";
        JSONArray current = task.optJSONArray("steps");
        JSONObject previousTask = task.optJSONObject("previousGoalTask");
        JSONArray previous = previousTask == null ? null : previousTask.optJSONArray("steps");

        boolean hasRouteModeAction = hasSemanticTargetPrefix(current, "route_mode:")
                || hasSemanticTargetPrefix(previous, "route_mode:");
        boolean hasUiTargetFailure = hasFailureCode(current, "UI_TARGET_NOT_FOUND")
                || hasFailureCode(previous, "UI_TARGET_NOT_FOUND");
        boolean hasSearchEvidence = usedTool(current, "search_current_app")
                || usedTool(previous, "search_current_app");
        boolean hasScreenRecovery = hasFailureThenVisualThenSuccess(current)
                || (hasPreviousGoalFailureEvidence(previousTask)
                    && hasVisualObservation(current)
                    && hasSuccessfulMutation(current));
        boolean hasVerificationFailure = hasVerificationFailure(current)
                || hasVerificationFailure(previous);

        return ReflectionGoalCategory.classify(
                hasRouteModeAction,
                hasUiTargetFailure,
                hasSearchEvidence,
                hasScreenRecovery,
                hasVerificationFailure,
                hasFailureOrPartial);
    }

    private static boolean hasPreviousGoalFailureEvidence(JSONObject previous) {
        if (previous == null || previous.length() == 0) return false;
        if (failedStepCount(previous.optJSONArray("steps")) > 0) return true;
        if (previous.optBoolean("partialOutcome", false)) return true;
        if (previous.optBoolean("modelRefusal", false)) return true;
        return !previous.optString("blockCategory", "").isEmpty();
    }

    private static boolean hasSemanticTargetPrefix(JSONArray steps, String prefix) {
        if (steps == null || prefix == null || prefix.isEmpty()) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String target = step.optString("semanticTarget", "");
            if (target.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean hasFailureCode(JSONArray steps, String code) {
        if (steps == null || code == null || code.isEmpty()) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null || !"FAILED".equals(step.optString("outcome", ""))) continue;
            if (code.equals(step.optString("failureCode", ""))) return true;
        }
        return false;
    }

    private static boolean hasVerificationFailure(JSONArray steps) {
        if (steps == null) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null || !"FAILED".equals(step.optString("outcome", ""))) continue;
            String code = step.optString("failureCode", "").toUpperCase(Locale.ROOT);
            if (code.contains("VERIFY") || code.contains("VERIFICATION")
                    || code.contains("NO_EFFECT") || code.contains("NOT_CONFIRMED")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFailureThenVisualThenSuccess(JSONArray steps) {
        if (steps == null) return false;
        boolean sawFailure = false;
        boolean sawVisualAfterFailure = false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            if ("FAILED".equals(step.optString("outcome", ""))) sawFailure = true;
            String tool = step.optString("tool", "");
            if (sawFailure && isVisualTool(tool)) sawVisualAfterFailure = true;
            if (sawVisualAfterFailure && "SUCCESS".equals(step.optString("outcome", ""))
                    && isMutationTool(tool)) return true;
        }
        return false;
    }

    private static boolean hasVisualObservation(JSONArray steps) {
        if (steps == null) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step != null && isVisualTool(step.optString("tool", ""))) return true;
        }
        return false;
    }

    private static boolean hasSuccessfulMutation(JSONArray steps) {
        if (steps == null) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step != null
                    && "SUCCESS".equals(step.optString("outcome", ""))
                    && isMutationTool(step.optString("tool", ""))) return true;
        }
        return false;
    }

    private static boolean isVisualTool(String tool) {
        return "inspect_ui".equals(tool) || "take_screenshot".equals(tool);
    }

    private static boolean isMutationTool(String tool) {
        if (tool == null || tool.isEmpty()) return false;
        return !isVisualTool(tool)
                && !"wait".equals(tool)
                && !"list_notes".equals(tool)
                && !"get_note".equals(tool)
                && !"search_notes".equals(tool)
                && !"list_app_guidance".equals(tool);
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private static String safeErrorCode(Exception error) {
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage().trim().toUpperCase(Locale.ROOT);
        if (message.startsWith("REFLECTION_HTTP_")) {
            return message.replaceAll("[^A-Z0-9_]", "_");
        }
        if (message.startsWith("REFLECTION_")) {
            String code = message.replaceAll("[^A-Z0-9_]", "_");
            return code.length() <= 48 ? code : code.substring(0, 48);
        }
        if (message.contains("TIMEOUT")) return "TIMEOUT";
        if (message.contains("GEMINI_API_KEY_MISSING")) return "NO_API_KEY";
        return error == null
                ? "UNKNOWN_ERROR"
                : error.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private static String currentPackage() {
        CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        if (service == null) return "";
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return "";
            return root.getPackageName().toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private static int failedStepCount(JSONArray steps) {
        if (steps == null) return 0;
        int failed = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step != null && "FAILED".equals(step.optString("outcome", ""))) failed++;
        }
        return failed;
    }

    private static boolean usedTool(JSONArray steps, String toolName) {
        if (steps == null) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step != null && toolName.equals(step.optString("tool", ""))) return true;
        }
        return false;
    }
}
