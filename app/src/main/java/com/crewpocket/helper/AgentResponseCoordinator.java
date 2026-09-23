package com.crewpocket.helper;

import android.os.Handler;
import android.os.Looper;

/**
 * Owns Agent response waiting, watchdog, post-action observation requests and
 * the final-speech contract.
 *
 * Tool execution and task persistence remain outside this class. It coordinates
 * only what should happen after Runtime has handed control back to Gemini.
 */
final class AgentResponseCoordinator {
    interface Host {
        void reportStage(String text);
        boolean sendInternalDirective(String text);
        void finishTask(AgentTaskRecord task, String reason, String finalReply);
    }

    private static final long FINAL_RESPONSE_WAIT_MS = 12_000L;

    private final AgentTaskCoordinator tasks;
    private final Host host;
    private final Handler watchdogHandler =
            new Handler(Looper.getMainLooper());

    private Runnable responseWatchdog;

    AgentResponseCoordinator(
            AgentTaskCoordinator tasks,
            Host host) {
        this.tasks = tasks;
        this.host = host;
    }

    void clear() {
        synchronized (tasks.monitor()) {
            clearLocked();
        }
    }

    void onModelResponse() {
        clear();
    }

    void requestConclusion(
            AgentTaskRecord task,
            String reason) {
        if (task == null) return;
        synchronized (tasks.monitor()) {
            if (!tasks.isActive(task)
                    || task.finished
                    || task.cancelled) {
                return;
            }
            task.awaitingModel = true;
            task.userVisibleReplyProducedSinceLastAction = false;
            task.finalSpeechRetryCount = 0;
            task.status = reason == null ? "" : reason;
        }

        host.reportStage(task.status);
        sendDirectiveOrFinish(task,
                "【Agent 系統狀態】" + task.status
                        + " 不要再呼叫工具；請以目前已知的工具結果，向使用者給出清楚、簡短的最終結論。"
                        + "這個 active task 不可靜默結束；必須輸出一個簡短 AUDIO 回覆。"
                        + "不要說『抱歉』或『對不起』；直接說明已完成的部分與目前唯一卡點。",
                "結論指令");
    }

    void scheduleWatchdog(final AgentTaskRecord task) {
        if (task == null) return;
        synchronized (tasks.monitor()) {
            clearLocked();
            responseWatchdog = new Runnable() {
                @Override public void run() {
                    boolean shouldPrompt = false;
                    synchronized (tasks.monitor()) {
                        if (tasks.isActive(task)
                                && task.awaitingModel
                                && !task.finished
                                && !task.cancelled
                                && !task.watchdogPrompted) {
                            task.watchdogPrompted = true;
                            shouldPrompt = true;
                        }
                    }
                    if (!shouldPrompt) return;

                    String reason = "任務未完成：Gemini 未在工具結果後繼續";
                    synchronized (tasks.monitor()) {
                        if (!tasks.isActive(task)
                                || task.finished
                                || task.cancelled) {
                            return;
                        }
                        task.awaitingModel = false;
                        task.status = reason;
                    }
                    host.reportStage(reason);
                    host.finishTask(
                            task,
                            reason,
                            task.finalReply);
                }
            };
            watchdogHandler.postDelayed(
                    responseWatchdog,
                    FINAL_RESPONSE_WAIT_MS);
        }
    }

    void finishIfAwaitingModel() {
        AgentTaskRecord task = tasks.active();
        if (task == null || !task.awaitingModel || task.finished) return;

        if (task.requiresPostActionInspection) {
            requestPostActionInspection(task);
            return;
        }

        boolean completionReady =
                AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                        task.lastTaskState,
                        task.lastToolName,
                        task.requiresPostActionInspection,
                        task.blockedReason != null,
                        task.mutationActions);
        if (!completionReady) {
            requestNextToolAfterIntermediateReply(task);
            return;
        }

        // Completion evidence is authoritative. Do not force another model turn
        // merely to manufacture a final spoken acknowledgement. Gemini may have
        // already spoken; simple success may also stay quiet.
        host.finishTask(
                task,
                task.blockedReason == null ? "任務完成" : task.blockedReason,
                task.finalReply);
    }

    void requestPostActionInspection(AgentTaskRecord task) {
        if (task == null) return;
        synchronized (tasks.monitor()) {
            if (!tasks.isActive(task)
                    || task.finished
                    || task.cancelled
                    || !task.requiresPostActionInspection
                    || task.postActionInspectionPrompted) {
                return;
            }
            task.postActionInspectionPrompted = true;
            task.awaitingModel = true;
            task.status = "正在驗證上一個操作的實際畫面";
        }

        host.reportStage(task.status);
        sendDirectiveOrFinish(task,
                "【Runtime 必要驗證】上一個手機操作只代表動作已執行，尚未證明任務完成。"
                        + "現在必須呼叫 inspect_ui；Runtime 會送一張 fresh screenshot。"
                        + "直接看最新畫面決定下一步；在取得該證據前，不要對使用者作答或作結論。",
                "操作後驗證");
    }

    private void requestNextToolAfterIntermediateReply(
            AgentTaskRecord task) {
        boolean alreadyPrompted;
        synchronized (tasks.monitor()) {
            if (!tasks.isActive(task)
                    || task.finished
                    || task.cancelled
                    || !task.awaitingModel) {
                return;
            }

            alreadyPrompted = task.prematureModelReplies > 0;
            if (alreadyPrompted) {
                task.awaitingModel = false;
                task.status = "任務未完成：模型未繼續目前目標";
                clearLocked();
            } else {
                task.prematureModelReplies = 1;
                task.userVisibleReplyProducedSinceLastAction = false;
                task.finalSpeechRetryCount = 0;
                task.watchdogPrompted = false;
                clearLocked();
                task.status = "目標尚未完成，等待下一個必要動作";
            }
        }

        if (alreadyPrompted) {
            host.reportStage(task.status);
            host.finishTask(
                    task,
                    "模型未繼續目前目標",
                    task.finalReply);
            return;
        }

        host.reportStage(task.status);
        sendDirectiveOrFinish(task,
                "【CONTINUE CURRENT GOAL】目前目標尚未完成。"
                        + "不要敘述工具、Runtime 狀態或中間結果；"
                        + "如果還有明確下一步，現在只呼叫一個必要工具。"
                        + "若沒有可執行的下一步，就用一句使用者可理解的結果結束。",
                "任務續接");
        scheduleWatchdog(task);
    }

    private void sendDirectiveOrFinish(
            AgentTaskRecord task,
            String directive,
            String stage) {
        if (host.sendInternalDirective(directive)) return;

        String reason = stage + "傳送失敗，Gemini Live 連線不可用";
        synchronized (tasks.monitor()) {
            if (!tasks.isActive(task) || task.finished || task.cancelled) return;
            markDirectiveSendFailure(task, reason);
        }
        clear();
        host.reportStage(reason);
        host.finishTask(task, reason, task.finalReply);
    }

    static boolean markDirectiveSendFailure(
            AgentTaskRecord task,
            String reason) {
        if (task == null || task.finished || task.cancelled) return false;
        task.awaitingModel = false;
        task.status = reason == null ? "Gemini Live internal directive failed" : reason;
        return true;
    }

    private void clearLocked() {
        if (responseWatchdog != null) {
            watchdogHandler.removeCallbacks(responseWatchdog);
        }
        responseWatchdog = null;
    }
}
