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
 * 0088: privacy-bounded developer trace for the latest phone Agent task.
 *
 * Stores only fixed runtime categories, tool names, counts and outcomes.
 * It deliberately never stores screenshots, transcript/user text, tool args,
 * model replies, API keys, bridge tokens or raw result details.
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

    private AgentInspectorStore() {}

    static String friendlyStage(String rawStatus, boolean activeTask) {
        String raw = rawStatus == null ? "" : rawStatus.trim();
        if (raw.isEmpty()) return activeTask ? "正在執行任務…" : "";

        if (raw.contains("等待使用者選擇")) return "等你選擇";
        if (raw.contains("正在驗證上一個操作")
                || raw.contains("正在確認")) {
            return "正在確認結果…";
        }
        if (raw.contains("等待最終語音")
                || raw.contains("工具結果已回傳")
                || raw.contains("未收到模型下一步")) {
            return "等待 Gemini…";
        }
        if (raw.contains("任務已停止")
                || raw.contains("使用者取消")) {
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
        if (matcher.find()) {
            return friendlyTool(matcher.group(1));
        }

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

    private static String friendlyTool(String tool) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (t.contains("open_app") || t.equals("launch_app")) {
            return "正在開啟 App…";
        }
        if (t.contains("search")) {
            return "正在搜尋…";
        }
        if (t.contains("inspect")
                || t.contains("screenshot")
                || t.contains("screen")) {
            return "正在看畫面…";
        }
        if (t.contains("send")) {
            return "正在送出…";
        }
        if (t.contains("deck")) {
            return "正在操作簡報…";
        }
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
            for (int i = Math.max(0, events.length() - MAX_EVENTS);
                 i < events.length();
                 i++) {
                trimmed.put(events.opt(i));
            }
            events = trimmed;
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_EVENTS, events.toString())
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        if (task != null) {
            editor.putString(KEY_TASK, task.toString());
        }
        editor.apply();
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
        out.append("No screenshots, transcript text, tool args, replies, API keys or bridge tokens.\n\n");

        if (updatedAt > 0L) {
            out.append("Updated: ").append(formatTime(updatedAt)).append("\n");
        }

        if (task.length() > 0) {
            out.append("State: ")
                    .append(task.optBoolean("active", false) ? "ACTIVE" : "FINISHED")
                    .append("\n");
            String taskId = task.optString("taskId", "");
            if (!taskId.isEmpty()) out.append("Task: ").append(taskId).append("\n");
            out.append("Steps: ").append(task.optInt("stepCount", 0)).append("\n");
            out.append("Mutations: ").append(task.optInt("mutationActions", 0)).append("\n");
            out.append("Visual observations: ")
                    .append(task.optInt("visualObservations", 0)).append("\n");

            JSONArray steps = task.optJSONArray("steps");
            if (steps != null && steps.length() > 0) {
                out.append("\nTool outcomes:\n");
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    out.append(i + 1).append(". ")
                            .append(step.optString("tool", "tool"))
                            .append(" · ")
                            .append(step.optString("outcome", "UNKNOWN"))
                            .append("\n");
                }
            }
        } else {
            out.append("Task: none captured yet\n");
        }

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

    static synchronized void clear(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
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

    private static JSONObject sanitizeLatestTask(JSONArray history,
                                                 boolean activeTask) {
        if (history == null || history.length() == 0) return null;
        JSONObject raw = history.optJSONObject(history.length() - 1);
        if (raw == null) return null;

        JSONObject safe = new JSONObject();
        try {
            String taskId = raw.optString("taskId", "");
            if (!taskId.isEmpty()) {
                String suffix = taskId.length() <= 8
                        ? taskId
                        : taskId.substring(taskId.length() - 8);
                safe.put("taskId", "…" + suffix);
            }
            safe.put("active", activeTask);
            safe.put("startedAt", raw.optLong("startedAt", 0L));
            safe.put("stepCount", raw.optInt("stepCount", 0));
            safe.put("mutationActions", raw.optInt("mutationActions", 0));

            JSONArray rawSteps = raw.optJSONArray("steps");
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
                    safeSteps.put(step);

                    String lower = tool.toLowerCase(Locale.ROOT);
                    if (lower.contains("inspect")
                            || lower.contains("screenshot")
                            || lower.contains("screen")) {
                        visualObservations++;
                    }
                }
            }
            safe.put("steps", safeSteps);
            safe.put("visualObservations", visualObservations);
        } catch (Exception ignored) {}
        return safe;
    }

    private static boolean duplicatesLast(JSONArray events,
                                          String stage,
                                          String tool) {
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

    private static String formatTime(long at) {
        if (at <= 0L) return "--:--:--";
        try {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(at));
        } catch (Exception ignored) {
            return String.valueOf(at);
        }
    }
}
