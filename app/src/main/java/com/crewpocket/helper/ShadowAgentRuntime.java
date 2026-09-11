package com.crewpocket.helper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase-1 adapter for introducing Event Ledger / State Machine without changing
 * execution behaviour. All methods are observational only.
 */
final class ShadowAgentRuntime {
    private static final int MAX_TRANSACTIONS = 64;

    private final AgentLedger ledger = new AgentLedger();
    private final LinkedHashMap<String, ActionTransaction> transactions =
            new LinkedHashMap<String, ActionTransaction>();
    private AgentState state = AgentState.idle();

    synchronized void setListener(AgentLedger.Listener listener) {
        ledger.setListener(listener);
    }

    synchronized AgentState state() { return state; }

    synchronized String dumpRecent(int limit) { return ledger.dumpRecent(limit); }

    synchronized void onUserIntent(long generation, String goalId, String taskId, boolean newCapsule) {
        record(AgentEvent.builder(AgentEvent.Type.USER_INTENT_ACCEPTED)
                .generation(generation)
                .goalId(goalId)
                .taskId(taskId)
                .attr("capsule", newCapsule ? "NEW" : "CONTINUE")
                .build());
    }

    synchronized void onToolQueued(String toolCallId, String requestedName, long generation) {
        record(AgentEvent.builder(AgentEvent.Type.TOOL_QUEUED)
                .generation(generation)
                .toolCallId(toolCallId)
                .attr("requested", safeName(requestedName))
                .build());
    }

    synchronized void onDuplicateIgnored(String toolCallId, String requestedName, long generation) {
        record(AgentEvent.builder(AgentEvent.Type.DUPLICATE_IGNORED)
                .generation(generation)
                .toolCallId(toolCallId)
                .attr("requested", safeName(requestedName))
                .build());
    }

    synchronized void onStaleActionRejected(String toolCallId, String requestedName,
                                             long callGeneration, long currentGeneration) {
        record(AgentEvent.builder(AgentEvent.Type.STALE_ACTION_REJECTED)
                .generation(callGeneration)
                .toolCallId(toolCallId)
                .attr("requested", safeName(requestedName))
                .attr("currentGeneration", String.valueOf(currentGeneration))
                .build());
    }

    synchronized String onActionStarted(String toolCallId,
                                        long generation,
                                        String goalId,
                                        String taskId,
                                        String requestedName,
                                        String runtimeName,
                                        String beforeFingerprint,
                                        ActionTransaction.ExpectedEffect expectedEffect) {
        String actionId = "act_" + generation + "_" + sanitizeId(toolCallId);
        ActionTransaction tx = new ActionTransaction(
                actionId, generation, goalId, taskId, toolCallId,
                safeName(requestedName), safeName(runtimeName), expectedEffect,
                beforeFingerprint);
        tx.start();
        transactions.put(toolCallId, tx);
        trimTransactions();
        record(AgentEvent.builder(AgentEvent.Type.ACTION_STARTED)
                .generation(generation)
                .goalId(goalId)
                .taskId(taskId)
                .actionId(actionId)
                .toolCallId(toolCallId)
                .attr("requested", safeName(requestedName))
                .attr("runtime", safeName(runtimeName))
                .attr("before", compactFingerprint(beforeFingerprint))
                .build());
        return actionId;
    }

    synchronized void onActionExecuted(String toolCallId) {
        ActionTransaction tx = transactions.get(toolCallId);
        if (tx == null) return;
        tx.markExecuted();
        record(AgentEvent.builder(AgentEvent.Type.ACTION_EXECUTED)
                .generation(tx.generation)
                .goalId(tx.goalId)
                .taskId(tx.taskId)
                .actionId(tx.actionId)
                .toolCallId(toolCallId)
                .build());
    }

    synchronized void onScreenObserved(String fingerprint, String stableScreenKey, String packageName) {
        for (ActionTransaction tx : transactions.values()) {
            if (tx.status() == ActionTransaction.Status.STARTED
                    || tx.status() == ActionTransaction.Status.EXECUTED) {
                tx.observe(fingerprint);
            }
        }
        record(AgentEvent.builder(AgentEvent.Type.SCREEN_OBSERVED)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(state.taskId)
                .attr("fingerprint", compactFingerprint(fingerprint))
                .attr("stableKey", compactFingerprint(stableScreenKey))
                .attr("package", safePackage(packageName))
                .build());
    }

