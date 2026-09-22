package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runtime-owned special TaskRecipe for an explicitly authorized conversation.
 *
 * Recipe:
 * SEND -> WAIT_NEW_MESSAGE -> INSPECT -> MODEL_REPLY -> SEND -> repeat.
 *
 * Generic TaskRecipe safety is unchanged: TYPE/SEND are still not reusable
 * generic recipe steps. This runtime owns the explicit continuous-send lease.
 */
final class ConversationLoopRuntime {
    interface Host {
        JSONObject verifyRecipient(String recipient) throws Exception;
        void sendInternalDirective(String text);
        void reportStage(String text);
        boolean hasLiveSession();
    }

    private enum State {
        IDLE,
        WAITING,
        AWAKE,
        SENDING,
        PAUSED,
        STOPPED
    }

    private final Context appContext;
    private final Host host;

    private boolean active;
    private String recipient = "";
    private String packageName = "";
    private String newMessageMarker = "";
    private String waitTaskId = "";
    private long expiresAt;
    private int replyCount;
    private boolean sendLeaseAvailable;
    private State state = State.IDLE;

    ConversationLoopRuntime(Context context, Host host) {
        if (context == null) {
            throw new IllegalArgumentException("context required");
        }
        if (host == null) {
            throw new IllegalArgumentException("host required");
        }
        this.appContext = context.getApplicationContext();
        this.host = host;
    }

    synchronized JSONObject execute(JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        String action = ConversationLoopPolicy.normalizeAction(
                safeArgs.optString("action", ""));

        if (ConversationLoopPolicy.ACTION_START.equals(action)) {
            return start(
                    safeArgs.optString("recipient", ""),
                    safeArgs.optString("new_message_marker", ""),
                    safeArgs.optInt(
                            "duration_minutes",
                            ConversationLoopPolicy.DEFAULT_DURATION_MINUTES));
        }
        if (ConversationLoopPolicy.ACTION_WAIT.equals(action)) {
            return waitAgain();
        }
        if (ConversationLoopPolicy.ACTION_STOP.equals(action)) {
            return stop("MODEL_STOP");
        }
        if (ConversationLoopPolicy.ACTION_STATUS.equals(action)) {
            return statusJson(true);
        }
        return new JSONObject()
                .put("success", false)
                .put("error", "BAD_CONVERSATION_LOOP_ACTION")
                .put(
                        "instruction",
                        "action 只支援 START、WAIT、STOP、STATUS。");
    }

    synchronized boolean isActive() {
        expireIfNeeded();
        return active;
    }

    synchronized boolean hasSendLease() {
        expireIfNeeded();
        return active && sendLeaseAvailable;
    }

    synchronized String recipient() {
        return recipient;
    }

    synchronized String packageName() {
        return packageName;
    }

    synchronized void beforeSend() {
        expireIfNeeded();
        if (!active || !sendLeaseAvailable) return;
        cancelWaitLocked();
        sendLeaseAvailable = false;
        state = State.SENDING;
    }

    synchronized void afterSend(boolean success) {
        expireIfNeeded();
        if (!active) return;

        if (!success) {
            state = State.PAUSED;
            sendLeaseAvailable = false;
            host.reportStage(
                    "對談模式暫停：上一則訊息沒有被 Runtime 驗證送出");
            return;
        }

        replyCount++;
        sendLeaseAvailable = false;
        try {
            armWaitLocked();
        } catch (Exception error) {
            state = State.PAUSED;
            host.reportStage(
                    "對談模式暫停：無法等待新訊息");
        }
    }

    synchronized JSONObject stopFromUser() {
        return stop("USER_STOP");
    }

    synchronized JSONObject modelState() {
        return statusJson(false);
    }

    synchronized boolean sameVerifiedPackage(String currentPackage) {
        return active
                && !packageName.isEmpty()
                && packageName.equals(
                        currentPackage == null ? "" : currentPackage.trim());
    }

