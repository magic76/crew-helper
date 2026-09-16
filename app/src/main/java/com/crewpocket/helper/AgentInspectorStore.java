package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privacy-bounded developer trace for the latest phone Agent task.
 *
 * Stores fixed runtime categories, tool names, counts and outcomes. For semantic
 * TAP diagnostics only, it may also keep a bounded non-sensitive UI target label
 * such as "開車" or "結束". It deliberately never stores screenshots,
 * transcript/user text, TYPE/SEARCH content, arbitrary tool args, model replies,
 * API keys, bridge tokens or raw result details.
 */
final class AgentInspectorStore {
    private static final String PREFS = "crew_agent_inspector";
    private static final String KEY_EVENTS = "events_v1";
    private static final String KEY_TASK = "task_v1";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final int MAX_EVENTS = 20;

    private static final Pattern TOOL_FROM_STATUS =
            Pattern.compile("正在執行「([A-Za-z0-9_]{1,48})」");
    private static final Pattern SAFE_TOOL =
            Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static final Pattern SAFE_FAILURE_CODE =
            Pattern.compile("（([A-Z][A-Z0-9_]{2,63})）");
    private static final Pattern TYPE_LENGTH_MISMATCH = Pattern.compile(
            "TYPE_LENGTH_MISMATCH_EXPECTED_(\\d{1,5})_ACTUAL_(\\d{1,5})");
    private static final Pattern SAFE_SEMANTIC_TARGET =
            Pattern.compile("[A-Za-z0-9:_-]{1,80}");

    private AgentInspectorStore() {}

