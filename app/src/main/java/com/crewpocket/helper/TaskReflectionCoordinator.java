package com.crewpocket.helper;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges sanitized Agent Inspector metadata to the post-task reviewer.
 *
 * Reflection identity is Runtime-owned. The coordinator derives bounded,
 * deterministic evidence rules first; Gemini may only select among those rules
 * and phrase a concise human-readable lesson.
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

            List<ReflectionRuleEvidence.Candidate> candidates =
                    deriveRuleCandidates(task);
            if (candidates.isEmpty()) {
                ReflectionHistoryStore.record(
                        context, "SKIPPED", "NO_RULE_EVIDENCE", elapsed(startedAt));
                Log.d(TAG, "skip task reflection: no deterministic rule evidence");
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

            JSONObject episode = new JSONObject()
                    .put("app", new JSONObject()
                            .put("package", packageName)
                            .put("label", appLabel))
                    .put("outcome", outcome)
                    .put("evidence_rules", candidateJson(candidates))
                    .put("task", task)
                    .put("existing_app_playbook", playbooks.modelContext(packageName));

            reflector = new GeminiTaskReflector(apiKey);
            JSONObject reflection = reflector.reflect(episode);
            JSONObject stored = new ReflectionLessonStore(context)
                    .recordRules(packageName, appLabel, candidates, reflection);

            String historyStatus;
            if (stored.optInt("storedCount", 0) > 0) {
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
                    + " candidates=" + candidates.size()
                    + " stored=" + stored.optInt("storedCount", 0)
                    + " state=" + stored.optString("state", "SKIPPED"));
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

    static List<ReflectionRuleEvidence.Candidate> deriveRuleCandidates(JSONObject task) {
        if (task == null) return new ArrayList<ReflectionRuleEvidence.Candidate>();
        JSONObject previousTask = task.optJSONObject("previousGoalTask");
        List<ReflectionRuleEvidence.Step> previous = previousTask == null
                ? new ArrayList<ReflectionRuleEvidence.Step>()
                : steps(previousTask.optJSONArray("steps"));
        List<ReflectionRuleEvidence.Step> current = steps(task.optJSONArray("steps"));
        return ReflectionRuleEvidence.derive(previous, current);
    }

    private static List<ReflectionRuleEvidence.Step> steps(JSONArray raw) {
        ArrayList<ReflectionRuleEvidence.Step> out =
                new ArrayList<ReflectionRuleEvidence.Step>();
        if (raw == null) return out;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject step = raw.optJSONObject(i);
            if (step == null) continue;
            out.add(new ReflectionRuleEvidence.Step(
                    step.optString("tool", ""),
                    step.optString("outcome", ""),
                    step.optString("failureCode", ""),
                    step.optString("semanticTarget", ""),
                    step.optString("semanticAction", "")));
        }
        return out;
    }

    private static JSONArray candidateJson(
            List<ReflectionRuleEvidence.Candidate> candidates) {
        JSONArray out = new JSONArray();
        if (candidates == null) return out;
        for (ReflectionRuleEvidence.Candidate candidate : candidates) {
            if (candidate == null) continue;
            try {
                out.put(new JSONObject()
                        .put("id", candidate.id)
                        .put("scope", candidate.scope)
                        .put("condition", candidate.condition)
                        .put("response", candidate.response)
                        .put("evidence", candidate.evidence));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean hasPreviousGoalFailureEvidence(JSONObject previous) {
        if (previous == null || previous.length() == 0) return false;
        if (failedStepCount(previous.optJSONArray("steps")) > 0) return true;
        if (previous.optBoolean("partialOutcome", false)) return true;
        if (previous.optBoolean("modelRefusal", false)) return true;
        return !previous.optString("blockCategory", "").isEmpty();
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