    private JSONObject start(
            String requestedRecipient,
            String marker,
            int durationMinutes) throws Exception {
        String target = requestedRecipient == null
                ? "" : requestedRecipient.trim();
        if (!ConversationLoopPolicy.validRecipient(target)) {
            return new JSONObject()
                    .put("success", false)
                    .put("taskState", "NEED_USER")
                    .put("error", "CONVERSATION_RECIPIENT_REQUIRED")
                    .put(
                            "instruction",
                            "持續對談必須有明確收件人；不要猜。");
        }

        JSONObject verification = host.verifyRecipient(target);
        if (verification == null
                || !verification.optBoolean("success", false)) {
            return verification == null
                    ? new JSONObject()
                            .put("success", false)
                            .put("error", "RECIPIENT_CHAT_NOT_VERIFIED")
                    : verification;
        }

        cancelWaitLocked();
        active = true;
        recipient = target;
        packageName = verification.optString("package", "").trim();
        newMessageMarker = marker == null ? "" : marker.trim();
        int duration =
                ConversationLoopPolicy.clampDurationMinutes(
                        durationMinutes);
        expiresAt = System.currentTimeMillis()
                + duration * 60_000L;
        replyCount = 0;

        // START itself is explicit authorization for one initial reply. A
        // watcher is armed at the same time; beforeSend() cancels it so the
        // user's own outgoing message cannot wake the loop.
        sendLeaseAvailable = true;
        armWaitLocked();

        host.reportStage(
                "對談模式已開啟："
                        + recipient
                        + "；等待新訊息時由 Runtime 監控");
        return statusJson(true)
                .put("success", true)
                .put("taskState", "WAITING_BACKGROUND")
                .put(
                        "instruction",
                        "Conversation Loop 已啟用。若目前已有尚未回覆的新訊息，可 inspect_ui 後回覆；"
                                + "否則保持安靜等待 Runtime 喚醒。每次成功 send_text 後 Runtime 會自動重新等待。");
    }

