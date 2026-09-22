package com.crewpocket.helper;

import org.json.JSONArray;

/**
 * Owns mutable Agent task registry/lifecycle state.
 *
 * NativeGeminiLiveClient remains the orchestrator, while this class is the
 * single owner for active-task identity, history, task budget and terminal
 * generation bookkeeping. Callers may use monitor() for compound mutations on
 * AgentTaskRecord during this staged refactor.
 */
final class AgentTaskCoordinator {
    static final class StartResult {
        final AgentTaskRecord task;
        final boolean created;

        StartResult(AgentTaskRecord task, boolean created) {
            this.task = task;
            this.created = created;
        }
    }

    static final class TaskIdentity {
        final String taskId;
        final long intentGeneration;

        TaskIdentity(String taskId, long intentGeneration) {
            this.taskId = taskId == null ? "" : taskId;
            this.intentGeneration = intentGeneration;
        }

        boolean available() {
            return !taskId.isEmpty() && intentGeneration >= 0L;
        }
    }

    private final Object monitor = new Object();
    private final java.util.ArrayList<AgentTaskRecord> history =
            new java.util.ArrayList<AgentTaskRecord>();

    private AgentTaskRecord active;
    private volatile int maxSteps = 30;
    private long lastFinishedIntentGeneration = -1L;

    Object monitor() {
        return monitor;
    }

    void setMaxSteps(int steps) {
        maxSteps = Math.max(1, Math.min(100, steps));
    }

    int maxSteps() {
        return maxSteps;
    }

    boolean hasActive() {
        synchronized (monitor) {
            return active != null && !active.finished;
        }
    }

    String status() {
        synchronized (monitor) {
            return active == null ? "" : active.status;
        }
    }

    JSONArray historyJson() {
        synchronized (monitor) {
            JSONArray records = new JSONArray();
            for (AgentTaskRecord task : history) {
                records.put(task.toJson());
            }
            if (active != null) records.put(active.toJson());
            return records;
        }
    }

    String activeHint() {
        synchronized (monitor) {
            if (active == null || active.finished) return "";
            return active.taskId + " · " + active.status;
        }
    }

    String activeTaskId() {
        synchronized (monitor) {
            return active == null ? "" : active.taskId;
        }
    }

    AgentTaskRecord active() {
        synchronized (monitor) {
            return active;
        }
    }

    AgentTaskRecord activeRunning() {
        synchronized (monitor) {
            return active == null || active.finished ? null : active;
        }
    }

    boolean isActive(AgentTaskRecord task) {
        synchronized (monitor) {
            return active == task;
        }
    }

    StartResult ensureActive(
            long intentGeneration,
            String goalId,
            int goalTaskIndex,
            String recipeGoal,
            String recipeStartPackage) {
        synchronized (monitor) {
            if (active != null && !active.finished) {
                return new StartResult(active, false);
            }

            AgentTaskRecord task =
                    new AgentTaskRecord("agent_" + System.currentTimeMillis());
            task.intentGeneration = intentGeneration;
            task.goalId = goalId == null ? "" : goalId;
            task.goalTaskIndex = goalTaskIndex;
            task.setRecipeContext(recipeGoal, recipeStartPackage);
            active = task;
            return new StartResult(task, true);
        }
    }

    AgentTaskRecord cancelActive(String reason) {
        return cancelActive(reason, cancellationCategory(reason));
    }

    AgentTaskRecord cancelActive(String reason, String category) {
        synchronized (monitor) {
            AgentTaskRecord task = active;
            if (task == null || task.finished) return null;

            task.cancelled = true;
            task.cancelCategory = category == null ? "OTHER" : category;
            task.finished = true;
            task.endReason = reason == null ? "使用者取消" : reason;
            task.status = "Agent 任務已停止";
            history.add(task);
            lastFinishedIntentGeneration = task.intentGeneration;
            active = null;
            return task;
        }
    }

    private static String cancellationCategory(String reason) {
        String value = reason == null ? "" : reason;
        if (value.contains("新使用者指令")) return "NEW_USER_GOAL";
        if (value.contains("使用者打斷")
                || value.contains("使用者語音停止")
                || value.contains("使用者停止對話")) {
            return "USER_INTERRUPTED";
        }
        if (value.contains("通話已結束")) return "SESSION_ENDED";
        if (value.contains("等待使用者選擇")) return "WAIT_FOR_CHOICE";
        if (value.contains("舊操作") || value.contains("stale")) return "STALE_TASK";
        if (value.contains("重新規劃") || value.contains("replan")) return "LIVE_REPLAN";
        if (value.contains("取代") || value.contains("supersede")) return "INTERNAL_SUPERSEDE";
        return "OTHER";
    }

    boolean finish(
            AgentTaskRecord task,
            String reason,
            String finalReply) {
        if (task == null) return false;
        synchronized (monitor) {
            if (task.finished) return false;

            task.finished = true;
            task.endReason = reason == null ? "" : reason;
            task.finalReply =
                    finalReply == null ? task.finalReply : finalReply;
            task.status = "Agent 任務結束：" + task.endReason;
            history.add(task);
            if (history.size() > 20) history.remove(0);
            lastFinishedIntentGeneration = task.intentGeneration;
            if (active == task) active = null;
            return true;
        }
    }

    boolean isFinishedIntentGeneration(long generation) {
        synchronized (monitor) {
            return generation >= 0L
                    && generation == lastFinishedIntentGeneration
                    && (active == null || active.finished);
        }
    }

    AgentTaskRecord resumeAfterUserChoice(String status) {
        synchronized (monitor) {
            if (active == null || active.finished || active.cancelled) {
                return null;
            }
            active.awaitingModel = true;
            active.watchdogPrompted = false;
            active.userVisibleReplyProducedSinceLastAction = false;
            active.finalSpeechRetryCount = 0;
            active.status = status == null ? "使用者已完成選擇" : status;
            return active;
        }
    }

    void appendFinalText(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (monitor) {
            if (active != null && active.awaitingModel) {
                active.finalReply += text;
            }
        }
    }

    TaskIdentity markUserVisibleReplyProduced() {
        synchronized (monitor) {
            if (active == null
                    || !active.awaitingModel
                    || active.finished
                    || active.cancelled) {
                return new TaskIdentity("", -1L);
            }
            active.userVisibleReplyProducedSinceLastAction = true;
            active.finalSpeechRetryCount = 0;
            return new TaskIdentity(active.taskId, active.intentGeneration);
        }
    }

    boolean shouldWithholdUnverifiedReply() {
        synchronized (monitor) {
            return active != null
                    && !active.finished
                    && !active.cancelled
                    && active.requiresPostActionInspection;
        }
    }
}
