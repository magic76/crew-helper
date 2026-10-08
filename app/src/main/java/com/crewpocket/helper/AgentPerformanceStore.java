package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent, privacy-bounded summaries for recently finished Agent tasks.
 * Stores counters and timings only; never transcript text, tool args, screenshots,
 * package names, model replies, typed/search content, or credentials.
 */
final class AgentPerformanceStore {
    private static final String PREFS = "crew_agent_performance";
    private static final String KEY_TASKS = "tasks_v2";
    private static final int MAX_TASKS = 50;
    private static final Object LOCK = new Object();

    private AgentPerformanceStore() {}

    static void recordTerminalTask(Context context, String rawStatus, JSONObject task) {
        if (context == null || task == null || task.optBoolean("active", false)) return;
        String status = rawStatus == null ? "" : rawStatus;
        if (!status.contains("Agent 任務結束") && !status.contains("Agent 任務已停止")) return;

        String taskId = task.optString("taskId", "");
        if (taskId.isEmpty()) return;

        synchronized (LOCK) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray tasks = readArray(prefs.getString(KEY_TASKS, "[]"));
                if (containsTask(tasks, taskId)) return;

                boolean cancelled = status.contains("停止") || status.contains("取消");
                boolean partial = task.optBoolean("partialOutcome", false)
                        || !task.optString("blockCategory", "").isEmpty()
                        || task.optBoolean("modelRefusal", false);
                boolean terminalSuccess =
                        AgentInspectorStore.isSuccessfulTaskEnd(status)
                                && !cancelled
                                && !partial;
                boolean recovered = terminalSuccess
                        && hasFailureThenSuccess(task.optJSONArray("steps"));
                String outcome = cancelled
                        ? "CANCELLED"
                        : partial
                                ? "PARTIAL"
                                : recovered
                                        ? "RECOVERED_SUCCESS"
                                        : terminalSuccess
                                                ? "SUCCESS"
                                                : "HARD_FAILURE";

                JSONObject perf = PerformanceMetrics.latestFinishedTaskSnapshot(taskId);
                long taskMs = perf.optLong("taskMs", -1L);
                if (taskMs < 0L) {
                    long startedAt = task.optLong("startedAt", 0L);
                    taskMs = startedAt > 0L
                            ? Math.max(0L, System.currentTimeMillis() - startedAt)
                            : -1L;
                }

                JSONObject item = new JSONObject()
                        .put("taskId", taskId)
                        .put("at", System.currentTimeMillis())
                        .put("buildVersion", currentBuildVersion(context))
                        .put("runtimePolicyEpoch",
                                AgentRuntimePolicyEpoch.CURRENT_REVISION)
                        .put("outcome", outcome)
                        .put("success", terminalSuccess)
                        .put("cancelled", cancelled)
                        .put("cancelCategory", task.optString("cancelCategory", ""))
                        .put("partial", partial)
                        .put("recovered", recovered)
                        .put("steps", task.optInt("stepCount", 0))
                        .put("taskMs", taskMs)
                        .put("toolRuntimeMs", perf.optLong("toolRuntimeMs", -1L))
                        .put("geminiWaitMs", perf.optLong("geminiWaitMs", -1L))
                        .put("answerReadyToSpeechMs",
                                perf.optLong("answerReadyToSpeechMs", -1L));
                tasks.put(item);

                JSONArray trimmed = new JSONArray();
                for (int i = Math.max(0, tasks.length() - MAX_TASKS);
                     i < tasks.length(); i++) {
                    Object value = tasks.opt(i);
                    if (value != null) trimmed.put(value);
                }
                prefs.edit().putString(KEY_TASKS, trimmed.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    static String buildReport(Context context) {
        JSONArray tasks = new JSONArray();
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                tasks = readArray(prefs.getString(KEY_TASKS, "[]"));
            } catch (Exception ignored) {}
        }

        int total = 0;
        int success = 0;
        int recovered = 0;
        int partial = 0;
        int cancelled = 0;
        int implicitUserRetries = 0;
        int excludedFromSuccessRate = 0;
        int hardFailure = 0;
        LinkedHashMap<String, Integer> cancelCategories =
                new LinkedHashMap<String, Integer>();
        long taskTotal = 0L;
        int taskSamples = 0;
        long toolTotal = 0L;
        int toolSamples = 0;
        long geminiTotal = 0L;
        int geminiSamples = 0;
        long answerReadyTotal = 0L;
        int answerReadySamples = 0;

        for (int i = 0; i < tasks.length(); i++) {
            JSONObject item = tasks.optJSONObject(i);
            if (item == null) continue;
            total++;
            String outcome = normalizedOutcome(item);
            if ("SUCCESS".equals(outcome)) success++;
            else if ("RECOVERED_SUCCESS".equals(outcome)) recovered++;
            else if ("PARTIAL".equals(outcome)) partial++;
            else if ("CANCELLED".equals(outcome)) {
                cancelled++;
                String category = item.optString("cancelCategory", "").trim();
                if (category.isEmpty()) category = "UNKNOWN";
                if (UserRetryAfterUnconfirmedOutcomePolicy.CATEGORY.equals(category)) {
                    implicitUserRetries++;
                }
                if (isExcludedFromSuccessRate(category)) {
                    excludedFromSuccessRate++;
                }
                Integer count = cancelCategories.get(category);
                cancelCategories.put(category, count == null ? 1 : count + 1);
            } else hardFailure++;

            long taskMs = item.optLong("taskMs", -1L);
            if (taskMs >= 0L) { taskTotal += taskMs; taskSamples++; }
            long toolMs = item.optLong("toolRuntimeMs", -1L);
            if (toolMs >= 0L) { toolTotal += toolMs; toolSamples++; }
            long geminiMs = item.optLong("geminiWaitMs", -1L);
            if (geminiMs >= 0L) { geminiTotal += geminiMs; geminiSamples++; }
            long answerReadyMs = item.optLong("answerReadyToSpeechMs", -1L);
            if (answerReadyMs >= 0L) {
                answerReadyTotal += answerReadyMs;
                answerReadySamples++;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(buildCurrentWindowReport(context, tasks)).append("\n\n");
        out.append("Agent performance · last ").append(total).append("/").append(MAX_TASKS).append(" finished tasks\n");
        if (total == 0) {
            out.append("No persistent task samples yet.");
            return out.toString();
        }
        int fullSuccess = success + recovered;
        out.append("Success: ").append(success).append("\n")
                .append("Recovered success: ").append(recovered).append("\n")
                .append("Partial: ").append(partial).append("\n")
                .append("Hard failures: ").append(hardFailure).append("\n")
                .append("Cancelled / superseded: ").append(cancelled).append("\n");
        if (!cancelCategories.isEmpty()) {
            out.append("Cancel categories: ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : cancelCategories.entrySet()) {
                if (!first) out.append(" · ");
                first = false;
                out.append(entry.getKey()).append("=").append(entry.getValue());
            }
            out.append("\n");
        }
        if (implicitUserRetries > 0) {
            out.append("User retries after unconfirmed outcome: ")
                    .append(implicitUserRetries)
                    .append(" · included in success-rate evaluation\n");
        }
        int evaluatedTotal = Math.max(0, total - excludedFromSuccessRate);
        out.append("Full-success rate: ");
        if (evaluatedTotal == 0) {
            out.append("n/a");
        } else {
            out.append(Math.round(fullSuccess * 100.0 / evaluatedTotal))
                    .append("%");
        }
        out.append(" · evaluated=").append(evaluatedTotal);
        if (excludedFromSuccessRate > 0) {
            out.append(" · user-controlled exclusions=")
                    .append(excludedFromSuccessRate);
        }
        out.append("\n");
        int trustScore =
                TrustScorePolicy.score(
                        success,
                        recovered,
                        partial,
                        evaluatedTotal);
        out.append("Trust score: ");
        if (trustScore < 0) {
            out.append("n/a");
        } else {
            out.append(trustScore)
                    .append("/100 · ")
                    .append(
                            TrustScorePolicy.band(
                                    trustScore,
                                    evaluatedTotal));
        }
        out.append("\n");
        if (taskSamples > 0) out.append("Avg task time: ").append(taskTotal / taskSamples).append(" ms\n");
        if (toolSamples > 0) out.append("Avg Runtime tool time: ").append(toolTotal / toolSamples).append(" ms\n");
        if (geminiSamples > 0) out.append("Avg Gemini wait between tools: ").append(geminiTotal / geminiSamples).append(" ms\n");
        if (answerReadySamples > 0) {
            out.append("Avg answer-ready -> final speech: ")
                    .append(answerReadyTotal / answerReadySamples).append(" ms\n");
        }
        out.append("Persistent across Runtime/process restarts.");
        String arbitration =
                CandidateArbitrationTelemetryStore.buildReport(context);
        if (!arbitration.isEmpty()) {
            out.append("\n\n").append(arbitration);
        }
        return out.toString();
    }

    static void clear(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    private static String buildCurrentWindowReport(
            Context context,
            JSONArray tasks) {
        String build = currentBuildVersion(context);
        int epoch = AgentRuntimePolicyEpoch.CURRENT_REVISION;
        int total = 0;
        int success = 0;
        int recovered = 0;
        int partial = 0;
        int hardFailure = 0;
        int cancelled = 0;
        int excluded = 0;
        LinkedHashMap<String, Integer> cancelCategories =
                new LinkedHashMap<String, Integer>();

        if (tasks != null) {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject item = tasks.optJSONObject(i);
                if (item == null
                        || !build.equals(item.optString("buildVersion", ""))
                        || epoch != item.optInt("runtimePolicyEpoch", -1)) {
                    continue;
                }
                total++;
                String outcome = normalizedOutcome(item);
                if ("SUCCESS".equals(outcome)) success++;
                else if ("RECOVERED_SUCCESS".equals(outcome)) recovered++;
                else if ("PARTIAL".equals(outcome)) partial++;
                else if ("CANCELLED".equals(outcome)) {
                    cancelled++;
                    String category =
                            item.optString("cancelCategory", "").trim();
                    if (category.isEmpty()) category = "UNKNOWN";
                    if (isExcludedFromSuccessRate(category)) excluded++;
                    Integer count = cancelCategories.get(category);
                    cancelCategories.put(
                            category,
                            count == null ? 1 : count + 1);
                } else {
                    hardFailure++;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Current build / Runtime policy epoch\n")
                .append("Build: ")
                .append(build.isEmpty() ? "unknown" : build)
                .append(" · epoch ")
                .append(epoch)
                .append("\n")
                .append("Fresh tasks: ")
                .append(total);
        if (total == 0) {
            out.append(" · waiting for post-update samples");
            return out.toString();
        }

        int evaluated = Math.max(0, total - excluded);
        int fullSuccess = success + recovered;
        out.append("\nSuccess: ").append(success)
                .append(" · recovered: ").append(recovered)
                .append(" · partial: ").append(partial)
                .append(" · hard failure: ").append(hardFailure)
                .append("\nCancelled / superseded: ").append(cancelled);
        if (!cancelCategories.isEmpty()) {
            out.append(" · ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry
                    : cancelCategories.entrySet()) {
                if (!first) out.append(" · ");
                first = false;
                out.append(entry.getKey())
                        .append("=")
                        .append(entry.getValue());
            }
        }
        out.append("\nFull-success rate: ");
        if (evaluated == 0) {
            out.append("n/a");
        } else {
            out.append(Math.round(
                    fullSuccess * 100.0d / evaluated))
                    .append("%");
        }
        out.append(" · evaluated=").append(evaluated);
        if (excluded > 0) {
            out.append(" · user-controlled exclusions=")
                    .append(excluded);
        }
        int trustScore =
                TrustScorePolicy.score(
                        success,
                        recovered,
                        partial,
                        evaluated);
        out.append("\nTrust score: ");
        if (trustScore < 0) {
            out.append("n/a");
        } else {
            out.append(trustScore)
                    .append("/100 · ")
                    .append(
                            TrustScorePolicy.band(
                                    trustScore,
                                    evaluated));
        }
        return out.toString();
    }

    private static String currentBuildVersion(Context context) {
        if (context == null) return "";
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(
                            context.getPackageName(), 0);
            return info.versionName == null
                    ? "" : info.versionName.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizedOutcome(JSONObject item) {
        if (item == null) return "HARD_FAILURE";
        String explicit = item.optString("outcome", "").trim();
        if (!explicit.isEmpty()) return explicit;
        // Backward compatibility for samples written before outcome categories.
        if (item.optBoolean("cancelled", false)) return "CANCELLED";
        if (item.optBoolean("partial", false)) return "PARTIAL";
        if (item.optBoolean("recovered", false)) return "RECOVERED_SUCCESS";
        if (item.optBoolean("success", false)) return "SUCCESS";
        return "HARD_FAILURE";
    }

    static boolean isExcludedFromSuccessRate(String category) {
        String value = category == null ? "" : category.trim();
        return "SUPERSEDED_BY_USER".equals(value)
                || "NEW_USER_GOAL".equals(value) // legacy persisted samples
                || "USER_INTERRUPTED".equals(value)
                || "HUMAN_TAKEOVER".equals(value);
    }

    private static boolean containsTask(JSONArray tasks, String taskId) {
        for (int i = tasks.length() - 1; i >= 0; i--) {
            JSONObject item = tasks.optJSONObject(i);
            if (item != null && taskId.equals(item.optString("taskId", ""))) return true;
        }
        return false;
    }

    private static boolean hasFailureThenSuccess(JSONArray steps) {
        boolean failed = false;
        if (steps == null) return false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String outcome = step.optString("outcome", "");
            if ("FAILED".equals(outcome)) failed = true;
            else if (failed && "SUCCESS".equals(outcome)) return true;
        }
        return false;
    }

    private static JSONArray readArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }
}
