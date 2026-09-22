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
        boolean isAgentMuted();
        void reportStage(String text);
        void sendInternalDirective(String text);
        void finishTask(AgentTaskRecord task, String reason, String finalReply);
        String audioOutputState();
        long pcmBytesReceived();
        long pcmBytesAccepted();
    }

    private static final long FINAL_RESPONSE_WAIT_MS = 12_000L;
    private static final int FINAL_SPEECH_MAX_RETRIES = 2;

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
        host.sendInternalDirective(
                "【Agent 系統狀態】" + task.status
                        + " 不要再呼叫工具；請以目前已知的工具結果，向使用者給出清楚、簡短的最終結論。"
                        + "這個 active task 不可靜默結束；必須輸出一個簡短 AUDIO 回覆。"
                        + "不要說『抱歉』或『對不起』；直接說明已完成的部分與目前唯一卡點。");
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

                    String reason = "工具結果已回傳，但 12 秒未收到模型下一步。";
                    synchronized (tasks.monitor()) {
                        if (!tasks.isActive(task)
                                || task.finished
                                || task.cancelled) {
                            return;
                        }
                        task.awaitingModel = true;
                        task.status = reason;
                    }
                    host.reportStage(reason);
                    host.sendInternalDirective(
                            "【Agent 系統狀態】上一個工具結果已回傳。只看目前 model-facing status："
                                    + "DONE 代表上一步成功；WAIT 先 inspect_ui 看 fresh screenshot，不要重複操作；"
                                    + "FAILED 換方法且不要重複同一動作；NEED_USER 只問必要選擇。"
                                    + "只有真的完成或無替代方案時才作結論。");
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
                        task.blockedReason != null);
        if (!completionReady) {
            requestNextToolAfterIntermediateReply(task);
            return;
        }

        if (!host.isAgentMuted()
                && !task.userVisibleReplyProducedSinceLastAction) {
            requestFinalSpeechOrNextTool(task);
            return;
        }

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
        host.sendInternalDirective(
                "【Runtime 必要驗證】上一個手機操作只代表動作已執行，尚未證明任務完成。"
                        + "現在必須呼叫 inspect_ui；Runtime 會送一張 fresh screenshot。"
                        + "直接看最新畫面決定下一步；在取得該證據前，不要對使用者作答或作結論。");
    }

    private void requestNextToolAfterIntermediateReply(
            AgentTaskRecord task) {
        synchronized (tasks.monitor()) {
            if (!tasks.isActive(task)
                    || task.finished
                    || task.cancelled
                    || !task.awaitingModel) {
                return;
            }
            task.prematureModelReplies++;
            task.userVisibleReplyProducedSinceLastAction = false;
            task.finalSpeechRetryCount = 0;
            task.watchdogPrompted = false;
            clearLocked();
            task.status = "目標尚未完成，繼續同一個 Agent 任務";
        }

        host.reportStage(task.status);
        host.sendInternalDirective(
                "【WHOLE TASK CONTINUITY】剛才的語音/文字回覆不是 whole-task completion。"
                        + "目前 active goal 必須保持同一個 task，不要因 STEP_OK 或 EVIDENCE_AVAILABLE 就停。"
                        + "若還有明確下一步，保持安靜並只呼叫下一個工具；"
                        + "若你認為目標真的完成，先用 inspect_ui 取得 fresh 畫面證據，再給最後一句 AUDIO。"
                        + "只有 Runtime taskState=DONE/ANSWER_READY，或 fresh observation 後的結論，才可結束。");
        scheduleWatchdog(task);
    }

    private void requestFinalSpeechOrNextTool(
            AgentTaskRecord task) {
        int attempt;
        synchronized (tasks.monitor()) {
            if (!tasks.isActive(task)
                    || task.finished
                    || task.cancelled
                    || !task.awaitingModel) {
                return;
            }

            if (task.finalSpeechRetryCount
                    >= FINAL_SPEECH_MAX_RETRIES) {
                task.status = "Agent 操作已結束，但 Gemini 未產生最終語音";
                host.reportStage(task.status);
                host.finishTask(
                        task,
                        "最終語音未產生",
                        task.finalReply);
                return;
            }

            task.finalSpeechRetryCount++;
            attempt = task.finalSpeechRetryCount;
            task.watchdogPrompted = false;
            clearLocked();
            task.status =
                    "等待最終語音或下一個必要動作（"
                            + attempt
                            + "/"
                            + FINAL_SPEECH_MAX_RETRIES
                            + "）";
        }

        host.reportStage(
                task.status
                        + " · audio="
                        + host.audioOutputState()
                        + " · pcmReceived="
                        + host.pcmBytesReceived()
                        + " · pcmAccepted="
                        + host.pcmBytesAccepted());
        host.sendInternalDirective(
                "【FINAL TURN REQUIRED】上一個 active Agent turn 沒有產生使用者可聽見的最終回覆，"
                        + "也沒有下一個工具動作。現在只能二選一："
                        + "如果任務已完成，立刻用 AUDIO 說一句簡短結果，且不要再呼叫工具；"
                        + "如果任務尚未完成，保持安靜並只呼叫一個下一步工具。"
                        + "不得再次空白結束，也不要敘述 Runtime 中間步驟。");
        scheduleWatchdog(task);
    }

    private void clearLocked() {
        if (responseWatchdog != null) {
            watchdogHandler.removeCallbacks(responseWatchdog);
        }
        responseWatchdog = null;
    }
}
