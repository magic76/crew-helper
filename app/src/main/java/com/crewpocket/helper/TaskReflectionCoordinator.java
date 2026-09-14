package com.crewpocket.helper;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges sanitized Agent Inspector metadata to the post-task reviewer.
 *
 * Important: AgentInspectorStore already strips transcript text, tool args,
 * model replies, screenshots, API keys and raw result details. Reflection sees
 * only that bounded metadata plus the current app identity and existing
 * operational App Playbook context.
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
        try {
            String packageName = currentPackage();
            int failedSteps = failedStepCount(task.optJSONArray("steps"));
            boolean usedSendText = usedTool(task.optJSONArray("steps"), "send_text");
            boolean cancelled = ReflectionLearningPolicy.looksCancelled(rawStatus);
            int mutations = task.optInt("mutationActions", 0);

            if (!ReflectionLearningPolicy.shouldReflect(
                    mutations,
                    failedSteps,
                    false,
                    cancelled,
                    usedSendText,
                    packageName)) {
                Log.d(TAG, "skip task reflection by policy");
                return;
            }

            String apiKey = AppConfig.getGeminiApiKey(context);
            if (apiKey == null || apiKey.trim().length() < 20) {
                Log.d(TAG, "skip task reflection: api key unavailable");
                return;
            }

            AppPlaybookStore playbooks = new AppPlaybookStore(context);
            String appLabel = AppRuntimeRegistry.displayName(context, packageName);
            JSONObject episode = new JSONObject()
                    .put("app", new JSONObject()
                            .put("package", packageName)
                            .put("label", appLabel))
                    .put("outcome", AgentInspectorStore.isSuccessfulTaskEnd(rawStatus)
                            ? "SUCCESS" : "FAILED")
                    .put("task", task)
                    .put("existing_app_playbook", playbooks.modelContext(packageName));

            JSONObject reflection = new GeminiTaskReflector(apiKey).reflect(episode);
            JSONObject stored = new ReflectionLessonStore(context)
                    .record(packageName, appLabel, reflection);
            Log.i(TAG, "post-task reflection complete: stored="
                    + stored.optBoolean("stored", false)
                    + " state=" + stored.optString("state", "SKIPPED")
                    + " confirmations=" + stored.optInt("confirmations", 0));
        } catch (Exception error) {
            // Reflection is best-effort and never allowed to affect Live latency,
            // execution, user-visible status, or task completion.
            Log.d(TAG, "post-task reflection skipped: "
                    + (error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage()));
        }
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