    private JSONObject waitAgain() throws Exception {
        expireIfNeeded();
        if (!active) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "CONVERSATION_LOOP_NOT_ACTIVE");
        }
        sendLeaseAvailable = false;
        armWaitLocked();
        return statusJson(true)
                .put("success", true)
                .put("taskState", "WAITING_BACKGROUND")
                .put(
                        "instruction",
                        "沒有確認到新的對方訊息；Runtime 已重新等待。不要輪詢 inspect_ui。");
    }

    private JSONObject stop(String reason) {
        cancelWaitLocked();
        boolean wasActive = active;
        active = false;
        sendLeaseAvailable = false;
        state = State.STOPPED;
        String oldRecipient = recipient;
        recipient = "";
        packageName = "";
        newMessageMarker = "";
        expiresAt = 0L;
        host.reportStage(
                wasActive
                        ? "對談模式已停止"
                        : "目前沒有進行中的對談模式");

        JSONObject out = new JSONObject();
        try {
            out.put("success", true)
                    .put("stopped", wasActive)
                    .put("reason", reason == null ? "" : reason)
                    .put("recipient", oldRecipient)
                    .put("recipeType", TaskRecipePolicy.SPECIAL_CONVERSATION_LOOP);
        } catch (Exception ignored) {}
        return out;
    }

    private void armWaitLocked() throws Exception {
        cancelWaitLocked();
        expireIfNeeded();
        if (!active) return;

        int remainingMinutes = Math.max(
                1,
                (int) Math.ceil(
                        (expiresAt - System.currentTimeMillis())
                                / 60_000.0));
        final String expectedRecipient = recipient;
        final String expectedPackage = packageName;

        ScheduledTaskManager.ScheduledTask task =
                ScheduledTaskManager.getInstance(appContext)
                        .startConditionCallback(
                                "對談模式：等待 "
                                        + expectedRecipient
                                        + " 新訊息",
                                ConversationLoopPolicy.waitCondition(
                                        newMessageMarker),
                                newMessageMarker,
                                5,
                                remainingMinutes,
                                new ScheduledTaskManager
                                        .PendingConditionListener() {
                                    @Override public void onConditionMet(
                                            ScheduledTaskManager.ScheduledTask task) {
                                        onWaitTriggered(
                                                task,
                                                expectedRecipient,
                                                expectedPackage);
                                    }

                                    @Override public void onTimeout(
                                            ScheduledTaskManager.ScheduledTask task) {
                                        onWaitTimeout(task);
                                    }
                                });
        waitTaskId = task.id;
        if (packageName.isEmpty()) {
            packageName = task.packageName;
        }
        state = State.WAITING;
    }

    private synchronized void onWaitTriggered(
            ScheduledTaskManager.ScheduledTask task,
            String expectedRecipient,
            String expectedPackage) {
        if (!active
                || task == null
                || !task.id.equals(waitTaskId)
                || !expectedRecipient.equals(recipient)
                || (!expectedPackage.isEmpty()
                        && !expectedPackage.equals(packageName))) {
            return;
        }

        waitTaskId = "";
        state = State.AWAKE;
        sendLeaseAvailable = true;

        if (!host.hasLiveSession()) {
            state = State.PAUSED;
            sendLeaseAvailable = false;
            return;
        }

        host.reportStage(
                "對談模式：偵測到畫面更新，正在確認是否有新訊息");
        host.sendInternalDirective(
                "【CONVERSATION LOOP EVENT】Runtime 偵測到已驗證聊天室「"
                        + recipient
                        + "」有新的畫面變化。"
                        + "現在只做一輪：先呼叫 inspect_ui 一次，判斷最新變化是否真的是對方的新訊息。"
                        + "如果是，根據最新訊息自然產生一則簡短回覆，然後呼叫 send_text 一次；"
                        + "send_text 成功後 Runtime 會自動重新等待。"
                        + "如果只是 typing indicator、時間、動畫或沒有新對方訊息，"
                        + "呼叫 conversation_loop(action=WAIT) 重新掛起。"
                        + "不要切換到其他收件人，不要自行輪詢。");
    }

    private synchronized void onWaitTimeout(
            ScheduledTaskManager.ScheduledTask task) {
        if (task == null || !task.id.equals(waitTaskId)) return;
        waitTaskId = "";
        active = false;
        sendLeaseAvailable = false;
        state = State.STOPPED;
        host.reportStage("對談模式已逾時停止");
    }

    private void cancelWaitLocked() {
        if (waitTaskId.isEmpty()) return;
        try {
            ScheduledTaskManager.getInstance(appContext)
                    .cancelTask(waitTaskId);
        } catch (Exception ignored) {}
        waitTaskId = "";
    }

    private void expireIfNeeded() {
        if (active
                && expiresAt > 0L
                && System.currentTimeMillis() >= expiresAt) {
            cancelWaitLocked();
            active = false;
            sendLeaseAvailable = false;
            state = State.STOPPED;
        }
    }

    private JSONObject statusJson(boolean includeRecipe) {
        JSONObject out = new JSONObject();
        try {
            out.put("active", active)
                    .put("state", state.name())
                    .put("recipient", recipient)
                    .put("package", packageName)
                    .put("replyCount", replyCount)
                    .put("sendLease", sendLeaseAvailable)
                    .put("waitTaskId", waitTaskId)
                    .put("recipeType", ConversationLoopPolicy.RECIPE_TYPE);
            if (expiresAt > 0L) {
                out.put(
                        "remainingSeconds",
                        Math.max(
                                0L,
                                (expiresAt
                                                - System.currentTimeMillis())
                                        / 1000L));
            }
            if (includeRecipe) {
                JSONArray steps = new JSONArray()
                        .put("SEND")
                        .put("WAIT_NEW_MESSAGE")
                        .put("INSPECT")
                        .put("MODEL_REPLY")
                        .put("SEND");
                out.put("recipeSteps", steps);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
