package com.crewpocket.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages background timers, reminders, screen monitoring and one-shot
 * wait-then-action tasks.
 *
 * Pending actions are deterministic Runtime work. They do not call Gemini while
 * waiting and are scoped to the foreground package captured at creation time.
 */
public class ScheduledTaskManager {
    interface PendingActionExecutor {
        JSONObject execute(String action, String target, String text)
                throws Exception;
    }

    private static ScheduledTaskManager instance;
    private final Context context;
    private final Handler mainHandler;
    private final Vibrator vibrator;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    public static class ScheduledTask {
        public String id;
        public String type; // reminder, screen_monitor, condition_wait, pending_action
        public String label;
        public String message;
        public long createdAt;
        public long targetTime;
        public int intervalSeconds;
        public int durationMinutes;
        public String conditionType;
        public String conditionText;
        public String action;
        public String actionTarget;
        public String actionText;
        public String packageName;
        public String baselineFingerprint;
        public boolean reportSpeech;
        public int checkCount;
        public boolean cancelled;
        public Runnable runnable;
        PendingActionExecutor pendingActionExecutor;

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("type", type);
                obj.put("label", label);
                obj.put("message", message);
                obj.put("createdAt", createdAt);
                obj.put("targetTime", targetTime);
                long remaining = Math.max(
                        0, (targetTime - System.currentTimeMillis()) / 1000);
                obj.put("remainingSeconds", remaining);
                obj.put("intervalSeconds", intervalSeconds);
                obj.put("conditionType", conditionType);
                obj.put("conditionText", conditionText);
                obj.put("checkCount", checkCount);
                if ("pending_action".equals(type)) {
                    obj.put("action", action);
                    obj.put("actionTarget", actionTarget);
                    obj.put(
                            "actionTextLength",
                            actionText == null ? 0 : actionText.length());
                    obj.put("package", packageName);
                }
            } catch (Exception ignored) {}
            return obj;
        }
    }

    private final ConcurrentHashMap<String, ScheduledTask> activeTasks =
            new ConcurrentHashMap<String, ScheduledTask>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public static synchronized ScheduledTaskManager getInstance(Context context) {
        if (instance == null) {
            if (context == null) {
                throw new IllegalArgumentException(
                        "ScheduledTaskManager requires app context");
            }
            instance = new ScheduledTaskManager(
                    context.getApplicationContext());
        }
        return instance;
    }

    private ScheduledTaskManager(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.vibrator =
                (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        try {
            this.tts = new TextToSpeech(
                    context,
                    new TextToSpeech.OnInitListener() {
                        @Override public void onInit(int status) {
                            if (status == TextToSpeech.SUCCESS) {
                                try {
                                    tts.setLanguage(
                                            Locale.TRADITIONAL_CHINESE);
                                    ttsReady = true;
                                } catch (Exception ignored) {}
                            }
                        }
                    });
        } catch (Exception ignored) {}
    }

    public ScheduledTask scheduleReminder(
            String label, int delaySeconds, final String message) {
        final ScheduledTask task = new ScheduledTask();
        task.id = "timer_" + idCounter.getAndIncrement();
        task.type = "reminder";
        task.label = nonEmpty(label)
                ? label.trim()
                : (delaySeconds + "秒後提醒");
        task.message = nonEmpty(message) ? message.trim() : task.label;
        task.createdAt = System.currentTimeMillis();
        task.targetTime = task.createdAt + (delaySeconds * 1000L);
        task.intervalSeconds = 0;
        task.cancelled = false;

        task.runnable = new Runnable() {
            @Override public void run() {
                if (task.cancelled) return;
                activeTasks.remove(task.id);
                triggerAlarm(task.label, task.message);
            }
        };

        activeTasks.put(task.id, task);
        mainHandler.postDelayed(task.runnable, delaySeconds * 1000L);
        return task;
    }

    public ScheduledTask startScreenMonitor(
            String label,
            final int intervalSec,
            final int durationMin,
            final String condition,
            final boolean speech) {
        final ScheduledTask task = new ScheduledTask();
        task.id = "monitor_" + idCounter.getAndIncrement();
        task.type = nonEmpty(condition)
                ? "condition_wait"
                : "screen_monitor";
        task.label = nonEmpty(label)
                ? label.trim()
                : ("每" + intervalSec + "秒檢查畫面");
        task.conditionType = nonEmpty(condition)
                ? PendingActionPolicy.CONDITION_TEXT_APPEARS
                : "";
        task.conditionText = condition == null ? "" : condition.trim();
        task.intervalSeconds = Math.max(5, intervalSec);
        task.durationMinutes = durationMin > 0 ? durationMin : 10;
        task.reportSpeech = speech;
        task.createdAt = System.currentTimeMillis();
        task.targetTime =
                task.createdAt + (task.durationMinutes * 60 * 1000L);
        task.checkCount = 0;
        task.cancelled = false;

        task.runnable = new Runnable() {
            @Override public void run() {
                if (task.cancelled) return;
                if (System.currentTimeMillis() >= task.targetTime) {
                    activeTasks.remove(task.id);
                    return;
                }

                task.checkCount++;
                performScreenCheck(task);

                if (!task.cancelled) {
                    mainHandler.postDelayed(
                            task.runnable,
                            task.intervalSeconds * 1000L);
                }
            }
        };

        activeTasks.put(task.id, task);
        mainHandler.postDelayed(
                task.runnable, task.intervalSeconds * 1000L);
        return task;
    }

    ScheduledTask startPendingAction(
            String label,
            String conditionType,
            String conditionText,
            String action,
            String actionTarget,
            String actionText,
            int intervalSec,
            int durationMin,
            PendingActionExecutor executor) throws Exception {
        if (executor == null
                && !PendingActionPolicy.ACTION_NOTIFY.equals(action)) {
            throw new Exception("缺少 Pending Action executor");
        }

        CrewAccessibilityService service =
                CrewAccessibilityService.getInstance();
        if (service == null) {
            throw new Exception("需要啟用螢幕操作權限才能等待畫面條件");
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            throw new Exception("目前無法讀取前景 App 畫面");
        }

        final ScheduledTask task = new ScheduledTask();
        try {
            CharSequence pkg = root.getPackageName();
            task.packageName = pkg == null ? "" : pkg.toString().trim();
            if (task.packageName.isEmpty()) {
                throw new Exception("無法確認目前 App，未建立延後操作");
            }
            task.baselineFingerprint = fingerprint(root);
        } finally {
            root.recycle();
        }

        task.id = "pending_" + idCounter.getAndIncrement();
        task.type = "pending_action";
        task.label = nonEmpty(label)
                ? label.trim()
                : defaultPendingLabel(conditionText, action, actionTarget);
        task.conditionType = conditionType;
        task.conditionText =
                conditionText == null ? "" : conditionText.trim();
        task.action = action;
        task.actionTarget =
                actionTarget == null ? "" : actionTarget.trim();
        task.actionText = actionText == null ? "" : actionText;
        task.intervalSeconds = Math.max(5, Math.min(60, intervalSec));
        task.durationMinutes = Math.max(1, Math.min(60, durationMin));
        task.reportSpeech = true;
        task.createdAt = System.currentTimeMillis();
        task.targetTime =
                task.createdAt + (task.durationMinutes * 60 * 1000L);
        task.checkCount = 0;
        task.cancelled = false;
        task.pendingActionExecutor = executor;

        task.runnable = new Runnable() {
            @Override public void run() {
                if (task.cancelled) return;
                if (System.currentTimeMillis() >= task.targetTime) {
                    task.cancelled = true;
                    activeTasks.remove(task.id);
                    triggerAlarm(
                            "等待已逾時",
                            task.label + "，沒有執行後續操作。");
                    return;
                }

                task.checkCount++;
                performPendingActionCheck(task);

                if (!task.cancelled) {
                    mainHandler.postDelayed(
                            task.runnable,
                            task.intervalSeconds * 1000L);
                }
            }
        };

        activeTasks.put(task.id, task);
        mainHandler.postDelayed(
                task.runnable, task.intervalSeconds * 1000L);
        return task;
    }

    private void performScreenCheck(ScheduledTask task) {
        try {
            CrewAccessibilityService service =
                    CrewAccessibilityService.getInstance();
            if (service == null) return;
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return;

            try {
                if (nonEmpty(task.conditionText)) {
                    boolean found = searchConditionInTree(
                            root,
                            task.conditionText
                                    .trim()
                                    .toLowerCase(Locale.ROOT));
                    if (found) {
                        task.cancelled = true;
                        activeTasks.remove(task.id);
                        triggerAlarm(
                                "目標條件已達成",
                                "畫面上已出現「"
                                        + task.conditionText
                                        + "」！");
                    }
                }
            } finally {
                root.recycle();
            }
        } catch (Exception ignored) {}
    }

    private void performPendingActionCheck(final ScheduledTask task) {
        try {
            CrewAccessibilityService service =
                    CrewAccessibilityService.getInstance();
            if (service == null) return;
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return;

            String beforeFingerprint = "";
            try {
                CharSequence pkg = root.getPackageName();
                String currentPackage =
                        pkg == null ? "" : pkg.toString().trim();

                // Same-app scope is mandatory. A task may keep waiting while
                // another app is foreground, but it can never act there.
                if (!task.packageName.equals(currentPackage)) return;

                if (!isConditionMet(task, root)) return;

                String guard = pendingActionGuard(task, root);
                if (!guard.isEmpty()) {
                    task.cancelled = true;
                    activeTasks.remove(task.id);
                    triggerAlarm(
                            "等待條件已達成，但沒有執行",
                            guard);
                    return;
                }

                beforeFingerprint = fingerprint(root);
                task.cancelled = true;
                activeTasks.remove(task.id);
            } finally {
                root.recycle();
            }

            if (PendingActionPolicy.ACTION_NOTIFY.equals(task.action)) {
                triggerAlarm("目標條件已達成", task.label);
                return;
            }

            final String fingerprintBeforeAction = beforeFingerprint;
            new Thread(
                    new Runnable() {
                        @Override public void run() {
                            executePendingAction(
                                    task,
                                    fingerprintBeforeAction);
                        }
                    },
                    "crew-pending-" + task.id).start();
        } catch (Exception ignored) {}
    }

    private void executePendingAction(
            final ScheduledTask task,
            String beforeFingerprint) {
        JSONObject result = new JSONObject();
        try {
            result = task.pendingActionExecutor.execute(
                    task.action,
                    task.actionTarget,
                    task.actionText);
        } catch (Exception error) {
            try {
                result.put("success", false)
                        .put(
                                "error",
                                error.getMessage() == null
                                        ? "PENDING_ACTION_FAILED"
                                        : error.getMessage());
            } catch (Exception ignored) {}
        }

        final JSONObject outcome = result;
        final boolean success = result.optBoolean("success", false)
                || "STEP_OK".equals(result.optString("stepResult", ""));
        final String verification = buildVerificationSummary(
                task,
                beforeFingerprint,
                result);

        mainHandler.post(
                new Runnable() {
                    @Override public void run() {
                        if (success) {
                            triggerAlarm(
                                    "等待條件已達成",
                                    "已執行「"
                                            + actionSummary(task)
                                            + "」。"
                                            + (verification.isEmpty()
                                                    ? ""
                                                    : verification));
                        } else {
                            triggerAlarm(
                                    "等待條件已達成，但操作失敗",
                                    actionSummary(task)
                                            + "："
                                            + outcome.optString(
                                                    "error",
                                                    "Runtime 無法確認操作成功"));
                        }
                    }
                });
    }

    private boolean isConditionMet(
            ScheduledTask task,
            AccessibilityNodeInfo root) {
        if (PendingActionPolicy.CONDITION_SCREEN_CHANGE.equals(
                task.conditionType)) {
            String current = fingerprint(root);
            return !current.isEmpty()
                    && !current.equals(task.baselineFingerprint);
        }

        boolean found = searchConditionInTree(
                root,
                task.conditionText
                        .trim()
                        .toLowerCase(Locale.ROOT));
        if (PendingActionPolicy.CONDITION_TEXT_DISAPPEARS.equals(
                task.conditionType)) {
            return !found;
        }
        return found;
    }

    private String pendingActionGuard(
            ScheduledTask task,
            AccessibilityNodeInfo root) {
        if (PendingActionPolicy.ACTION_TAP.equals(task.action)) {
            if (!searchConditionInTree(
                    root,
                    task.actionTarget
                            .trim()
                            .toLowerCase(Locale.ROOT))) {
                return "條件成立時找不到目標「"
                        + task.actionTarget
                        + "」，已取消以避免點錯。";
            }
            if (PendingActionPolicy.looksLikeGenericCommitTarget(
                            task.actionTarget)
                    && screenContainsHighRiskCommit(root)) {
                return "目前畫面涉及付款、購買、下單或刪除等高風險流程，"
                        + "延後自動點擊已被阻擋。";
            }
        }

        if (PendingActionPolicy.ACTION_TYPE.equals(task.action)) {
            AccessibilityNodeInfo focused = null;
            try {
                focused = root.findFocus(
                        AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null || !focused.isEditable()) {
                    return "條件成立時沒有可信的已聚焦輸入框，已取消文字輸入。";
                }
            } finally {
                if (focused != null && focused != root) {
                    try { focused.recycle(); } catch (Exception ignored) {}
                }
            }
        }

        if (PendingActionPolicy.ACTION_COMMIT_SEARCH.equals(task.action)
                && !hasSearchEditable(root)) {
            return "條件成立時沒有可信的搜尋輸入框，已取消 Search/Enter。";
        }

        return "";
    }

    private boolean searchConditionInTree(
            AccessibilityNodeInfo node,
            String query) {
        if (node == null) return false;
        String text = node.getText() == null
                ? ""
                : node.getText().toString().toLowerCase(Locale.ROOT);
        String desc = node.getContentDescription() == null
                ? ""
                : node.getContentDescription()
                        .toString()
                        .toLowerCase(Locale.ROOT);
        String id = node.getViewIdResourceName() == null
                ? ""
                : node.getViewIdResourceName()
                        .toLowerCase(Locale.ROOT);
        if (text.contains(query)
                || desc.contains(query)
                || id.contains(query)) {
            return true;
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (searchConditionInTree(child, query)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean screenContainsHighRiskCommit(
            AccessibilityNodeInfo node) {
        if (node == null) return false;
        String metadata =
                (node.getText() == null ? "" : node.getText())
                        + " "
                        + (node.getContentDescription() == null
                                ? ""
                                : node.getContentDescription());
        if (PendingActionPolicy.looksLikeHighRiskCommit(metadata)) {
            return true;
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (screenContainsHighRiskCommit(child)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean hasSearchEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;

        if (node.isEditable()) {
            String metadata =
                    ((node.getViewIdResourceName() == null
                                    ? ""
                                    : node.getViewIdResourceName())
                            + " "
                            + (node.getContentDescription() == null
                                    ? ""
                                    : node.getContentDescription())
                            + " "
                            + (node.getClassName() == null
                                    ? ""
                                    : node.getClassName()))
                            .toLowerCase(Locale.ROOT);
            if (metadata.contains("search")
                    || metadata.contains("query")
                    || metadata.contains("搜尋")
                    || metadata.contains("搜索")) {
                return true;
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (hasSearchEditable(child)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private String fingerprint(AccessibilityNodeInfo root) {
        StringBuilder builder = new StringBuilder();
        appendFingerprint(root, builder, 0);
        return Integer.toHexString(builder.toString().hashCode());
    }

    private void appendFingerprint(
            AccessibilityNodeInfo node,
            StringBuilder out,
            int depth) {
        if (node == null || depth > 18 || out.length() > 12000) return;
        out.append(node.getText() == null ? "" : node.getText())
                .append('|')
                .append(
                        node.getContentDescription() == null
                                ? ""
                                : node.getContentDescription())
                .append('|')
                .append(
                        node.getViewIdResourceName() == null
                                ? ""
                                : node.getViewIdResourceName())
                .append('|')
                .append(node.getChildCount())
                .append(';');

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                appendFingerprint(child, out, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private String buildVerificationSummary(
            ScheduledTask task,
            String beforeFingerprint,
            JSONObject result) {
        if (result == null) return "";
        JSONObject after = result.optJSONObject("after");
        if (after != null) {
            String afterPackage = after.optString("package", "");
            if (!afterPackage.isEmpty()
                    && !PendingActionPolicy.ACTION_HOME.equals(task.action)
                    && !PendingActionPolicy.ACTION_BACK.equals(task.action)
                    && !task.packageName.equals(afterPackage)) {
                return "執行後 App 已改變，請自行確認結果。";
            }
            if (after.optBoolean("fresh", false)) {
                return "Runtime 已取得執行後畫面。";
            }
        }
        if (!beforeFingerprint.isEmpty()
                && result.optBoolean("screenChanged", false)) {
            return "Runtime 已確認畫面有變化。";
        }
        return "";
    }

    private String actionSummary(ScheduledTask task) {
        if (PendingActionPolicy.ACTION_TAP.equals(task.action)) {
            return "點擊 " + task.actionTarget;
        }
        if (PendingActionPolicy.ACTION_TYPE.equals(task.action)) {
            return "輸入文字";
        }
        if (PendingActionPolicy.ACTION_COMMIT_SEARCH.equals(task.action)) {
            return "提交搜尋";
        }
        if (PendingActionPolicy.ACTION_BACK.equals(task.action)) {
            return "返回";
        }
        if (PendingActionPolicy.ACTION_HOME.equals(task.action)) {
            return "回到主畫面";
        }
        return "通知";
    }

    private String defaultPendingLabel(
            String conditionText,
            String action,
            String actionTarget) {
        String condition = nonEmpty(conditionText)
                ? "等「" + conditionText.trim() + "」"
                : "等待畫面變化";
        String next = PendingActionPolicy.ACTION_TAP.equals(action)
                ? "後點「" + actionTarget + "」"
                : "後執行 " + action;
        return condition + next;
    }

    private void triggerAlarm(String title, String message) {
        try {
            if (vibrator != null) {
                vibrator.vibrate(
                        new long[]{0, 200, 100, 200, 100, 300},
                        -1);
            }
        } catch (Exception ignored) {}
        speak(title + "。" + message);
    }

    public void speak(String text) {
        if (!nonEmpty(text)) return;
        try {
            if (tts != null && ttsReady) {
                tts.speak(
                        text,
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "scheduled_alert");
            }
        } catch (Exception ignored) {}
    }

    public boolean cancelTask(String idOrHint) {
        if (!nonEmpty(idOrHint)) return false;
        String query = idOrHint.trim().toLowerCase(Locale.ROOT);
        boolean found = false;

        Iterator<ScheduledTask> it = activeTasks.values().iterator();
        while (it.hasNext()) {
            ScheduledTask task = it.next();
            if (task.id.equalsIgnoreCase(query)
                    || (task.label != null
                            && task.label
                                    .toLowerCase(Locale.ROOT)
                                    .contains(query))) {
                task.cancelled = true;
                if (task.runnable != null) {
                    mainHandler.removeCallbacks(task.runnable);
                }
                it.remove();
                found = true;
            }
        }
        return found;
    }

    public int cancelAllTasks() {
        int count = activeTasks.size();
        for (ScheduledTask task : activeTasks.values()) {
            task.cancelled = true;
            if (task.runnable != null) {
                mainHandler.removeCallbacks(task.runnable);
            }
        }
        activeTasks.clear();
        return count;
    }

    public JSONArray getActiveTasksJson() {
        JSONArray arr = new JSONArray();
        for (ScheduledTask task : activeTasks.values()) {
            if (!task.cancelled) arr.put(task.toJson());
        }
        return arr;
    }

    public String getActiveTasksSummaryText() {
        if (activeTasks.isEmpty()) {
            return "目前沒有任何進行中的計時器、巡檢或等待後續操作。";
        }

        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (ScheduledTask task : activeTasks.values()) {
            if (task.cancelled) continue;
            long rem = Math.max(
                    0,
                    (task.targetTime - System.currentTimeMillis())
                            / 1000);
            int m = (int) (rem / 60);
            int s = (int) (rem % 60);
            String remStr = m > 0
                    ? (m + "分" + s + "秒")
                    : (s + "秒");
            sb.append(idx++)
                    .append(". [")
                    .append(task.id)
                    .append("] ")
                    .append(task.label)
                    .append(" (剩餘 ")
                    .append(remStr)
                    .append(")");
            if (nonEmpty(task.conditionText)) {
                sb.append(" [目標: ")
                        .append(task.conditionText)
                        .append("]");
            }
            if ("pending_action".equals(task.type)) {
                sb.append(" [後續: ")
                        .append(actionSummary(task))
                        .append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static boolean nonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
