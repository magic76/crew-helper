package com.crewpocket.helper;

/**
 * Pure task-lifecycle policy extracted from NativeGeminiLiveClient.
 *
 * This class only decides whether a task step may run and whether a failed
 * mutation must be observed before another mutation. It does not execute phone
 * actions, own user authorization, or change Runtime safety boundaries.
 */
final class AgentTaskLifecyclePolicy {
    static final long TASK_TIMEOUT_MS = 180_000L;
    static final int MAX_TOOL_RUNS = 8;
    static final int MAX_MUTATION_ACTIONS = 15;
    static final int MAX_SCREENSHOTS = 3;
    static final int DECK_NAV_MAX_RUNS = 16;

    static final class StepDecision {
        final boolean allowed;
        final String blockedReason;

        StepDecision(boolean allowed, String blockedReason) {
            this.allowed = allowed;
            this.blockedReason = blockedReason == null ? "" : blockedReason;
        }
    }

    static final class StabilityDecision {
        final boolean blocked;
        final String code;
        final String instruction;

        StabilityDecision(boolean blocked, String code, String instruction) {
            this.blocked = blocked;
            this.code = code == null ? "" : code;
            this.instruction = instruction == null ? "" : instruction;
        }
    }

    private AgentTaskLifecyclePolicy() {}

    static StepDecision evaluateStep(long nowMs,
                                     long startedAtMs,
                                     int steps,
                                     int maxSteps,
                                     String name,
                                     String signature,
                                     String lastSignature,
                                     int toolCount,
                                     int mutationActions) {
        boolean observation = isObservationTool(name);
        boolean mutation = isMutationTool(name);

        if (nowMs - startedAtMs > TASK_TIMEOUT_MS) {
            return blocked("本次 Agent 任務已逾時（180 秒），請以目前已知結果作結論。");
        }
        if (steps >= maxSteps) {
            return blocked("已達本次自動執行步數上限（" + maxSteps + " 步），請以目前已知結果作結論。");
        }
        if (!observation && safe(signature).equals(safe(lastSignature))) {
            return blocked("偵測到相同動作與參數連續重複呼叫，請先重新觀察畫面並改用替代方案。");
        }
        if ("take_screenshot".equals(name) && toolCount >= MAX_SCREENSHOTS) {
            return blocked("截圖已達本次任務上限，請改用 Accessibility 畫面狀態或作結論。");
        }
        int maxRuns = maxRunsForTool(name);
        if (!observation && toolCount >= maxRuns) {
            return blocked("工具「" + name + "」已達本次任務最多 " + maxRuns + " 次執行限制，請改用替代方案或作結論。");
        }
        if (mutation && mutationActions >= MAX_MUTATION_ACTIONS) {
            return blocked("已達本次任務實際操作上限（" + MAX_MUTATION_ACTIONS + " 次），請以目前結果作結論。");
        }
        return new StepDecision(true, "");
    }

    static StabilityDecision evaluateStability(boolean taskFinished,
                                               boolean taskCancelled,
                                               String name,
                                               boolean requireObservationAfterFailure,
                                               boolean semanticObserveRequired,
                                               String signature,
                                               String lastFailedMutationSignature,
                                               String failedMutationScreenFingerprint,
                                               String latestSemanticFingerprint) {
        if (taskFinished || taskCancelled || !isMutationTool(name)) {
            return new StabilityDecision(false, "", "");
        }
        if (requireObservationAfterFailure || semanticObserveRequired) {
            return new StabilityDecision(
                    true,
                    "OBSERVE_REQUIRED_AFTER_FAILURE",
                    "上一個操作失敗或驗證不足。先呼叫 inspect_ui 一次，再依最新畫面改用不同方法；不要直接重做 mutation。");
        }
        if (!safe(lastFailedMutationSignature).isEmpty()
                && safe(signature).equals(safe(lastFailedMutationSignature))
                && !safe(failedMutationScreenFingerprint).isEmpty()
                && safe(failedMutationScreenFingerprint).equals(safe(latestSemanticFingerprint))) {
            return new StabilityDecision(
                    true,
                    "REPEAT_FAILED_ACTION_ON_UNCHANGED_SCREEN",
                    "這個完全相同的操作已在目前畫面失敗。禁止原樣重試；請換 selector、換工具、返回，或直接回報卡點。");
        }
        return new StabilityDecision(false, "", "");
    }

    static boolean shouldStopAfterStabilityBlock(int stabilityBlocks) {
        return stabilityBlocks >= 2;
    }

    static boolean shouldStopAfterMutationFailure(int consecutiveMutationFailures) {
        return consecutiveMutationFailures >= 3;
    }

    static int maxRunsForTool(String name) {
        return "advance_deck".equals(name) || "present_deck_card".equals(name)
                ? DECK_NAV_MAX_RUNS : MAX_TOOL_RUNS;
    }

    static boolean isObservationTool(String name) {
        return "inspect_ui".equals(name)
                || "get_selected_region".equals(name)
                || "read_web_page".equals(name)
                || "list_app_guidance".equals(name)
                || "get_note".equals(name)
                || "search_notes".equals(name)
                || "list_notes".equals(name)
                || "wait".equals(name)
                || "teach_ui_element".equals(name)
                || "list_active_schedules".equals(name)
                || "conversation_loop".equals(name)
                || "list_memory_rules".equals(name)
                || "list_decks".equals(name)
                || "get_deck_card".equals(name)
                || "list_deck_images".equals(name);
    }

    static boolean isMutationTool(String name) {
        return "launch_app".equals(name)
                || "swipe_screen".equals(name)
                || "tap_element".equals(name)
                || "tap_screen".equals(name)
                || "type_text".equals(name)
                || "search_current_app".equals(name)
                || "commit_search".equals(name)
                || "send_text".equals(name)
                || "press_key".equals(name);
    }

    private static StepDecision blocked(String reason) {
        return new StepDecision(false, reason);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