    synchronized void onToolResult(String toolCallId,
                                   String requestedName,
                                   boolean success,
                                   String code,
                                   String fingerprint) {
        ActionTransaction tx = transactions.get(toolCallId);
        if (tx != null) {
            if (fingerprint != null && !fingerprint.isEmpty()) tx.observe(fingerprint);
            tx.verify(success, code);
            record(AgentEvent.builder(AgentEvent.Type.ACTION_VERIFIED)
                    .generation(tx.generation)
                    .goalId(tx.goalId)
                    .taskId(tx.taskId)
                    .actionId(tx.actionId)
                    .toolCallId(toolCallId)
                    .attr("success", success)
                    .attr("code", safeCode(code))
                    .attr("fingerprint", compactFingerprint(fingerprint))
                    .build());
            if (success) {
                tx.commit();
                record(AgentEvent.builder(AgentEvent.Type.ACTION_COMMITTED)
                        .generation(tx.generation)
                        .goalId(tx.goalId)
                        .taskId(tx.taskId)
                        .actionId(tx.actionId)
                        .toolCallId(toolCallId)
                        .attr("screenChanged", tx.screenChanged())
                        .build());
            } else {
                record(AgentEvent.builder(AgentEvent.Type.ACTION_FAILED)
                        .generation(tx.generation)
                        .goalId(tx.goalId)
                        .taskId(tx.taskId)
                        .actionId(tx.actionId)
                        .toolCallId(toolCallId)
                        .attr("code", safeCode(code))
                        .build());
            }
        }

        record(AgentEvent.builder(AgentEvent.Type.TOOL_RESULT_SENT)
                .generation(tx == null ? state.generation : tx.generation)
                .toolCallId(toolCallId)
                .attr("requested", safeName(requestedName))
                .attr("success", success)
                .attr("code", safeCode(code))
                .build());
    }

    synchronized void onWaitingForUser(String reason) {
        record(AgentEvent.builder(AgentEvent.Type.USER_WAITING)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(state.taskId)
                .attr("reason", safeCode(reason))
                .build());
    }

    synchronized void onInterrupted(String reason) {
        cancelOpenTransactions(reason);
        record(AgentEvent.builder(AgentEvent.Type.USER_INTERRUPTED)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(state.taskId)
                .attr("reason", safeCode(reason))
                .build());
    }

    synchronized void onTaskCompleted(String taskId) {
        record(AgentEvent.builder(AgentEvent.Type.TASK_COMPLETED)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(taskId)
                .build());
    }

    synchronized void onTaskFailed(String taskId, String code) {
        cancelOpenTransactions(code);
        record(AgentEvent.builder(AgentEvent.Type.TASK_FAILED)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(taskId)
                .attr("code", safeCode(code))
                .build());
    }

    synchronized void onTaskCancelled(String taskId, String reason) {
        cancelOpenTransactions(reason);
        record(AgentEvent.builder(AgentEvent.Type.TASK_CANCELLED)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(taskId)
                .attr("reason", safeCode(reason))
                .build());
    }

    private void cancelOpenTransactions(String reason) {
        for (ActionTransaction tx : transactions.values()) {
            ActionTransaction.Status status = tx.status();
            if (status != ActionTransaction.Status.COMMITTED
                    && status != ActionTransaction.Status.FAILED
                    && status != ActionTransaction.Status.CANCELLED) {
                tx.cancel(reason);
            }
        }
    }

    private void record(AgentEvent input) {
        AgentEvent event = ledger.append(input);
        state = AgentReducer.reduce(state, event);
        ledger.notifyListener(event, state);
    }

    private void trimTransactions() {
        while (transactions.size() > MAX_TRANSACTIONS) {
            String first = null;
            for (Map.Entry<String, ActionTransaction> entry : transactions.entrySet()) {
                first = entry.getKey();
                break;
            }
            if (first == null) break;
            transactions.remove(first);
        }
    }

    private static String sanitizeId(String value) {
        if (value == null || value.isEmpty()) return String.valueOf(System.nanoTime());
        String compact = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return compact.length() > 48 ? compact.substring(0, 48) : compact;
    }

    private static String safeName(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9_./:-]", "_");
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private static String safePackage(String value) {
        return safeName(value);
    }

    private static String safeCode(String value) {
        return safeName(value);
    }

    private static String compactFingerprint(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}

