package com.crewpocket.helper;

import org.json.JSONObject;

/**
 * Executes the implementation layer for low-risk Runtime tools.
 *
 * Policy remains in NativeGeminiLiveClient: this class does not decide whether
 * a tool may run, own a user generation, complete a task, or authorize SEND.
 */
final class RuntimeToolExecutor {
    interface Environment {
        JSONObject helperPost(String endpoint, JSONObject payload) throws Exception;
    }

    private final NotebookToolHandler notebookToolHandler;
    private final LiveVisionController visionController;
    private final Environment environment;

    RuntimeToolExecutor(
            NotebookToolHandler notebookToolHandler,
            LiveVisionController visionController,
            Environment environment) {
        this.notebookToolHandler = notebookToolHandler;
        this.visionController = visionController;
        this.environment = environment;
    }

    boolean handles(String name) {
        return RuntimeToolRouting.handles(name);
    }

    JSONObject execute(String name, JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        if ("read_web_page".equals(name)) {
            return SafeWebPageReader.read(safeArgs.optString("url", ""));
        }
        if ("take_screenshot".equals(name)) {
            return captureAndSendScreen();
        }
        if (NotebookToolHandler.handles(name)) {
            return notebookToolHandler.execute(name, safeArgs);
        }
        if ("schedule_reminder".equals(name)) {
            return scheduleReminder(safeArgs);
        }
        if ("list_active_schedules".equals(name)) {
            return listSchedules();
        }
        if ("cancel_schedule".equals(name)) {
            return cancelSchedule(safeArgs);
        }
        throw new IllegalArgumentException("Unsupported RuntimeToolExecutor tool: " + name);
    }

    private JSONObject captureAndSendScreen() throws Exception {
        JSONObject capture = environment.helperPost(
                "/screenshot", new JSONObject());
        if (!capture.optBoolean("success")) return capture;
        String path = capture.optString(
                "latestPath", capture.optString("path", ""));
        if (path.isEmpty()) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "截圖未提供檔案路徑");
        }
        if (!visionController.sendImageFile(path, true)) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "截圖已取得，但 Gemini 連線不可用");
        }
        return new JSONObject()
                .put("success", true)
                .put("silent", capture.optBoolean("silent"))
                .put("message", "最新手機螢幕已傳送，請只依這張畫面回答。");
    }

    private JSONObject scheduleReminder(JSONObject args) throws Exception {
        int delay = (int) args.optDouble("delay_seconds", 60);
        String message = args.optString("message", args.optString("label", "時間到了"));
        String label = args.optString("label", delay + "秒後提醒");
        ScheduledTaskManager.ScheduledTask task =
                scheduledTaskManager().scheduleReminder(label, delay, message);
        return new JSONObject()
                .put("success", true)
                .put("task", task.toJson())
                .put("message", "已設定計時器：" + label);
    }

    private JSONObject listSchedules() throws Exception {
        ScheduledTaskManager manager = scheduledTaskManager();
        return new JSONObject()
                .put("success", true)
                .put("tasks", manager.getActiveTasksJson())
                .put("summary", manager.getActiveTasksSummaryText());
    }

    private JSONObject cancelSchedule(JSONObject args) throws Exception {
        boolean all = args.optBoolean("cancel_all", false);
        String taskId = args.optString("task_id", args.optString("label_hint", ""));
        ScheduledTaskManager manager = scheduledTaskManager();
        if (all) {
            return new JSONObject()
                    .put("success", true)
                    .put("cancelledCount", manager.cancelAllTasks())
                    .put("message", "已取消所有計時器與畫面巡檢");
        }
        boolean ok = manager.cancelTask(taskId);
        return new JSONObject()
                .put("success", ok)
                .put("message", ok ? "已成功取消該計時器" : "找不到指定計時器或巡檢任務");
    }

    private ScheduledTaskManager scheduledTaskManager() {
        CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        return ScheduledTaskManager.getInstance(
                service != null ? service : MainActivity.class.cast(null));
    }
}