    static String friendlyStage(String rawStatus, boolean activeTask) {
        String raw = rawStatus == null ? "" : rawStatus.trim();
        if (raw.isEmpty()) return activeTask ? "正在執行任務…" : "";

        if (raw.contains("等待使用者選擇")) return "等你選擇";
        if (raw.contains("正在驗證上一個操作") || raw.contains("正在確認")) {
            return "正在確認結果…";
        }
        if (raw.contains("等待最終語音")
                || raw.contains("工具結果已回傳")
                || raw.contains("未收到模型下一步")) {
            return "等待 Gemini…";
        }
        if (raw.contains("任務已停止") || raw.contains("使用者取消")) {
            return "任務已停止";
        }
        if (raw.contains("Agent 任務結束")) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("失敗")
                    || lower.contains("錯誤")
                    || lower.contains("未完成")
                    || lower.contains("逾時")
                    || lower.contains("取消")) {
                return "操作未完成";
            }
            return "✓ 已完成";
        }

        Matcher matcher = TOOL_FROM_STATUS.matcher(raw);
        if (matcher.find()) return friendlyTool(matcher.group(1));

        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("失敗")
                || lower.contains("錯誤")
                || lower.contains("無法")
                || lower.contains("blocked")
                || lower.contains("逾時")) {
            return activeTask ? "正在處理問題…" : "操作未完成";
        }
        return activeTask ? "正在執行任務…" : "";
    }

    static boolean isSuccessfulTaskEnd(String rawStatus) {
        String raw = rawStatus == null ? "" : rawStatus.trim();
        if (!raw.contains("Agent 任務結束")) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return !lower.contains("失敗")
                && !lower.contains("錯誤")
                && !lower.contains("未完成")
                && !lower.contains("逾時")
                && !lower.contains("取消")
                && !raw.contains("使用者取消");
    }

    /** Minimal labels allowed beside the bubble. */
    static String quietFeedbackLabel(String rawStatus, boolean activeTask) {
        String raw = rawStatus == null ? "" : rawStatus.trim();
        if (raw.isEmpty()) return "";
        if (raw.contains("等待使用者選擇") || raw.contains("NEED_USER")) {
            return "需要你選擇";
        }

        String lower = raw.toLowerCase(Locale.ROOT);
        boolean permission = lower.contains("permission")
                || raw.contains("權限不足")
                || raw.contains("未取得權限");
        if (permission && activeTask) return "需要權限";

        if (!activeTask && raw.contains("Agent 任務結束")) {
            boolean failed = lower.contains("失敗")
                    || lower.contains("錯誤")
                    || lower.contains("未完成")
                    || lower.contains("逾時");
            if (failed) return "操作失敗";
        }
        return "";
    }

    private static String friendlyTool(String tool) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (t.contains("open_app") || t.equals("launch_app")) return "正在開啟 App…";
        if (t.contains("search")) return "正在搜尋…";
        if (isVisualObservationTool(t)) return "正在看畫面…";
        if (t.contains("send")) return "正在送出…";
        if (t.contains("deck")) return "正在操作簡報…";
        if (t.contains("tap")
                || t.contains("click")
                || t.contains("scroll")
                || t.contains("swipe")
                || t.contains("input")
                || t.contains("type")) {
            return "正在操作…";
        }
        return "正在執行任務…";
    }

    private static boolean isVisualObservationTool(String tool) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT).trim();
        // tap_screen mutates the phone; it must never count as an observation.
        return "inspect_ui".equals(t) || "take_screenshot".equals(t);
    }

    static synchronized void record(Context context,
                                    String rawStatus,
                                    JSONArray history,
                                    boolean activeTask) {
        if (context == null) return;

        boolean agentStatus = isAgentStatus(rawStatus, activeTask);
        JSONObject task = sanitizeLatestTask(history, activeTask);
        if (!agentStatus && task == null) return;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray events = readArray(prefs.getString(KEY_EVENTS, "[]"));

        if (agentStatus) {
            String stage = friendlyStage(rawStatus, activeTask);
            String tool = safeToolFromStatus(rawStatus);
            if (!stage.isEmpty() && !duplicatesLast(events, stage, tool)) {
                JSONObject event = new JSONObject();
                try {
                    event.put("at", System.currentTimeMillis());
                    event.put("stage", stage);
                    if (!tool.isEmpty()) event.put("tool", tool);
                    events.put(event);
                } catch (Exception ignored) {}
            }
        }

        if (events.length() > MAX_EVENTS) {
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, events.length() - MAX_EVENTS); i < events.length(); i++) {
                trimmed.put(events.opt(i));
            }
            events = trimmed;
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_EVENTS, events.toString())
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        if (task != null) editor.putString(KEY_TASK, task.toString());
        editor.apply();

        // Reflection sees only sanitized task categories plus bounded TAP UI labels.
        // Raw user/model text, TYPE/SEARCH content, blocked reasons and arbitrary
        // tool arguments never leave this method.
        if (task != null) {
            TaskReflectionCoordinator.maybeReflect(context, rawStatus, task, activeTask);
        }
    }

    static synchronized String buildReport(Context context) {
        if (context == null) return "Agent Inspector\nNo context.";

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray events = readArray(prefs.getString(KEY_EVENTS, "[]"));
        JSONObject task = readObject(prefs.getString(KEY_TASK, "{}"));
        long updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L);

        StringBuilder out = new StringBuilder();
        out.append("Crew Helper Agent Inspector\n");
        out.append("Privacy: sanitized runtime metadata only\n");
        out.append("No screenshots, transcript text, TYPE/SEARCH content, replies, API keys or bridge tokens. Safe TAP target labels may appear for diagnostics.\n\n");

        if (updatedAt > 0L) out.append("Updated: ").append(formatTime(updatedAt)).append("\n");

        if (task.length() > 0) {
            String taskState = task.optString("state",
                    task.optBoolean("active", false)
                            ? InspectorTaskState.ACTIVE : InspectorTaskState.COMPLETED);
            out.append("State: ").append(taskState).append("\n");
            String taskId = task.optString("taskId", "");
            if (!taskId.isEmpty()) out.append("Task: ").append(taskId).append("\n");
            int goalTaskIndex = task.optInt("goalTaskIndex", 0);
            if (goalTaskIndex > 0) out.append("Goal task index: ").append(goalTaskIndex).append("\n");
            String goalBoundary = task.optString("goalBoundary", "");
            if (!goalBoundary.isEmpty()) {
                out.append("Goal continuity: NEW · ").append(goalBoundary).append("\n");
            }
            out.append("Steps: ").append(task.optInt("stepCount", 0)).append("\n");
            out.append("Mutations: ").append(task.optInt("mutationActions", 0)).append("\n");
            out.append("Visual observations: ")
                    .append(task.optInt("visualObservations", 0)).append("\n");

            if (task.optBoolean("partialOutcome", false)
                    || task.optBoolean("modelRefusal", false)
                    || !task.optString("blockCategory", "").isEmpty()) {
                out.append("Outcome evidence:");
                if (task.optBoolean("partialOutcome", false)) out.append(" PARTIAL");
                if (task.optBoolean("modelRefusal", false)) out.append(" MODEL_REFUSAL");
                String block = task.optString("blockCategory", "");
                if (!block.isEmpty()) out.append(" ").append(block);
                out.append("\n");
            }

            appendSteps(out, task.optJSONArray("steps"), "\nTool outcomes:\n");

            JSONObject previous = task.optJSONObject("previousGoalTask");
            if (previous != null && previous.length() > 0) {
                out.append("\nPrevious same-goal task:\n");
                out.append("Goal task index: ").append(previous.optInt("goalTaskIndex", 0)).append("\n");
                out.append("Steps: ").append(previous.optInt("stepCount", 0))
                        .append(" · mutations: ").append(previous.optInt("mutationActions", 0))
                        .append(" · visual: ").append(previous.optInt("visualObservations", 0))
                        .append("\n");
                appendSteps(out, previous.optJSONArray("steps"), "Outcomes:\n");
            }
        } else {
            out.append("Task: none captured yet\n");
        }

        out.append("\n\n")
                .append(PerformanceMetrics.buildReportForTask(task.optString("taskId", "")))
                .append("\n");
        if (events.length() > 0) {
            out.append("\nRecent runtime stages:\n");
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                out.append(formatTime(event.optLong("at", 0L)))
                        .append("  ")
                        .append(event.optString("stage", ""))
                        .append("\n");
            }
        }
        return out.toString().trim();
    }

    private static void appendSteps(StringBuilder out, JSONArray steps, String heading) {
        if (steps == null || steps.length() == 0) return;
        out.append(heading);
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            out.append(i + 1).append(". ");

            String requestedTool = step.optString("requestedTool", "");
            String semanticAction = step.optString("semanticAction", "");
            String target = step.optString("target", "");
            String runtimeTool = step.optString("tool", "tool");
            if ("phone_action".equals(requestedTool)
                    && "TAP".equals(semanticAction)
                    && !target.isEmpty()) {
                out.append("phone_action(TAP, target=\"")
                        .append(reportQuote(target))
                        .append("\") → ")
                        .append(runtimeTool);
                String semanticTarget = step.optString("semanticTarget", "");
                if (!semanticTarget.isEmpty()) {
                    out.append(" [").append(semanticTarget).append("]");
                }
            } else {
                out.append(runtimeTool);
            }

            out.append(" · ")
                    .append(step.optString("outcome", "UNKNOWN"));
            String failureCode = step.optString("failureCode", "");
            if ("TYPE_LENGTH_MISMATCH".equals(failureCode)) {
                out.append(" · TYPE_LENGTH_MISMATCH expected=")
                        .append(step.optInt("expectedTextLength", 0))
                        .append(" actual=")
                        .append(step.optInt("actualTextLength", 0));
            } else if (!failureCode.isEmpty()) {
                out.append(" · ").append(failureCode);
            }
            out.append("\n");
        }
    }

    static synchronized void clear(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();
        PerformanceMetrics.reset();
    }

    private static boolean isAgentStatus(String rawStatus, boolean activeTask) {
        if (activeTask) return true;
        String raw = rawStatus == null ? "" : rawStatus;
        return raw.contains("Agent")
                || raw.contains("正在執行「")
                || raw.contains("正在驗證上一個操作")
                || raw.contains("等待使用者選擇")
                || raw.contains("工具結果已回傳")
                || raw.contains("等待最終語音");
    }

    private static String safeToolFromStatus(String rawStatus) {
        String raw = rawStatus == null ? "" : rawStatus;
        Matcher matcher = TOOL_FROM_STATUS.matcher(raw);
        if (!matcher.find()) return "";
        String tool = matcher.group(1);
        return SAFE_TOOL.matcher(tool).matches() ? tool : "";
    }

    /**
     * Sanitizes the latest task and attaches at most ONE immediately preceding
     * task from the same conversation capsule only when Runtime evidence says
     * the two tasks operate in the same coarse capability domain.
     */
    private static JSONObject sanitizeLatestTask(JSONArray history, boolean activeTask) {
        if (history == null || history.length() == 0) return null;
        int currentPosition = history.length() - 1;
        JSONObject raw = history.optJSONObject(currentPosition);
        if (raw == null) return null;

        JSONObject safe = sanitizeTask(raw, activeTask);
        if (safe == null) return null;

        String goalId = raw.optString("goalId", "").trim();
        int goalTaskIndex = Math.max(0, raw.optInt("goalTaskIndex", 0));
        if (goalTaskIndex > 0) {
            try { safe.put("goalTaskIndex", goalTaskIndex); } catch (Exception ignored) {}
        }

        if (!goalId.isEmpty() && goalTaskIndex > 1) {
            JSONObject previousRaw = findPreviousGoalTask(
                    history, currentPosition, goalId, goalTaskIndex);
            JSONObject previousSafe = sanitizeTask(previousRaw, false);
            if (previousSafe != null) {
                if (GoalTaskContinuityPolicy.compatible(previousSafe, safe)) {
                    int previousIndex = Math.max(0, previousRaw.optInt("goalTaskIndex", 0));
                    try {
                        previousSafe.put("goalTaskIndex", previousIndex);
                        safe.put("previousGoalTask", compactPreviousTask(previousSafe));
                    } catch (Exception ignored) {}
                } else {
                    try { safe.put("goalBoundary", "CAPABILITY_DOMAIN_CHANGED"); }
                    catch (Exception ignored) {}
                }
            }
        }
        return safe;
    }

    private static JSONObject findPreviousGoalTask(JSONArray history,
                                                   int currentPosition,
                                                   String goalId,
                                                   int currentGoalTaskIndex) {
        if (history == null || goalId == null || goalId.isEmpty()) return null;
        for (int i = currentPosition - 1; i >= 0; i--) {
            JSONObject candidate = history.optJSONObject(i);
            if (candidate == null) continue;
            if (!goalId.equals(candidate.optString("goalId", ""))) continue;
            int candidateIndex = candidate.optInt("goalTaskIndex", 0);
            if (candidateIndex > 0 && candidateIndex < currentGoalTaskIndex) return candidate;
        }
        return null;
    }

    private static JSONObject compactPreviousTask(JSONObject task) {
        if (task == null) return null;
        JSONObject out = new JSONObject();
        try {
            out.put("goalTaskIndex", task.optInt("goalTaskIndex", 0));
            out.put("stepCount", task.optInt("stepCount", 0));
            out.put("mutationActions", task.optInt("mutationActions", 0));
            out.put("visualObservations", task.optInt("visualObservations", 0));
            out.put("steps", task.optJSONArray("steps") == null
                    ? new JSONArray() : new JSONArray(task.optJSONArray("steps").toString()));
            if (task.optBoolean("partialOutcome", false)) out.put("partialOutcome", true);
            if (task.optBoolean("modelRefusal", false)) out.put("modelRefusal", true);
            String block = task.optString("blockCategory", "");
            if (!block.isEmpty()) out.put("blockCategory", block);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject sanitizeTask(JSONObject raw, boolean activeTask) {
        if (raw == null) return null;
        JSONObject safe = new JSONObject();
        try {
            String taskId = raw.optString("taskId", "");
            if (!taskId.isEmpty()) {
                String suffix = taskId.length() <= 8
                        ? taskId : taskId.substring(taskId.length() - 8);
                safe.put("taskId", "…" + suffix);
            }
            safe.put("active", activeTask);
            safe.put("state", InspectorTaskState.classify(
                    activeTask,
                    raw.optBoolean("cancelled", false),
                    raw.optString("status", ""),
                    raw.optString("endReason", ""),
                    raw.optString("blockedReason", "")));
            safe.put("startedAt", raw.optLong("startedAt", 0L));
            safe.put("stepCount", raw.optInt("stepCount", 0));
            safe.put("mutationActions", raw.optInt("mutationActions", 0));

            String blockCategory = classifyBlock(raw.optString("blockedReason", ""));
            if (!blockCategory.isEmpty()) safe.put("blockCategory", blockCategory);

            if (looksPartial(raw.optString("endReason", ""), raw.optString("status", ""))) {
                safe.put("partialOutcome", true);
            }
            if (looksLikeModelRefusal(raw.optString("finalReply", ""))) {
                safe.put("modelRefusal", true);
            }

            JSONArray rawSteps = raw.optJSONArray("steps");
            JSONArray rawDiagnostics = raw.optJSONArray("stepDiagnostics");
            JSONArray safeSteps = new JSONArray();
            int visualObservations = 0;
            if (rawSteps != null) {
                for (int i = 0; i < rawSteps.length(); i++) {
                    String line = rawSteps.optString(i, "");
                    int colon = line.indexOf('：');
                    if (colon <= 0) continue;
                    String tool = line.substring(0, colon).trim();
                    if (!SAFE_TOOL.matcher(tool).matches()) continue;

                    String outcome;
                    if (line.contains("成功")) outcome = "SUCCESS";
                    else if (line.contains("已取消")) outcome = "CANCELLED";
                    else if (line.contains("失敗")) outcome = "FAILED";
                    else outcome = "UNKNOWN";

                    JSONObject step = new JSONObject();
                    step.put("tool", tool);
                    step.put("outcome", outcome);

                    JSONObject diagnostic = rawDiagnostics == null
                            ? null : rawDiagnostics.optJSONObject(i);
                    if (diagnostic != null
                            && "phone_action".equals(diagnostic.optString("requestedTool", ""))
                            && "TAP".equals(diagnostic.optString("semanticAction", ""))
                            && tool.equals(diagnostic.optString("resolvedTool", ""))) {
                        String target = AgentTapDiagnostic.sanitizeTarget(
                                diagnostic.optString("target", ""));
                        if (!target.isEmpty()) {
                            step.put("requestedTool", "phone_action")
                                    .put("semanticAction", "TAP")
                                    .put("target", target);
                            String semanticTarget = diagnostic.optString("semanticTarget", "").trim();
                            if (SAFE_SEMANTIC_TARGET.matcher(semanticTarget).matches()) {
                                step.put("semanticTarget", semanticTarget);
                            }
                        }
                    }

                    // Preserve only deterministic uppercase Runtime error codes;
                    // arbitrary message/detail text remains discarded.
                    if ("FAILED".equals(outcome)) {
                        Matcher failure = SAFE_FAILURE_CODE.matcher(line);
                        if (failure.find()) {
                            step.put("failureCode", failure.group(1));
                            safe.put("partialOutcome", true);
                        }
                    }

                    Matcher lengthMismatch = TYPE_LENGTH_MISMATCH.matcher(line);
                    if (lengthMismatch.find()) {
                        int expected = safeBoundedInt(lengthMismatch.group(1));
                        int actual = safeBoundedInt(lengthMismatch.group(2));
                        step.put("failureCode", "TYPE_LENGTH_MISMATCH")
                                .put("expectedTextLength", expected)
                                .put("actualTextLength", actual);
                        safe.put("partialOutcome", true);
                    }
                    safeSteps.put(step);
                    if (isVisualObservationTool(tool)) visualObservations++;
                }
            }
            safe.put("steps", safeSteps);
            safe.put("visualObservations", visualObservations);
        } catch (Exception ignored) {}
        return safe;
    }

    private static String classifyBlock(String raw) {
        String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
        if (value.isEmpty()) return "";
        if (containsAny(value,
                "sensitive", "credential", "manual operation", "payment",
                "password", "otp", "安全", "敏感", "憑證", "凭证",
                "密碼", "密码", "驗證碼", "验证码")) {
            return "SAFETY_BLOCK";
        }
        if (containsAny(value,
                "limit", "maximum", "budget", "上限", "最多", "逾時", "timeout")) {
            return "BUDGET_BLOCK";
        }
        if (containsAny(value,
                "repeat", "observe", "stability", "重複", "重复", "觀察", "观察")) {
            return "STABILITY_BLOCK";
        }
        return "RUNTIME_BLOCK";
    }

    private static boolean looksPartial(String endReason, String status) {
        String value = ((endReason == null ? "" : endReason) + " "
                + (status == null ? "" : status)).toLowerCase(Locale.ROOT);
        return containsAny(value,
                "partial", "incomplete", "not complete", "need_user",
                "未完成", "部分完成", "需要使用者", "等待使用者");
    }

    private static boolean looksLikeModelRefusal(String reply) {
        String value = reply == null ? "" : reply.toLowerCase(Locale.ROOT).trim();
        if (value.isEmpty()) return false;
        return containsAny(value,
                "基於安全", "基于安全", "安全限制", "無法協助", "无法协助",
                "我不能", "不能幫", "不能帮", "我無法", "我无法",
                "can't help", "cannot help", "can't do", "cannot do",
                "not allowed", "safety restriction", "safety reasons");
    }

    private static int safeBoundedInt(String value) {
        try { return Math.max(0, Math.min(5000, Integer.parseInt(value))); }
        catch (Exception ignored) { return 0; }
    }

    private static boolean containsAny(String value, String... markers) {
        if (value == null || value.isEmpty()) return false;
        for (String marker : markers) {
            if (marker != null && !marker.isEmpty() && value.contains(marker)) return true;
        }
        return false;
    }

    private static boolean duplicatesLast(JSONArray events, String stage, String tool) {
        if (events == null || events.length() == 0) return false;
        JSONObject last = events.optJSONObject(events.length() - 1);
        if (last == null) return false;
        return stage.equals(last.optString("stage", ""))
                && tool.equals(last.optString("tool", ""));
    }

    private static JSONArray readArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject readObject(String raw) {
        try { return new JSONObject(raw == null ? "{}" : raw); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static String reportQuote(String value) {
        String clean = AgentTapDiagnostic.sanitizeTarget(value);
        return clean.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatTime(long at) {
        if (at <= 0L) return "--:--:--";
        try {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at));
        } catch (Exception ignored) {
            return String.valueOf(at);
        }
    }
}
