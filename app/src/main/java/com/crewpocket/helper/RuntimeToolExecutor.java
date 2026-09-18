package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONObject;

/**
 * Executes the implementation layer for low-risk Runtime tools.
 *
 * Policy remains in NativeGeminiLiveClient: this class does not decide whether
 * a tool may run, own a user generation, complete a task, or authorize SEND.
 */
final class RuntimeToolExecutor {
    interface Environment {
        String currentForegroundPackageName();
    }

    private final Context appContext;
    private final NotebookToolHandler notebookToolHandler;
    private final AppPlaybookStore appPlaybookStore;
    private final Environment environment;

    RuntimeToolExecutor(
            Context appContext,
            NotebookToolHandler notebookToolHandler,
            AppPlaybookStore appPlaybookStore,
            Environment environment) {
        this.appContext = appContext;
        this.notebookToolHandler = notebookToolHandler;
        this.appPlaybookStore = appPlaybookStore;
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
        if ("list_app_guidance".equals(name)) {
            return listCurrentAppGuidance();
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

    private JSONObject listCurrentAppGuidance() {
        String packageName = environment == null
                ? ""
                : environment.currentForegroundPackageName();
        if (packageName == null) packageName = "";
        packageName = packageName.trim();
        if (packageName.isEmpty()) {
            return blocked("APP_CONTEXT_UNAVAILABLE", "目前無法確認前景 App。");
        }

        JSONObject context = appPlaybookStore == null
                ? new JSONObject()
                : appPlaybookStore.modelContext(packageName);
        JSONObject out = new JSONObject();
        try {
            out.put("success", true)
                    .put("package", packageName)
                    .put("app", AppRuntimeRegistry.displayName(appContext, packageName));
            if (context.length() > 0) out.put("appPlaybook", context);
            else out.put("message", "目前這個 App 還沒有內建或自訂經驗。");
        } catch (Exception ignored) {}
        return out;
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

    private static JSONObject blocked(String code, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", false)
                    .put("blocked", true)
                    .put("error", code)
                    .put("message", message);
        } catch (Exception ignored) {}
        return result;
    }
}
