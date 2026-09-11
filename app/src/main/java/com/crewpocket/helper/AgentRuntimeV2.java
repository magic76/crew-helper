package com.crewpocket.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Event-ledger runtime with narrowly scoped authority.
 *
 * v2 owns:
 *  - stale generation rejection
 *  - short-window duplicate mutation rejection
 *  - "pending verification" retry barrier
 *  - transaction verification / commit state
 *
 * It does NOT choose phone actions and does not execute Accessibility calls.
 */
final class AgentRuntimeV2 {
    private static final int MAX_TRANSACTIONS = 96;
    private static final int MAX_RECENT_ACTIONS = 96;
    private static final long COMMITTED_DEDUPE_WINDOW_MS = 2500L;
    private static final long PENDING_RETRY_BARRIER_MS = 8000L;
    private static final long FAILED_RETRY_BARRIER_MS = 8000L;
    private static final long DELAYED_UI_PENDING_TIMEOUT_MS = 6500L;

    enum PreflightDecision {
        ALLOW,
        REJECT_STALE,
        REJECT_DUPLICATE,
        REQUIRE_OBSERVE
    }

    static final class PreflightResult {
        final PreflightDecision decision;
        final String code;
        final String actionHash;

        PreflightResult(PreflightDecision decision, String code, String actionHash) {
            this.decision = decision == null ? PreflightDecision.ALLOW : decision;
            this.code = safeCode(code);
            this.actionHash = actionHash == null ? "" : actionHash;
        }

        boolean allowed() { return decision == PreflightDecision.ALLOW; }
    }

    private static final class RecentAction {
        final long generation;
        final String actionHash;
        final String runtimeName;
        String screenIdentity;
        ActionTransaction.Status status;
        long updatedAtMs;

        RecentAction(long generation,
                     String actionHash,
                     String runtimeName,
                     String screenIdentity,
                     ActionTransaction.Status status,
                     long updatedAtMs) {
            this.generation = generation;
            this.actionHash = actionHash;
            this.runtimeName = safeName(runtimeName);
            this.screenIdentity = screenIdentity == null ? "" : screenIdentity;
            this.status = status;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private final AgentLedger ledger = new AgentLedger();
    private final LinkedHashMap<String, ActionTransaction> transactions =
            new LinkedHashMap<String, ActionTransaction>();
    private final LinkedHashMap<String, RecentAction> recentByHash =
            new LinkedHashMap<String, RecentAction>();

    private AgentState state = AgentState.idle();
    private ActionObservation latestObservation = ActionObservation.unavailable();

    synchronized void setListener(AgentLedger.Listener listener) { ledger.setListener(listener); }
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

    /**
     * Gate immediately before a mutation is allowed to reach the executor.
     * rawSignature is hashed immediately and is never written to the ledger.
     */
    synchronized PreflightResult preflight(long callGeneration,
                                           long currentGeneration,
                                           String toolCallId,
                                           String runtimeName,
                                           String rawSignature,
                                           ActionObservation observation) {
        String actionHash = hashSignature(rawSignature);
        ActionObservation current = observation == null ? latestObservation : observation;
        String currentScreen = screenIdentity(current);

        if (callGeneration != currentGeneration) {
            record(AgentEvent.builder(AgentEvent.Type.STALE_ACTION_REJECTED)
                    .generation(callGeneration)
                    .toolCallId(toolCallId)
                    .attr("runtime", safeName(runtimeName))
                    .attr("currentGeneration", currentGeneration)
                    .build());
            return new PreflightResult(PreflightDecision.REJECT_STALE,
                    "STALE_INTENT_GENERATION", actionHash);
        }

        if (isDedupeSensitive(runtimeName)) {
            RecentAction recent = recentByHash.get(actionHash);
            if (recent != null
                    && recent.generation == callGeneration
                    && sameKnownScreen(recent.screenIdentity, currentScreen)) {
                long age = Math.max(0L, System.currentTimeMillis() - recent.updatedAtMs);
                if (recent.status == ActionTransaction.Status.COMMITTED
                        && age <= COMMITTED_DEDUPE_WINDOW_MS) {
                    record(AgentEvent.builder(AgentEvent.Type.DUPLICATE_IGNORED)
                            .generation(callGeneration)
                            .toolCallId(toolCallId)
                            .attr("runtime", safeName(runtimeName))
                            .attr("ageMs", age)
                            .build());
                    return new PreflightResult(PreflightDecision.REJECT_DUPLICATE,
                            "RECENT_COMMITTED_DUPLICATE", actionHash);
                }
                if (recent.status == ActionTransaction.Status.PENDING_VERIFICATION
                        && age <= PENDING_RETRY_BARRIER_MS) {
                    record(AgentEvent.builder(AgentEvent.Type.OBSERVATION_REQUIRED)
                            .generation(callGeneration)
                            .toolCallId(toolCallId)
                            .attr("runtime", safeName(runtimeName))
                            .attr("reason", "PENDING_VERIFICATION")
                            .build());
                    return new PreflightResult(PreflightDecision.REQUIRE_OBSERVE,
                            "PENDING_ACTION_REQUIRES_OBSERVE", actionHash);
                }
                if (recent.status == ActionTransaction.Status.FAILED
                        && age <= FAILED_RETRY_BARRIER_MS) {
                    record(AgentEvent.builder(AgentEvent.Type.OBSERVATION_REQUIRED)
                            .generation(callGeneration)
                            .toolCallId(toolCallId)
                            .attr("runtime", safeName(runtimeName))
                            .attr("reason", "FAILED_ON_SAME_SCREEN")
                            .build());
                    return new PreflightResult(PreflightDecision.REQUIRE_OBSERVE,
                            "FAILED_ACTION_REQUIRES_NEW_OBSERVATION", actionHash);
                }
            }
        }

        record(AgentEvent.builder(AgentEvent.Type.ACTION_PREFLIGHT_ALLOWED)
                .generation(callGeneration)
                .toolCallId(toolCallId)
                .attr("runtime", safeName(runtimeName))
                .build());
        return new PreflightResult(PreflightDecision.ALLOW, "ALLOW", actionHash);
    }

    synchronized String onActionStarted(String toolCallId,
                                        long generation,
                                        String goalId,
                                        String taskId,
                                        String requestedName,
                                        String runtimeName,
                                        String actionHash,
                                        ActionTransaction.ExpectedEffect expectedEffect,
                                        ActionObservation beforeObservation) {
        String actionId = "act_" + generation + "_" + sanitizeId(toolCallId);
        String hash = actionHash == null || actionHash.isEmpty()
                ? hashSignature(runtimeName + ":" + toolCallId)
                : actionHash;
        ActionObservation before = beforeObservation == null
                ? latestObservation : beforeObservation;

        ActionTransaction tx = new ActionTransaction(
                actionId, generation, goalId, taskId, toolCallId,
                safeName(requestedName), safeName(runtimeName), expectedEffect,
                hash, before);
        tx.allowPreflight();
        tx.start();
        transactions.put(toolCallId, tx);
        recentByHash.put(hash, new RecentAction(generation, hash, runtimeName,
                screenIdentity(before), ActionTransaction.Status.STARTED,
                System.currentTimeMillis()));
        trimMaps();

        record(AgentEvent.builder(AgentEvent.Type.ACTION_STARTED)
                .generation(generation)
                .goalId(goalId)
                .taskId(taskId)
                .actionId(actionId)
                .toolCallId(toolCallId)
                .attr("requested", safeName(requestedName))
                .attr("runtime", safeName(runtimeName))
                .attr("before", compactFingerprint(before.fingerprint))
                .attr("stableKey", compactFingerprint(before.stableScreenKey))
                .build());
        return actionId;
    }

    synchronized void onActionExecuted(String toolCallId, ExecutionEvidence evidence) {
        ActionTransaction tx = transactions.get(toolCallId);
        if (tx == null) return;
        tx.markExecuted(evidence);
        updateRecent(tx, ActionTransaction.Status.EXECUTED, tx.beforeObservation());
        record(AgentEvent.builder(AgentEvent.Type.ACTION_EXECUTED)
                .generation(tx.generation)
                .goalId(tx.goalId)
                .taskId(tx.taskId)
                .actionId(tx.actionId)
                .toolCallId(toolCallId)
                .attr("accepted", evidence != null && evidence.executionAccepted)
                .build());
    }

    synchronized ActionVerificationResult verifyAndRecord(String toolCallId,
                                                          ExecutionEvidence evidence,
                                                          ActionObservation afterObservation) {
        ActionTransaction tx = transactions.get(toolCallId);
        if (tx == null) return null;
        if (evidence != null) tx.markExecuted(evidence);
        ActionObservation after = afterObservation == null
                ? latestObservation : afterObservation;
        tx.observe(after);
        ExecutionEvidence effective = tx.executionEvidence() == null
                ? ExecutionEvidence.failed("NO_EXECUTION_EVIDENCE")
                : tx.executionEvidence();
        ActionVerificationResult verification = ActionVerifierV2.verify(
                tx.runtimeName, tx.expectedEffect, effective,
                tx.beforeObservation(), after);
        applyVerification(tx, verification, after);
        return verification;
    }

    /** Re-runs only pending transactions after an explicit inspect/wait observation. */
    synchronized int reverifyPending(ActionObservation observation) {
        if (observation == null || !observation.available) return 0;
        latestObservation = observation;
        int committed = 0;
        for (ActionTransaction tx : transactions.values()) {
            if (!tx.isPendingVerification()) continue;
            ExecutionEvidence evidence = tx.executionEvidence();
            if (evidence == null) continue;
            tx.observe(observation);
            ActionVerificationResult verification = ActionVerifierV2.verify(
                    tx.runtimeName, tx.expectedEffect, evidence,
                    tx.beforeObservation(), observation);
            if (verification.pending()) {
                long pendingAge = Math.max(0L, System.currentTimeMillis() - tx.updatedAtMs());
                if (!evidence.allowDelayedUi) {
                    verification = new ActionVerificationResult(
                            ActionVerificationResult.Status.FAILED,
                            "NO_EFFECT_AFTER_EXPLICIT_OBSERVE",
                            verification.screenChanged,
                            verification.stableScreenChanged,
                            verification.packageChanged,
                            verification.focusChanged);
                } else if (pendingAge >= DELAYED_UI_PENDING_TIMEOUT_MS) {
                    verification = new ActionVerificationResult(
                            ActionVerificationResult.Status.FAILED,
                            "DELAYED_UI_VERIFICATION_TIMEOUT",
                            verification.screenChanged,
                            verification.stableScreenChanged,
                            verification.packageChanged,
                            verification.focusChanged);
                } else {
                    continue;
                }
            }
            applyVerification(tx, verification, observation);
            if (verification.committed()) committed++;
        }
        return committed;
    }

    synchronized void onScreenObserved(ActionObservation observation) {
        if (observation == null || !observation.available) return;
        latestObservation = observation;
        record(AgentEvent.builder(AgentEvent.Type.SCREEN_OBSERVED)
                .generation(state.generation)
                .goalId(state.goalId)
                .taskId(state.taskId)
                .attr("fingerprint", compactFingerprint(observation.fingerprint))
                .attr("stableKey", compactFingerprint(observation.stableScreenKey))
                .attr("package", safePackage(observation.packageName))
                .build());
    }

    synchronized void onLocatorResolved(long generation,
                                        String toolCallId,
                                        String decision,
                                        double confidence,
                                        String source) {
        record(AgentEvent.builder(AgentEvent.Type.LOCATOR_RESOLVED)
                .generation(generation)
                .toolCallId(toolCallId)
                .attr("decision", safeCode(decision))
                .attr("confidence", confidence)
                .attr("source", safeCode(source))
                .build());
    }

    synchronized void onLocatorRejected(long generation,
                                        String toolCallId,
                                        String decision,
                                        double confidence,
                                        String code) {
        record(AgentEvent.builder(AgentEvent.Type.LOCATOR_REJECTED)
                .generation(generation)
                .toolCallId(toolCallId)
                .attr("decision", safeCode(decision))
                .attr("confidence", confidence)
                .attr("code", safeCode(code))
                .build());
    }

    synchronized void onToolResultSent(String toolCallId,
                                       String requestedName,
                                       boolean success,
                                       String code) {
        ActionTransaction tx = transactions.get(toolCallId);
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

    private void applyVerification(ActionTransaction tx,
                                   ActionVerificationResult verification,
                                   ActionObservation after) {
        tx.applyVerification(verification);
        if (verification.committed()) {
            record(AgentEvent.builder(AgentEvent.Type.ACTION_VERIFIED)
                    .generation(tx.generation)
                    .goalId(tx.goalId)
                    .taskId(tx.taskId)
                    .actionId(tx.actionId)
                    .toolCallId(tx.toolCallId)
                    .attr("status", verification.status.name())
                    .attr("code", safeCode(verification.code))
                    .build());
            tx.commit();
            updateRecent(tx, ActionTransaction.Status.COMMITTED, after);
            record(AgentEvent.builder(AgentEvent.Type.ACTION_COMMITTED)
                    .generation(tx.generation)
                    .goalId(tx.goalId)
                    .taskId(tx.taskId)
                    .actionId(tx.actionId)
                    .toolCallId(tx.toolCallId)
                    .attr("status", verification.status.name())
                    .attr("stableKey", compactFingerprint(after.stableScreenKey))
                    .build());
        } else if (verification.pending()) {
            updateRecent(tx, ActionTransaction.Status.PENDING_VERIFICATION, after);
            record(AgentEvent.builder(AgentEvent.Type.ACTION_VERIFICATION_PENDING)
                    .generation(tx.generation)
                    .goalId(tx.goalId)
                    .taskId(tx.taskId)
                    .actionId(tx.actionId)
                    .toolCallId(tx.toolCallId)
                    .attr("code", safeCode(verification.code))
                    .attr("stableKey", compactFingerprint(after.stableScreenKey))
                    .build());
        } else {
            ActionTransaction.Status status = verification.status == ActionVerificationResult.Status.BLOCKED
                    ? ActionTransaction.Status.REJECTED
                    : (verification.status == ActionVerificationResult.Status.CANCELLED
                        ? ActionTransaction.Status.CANCELLED
                        : ActionTransaction.Status.FAILED);
            updateRecent(tx, status, after);
            record(AgentEvent.builder(AgentEvent.Type.ACTION_FAILED)
                    .generation(tx.generation)
                    .goalId(tx.goalId)
                    .taskId(tx.taskId)
                    .actionId(tx.actionId)
                    .toolCallId(tx.toolCallId)
                    .attr("status", verification.status.name())
                    .attr("code", safeCode(verification.code))
                    .build());
        }
    }

    private void updateRecent(ActionTransaction tx,
                              ActionTransaction.Status status,
                              ActionObservation observation) {
        if (tx == null || tx.actionHash.isEmpty()) return;
        RecentAction recent = recentByHash.get(tx.actionHash);
        if (recent == null) {
            recent = new RecentAction(tx.generation, tx.actionHash, tx.runtimeName,
                    screenIdentity(observation), status, System.currentTimeMillis());
            recentByHash.put(tx.actionHash, recent);
        } else {
            recent.status = status;
            String identity = screenIdentity(observation);
            if (!identity.isEmpty()) recent.screenIdentity = identity;
            recent.updatedAtMs = System.currentTimeMillis();
        }
    }

    private void cancelOpenTransactions(String reason) {
        for (ActionTransaction tx : transactions.values()) {
            ActionTransaction.Status s = tx.status();
            if (s != ActionTransaction.Status.COMMITTED
                    && s != ActionTransaction.Status.FAILED
                    && s != ActionTransaction.Status.REJECTED
                    && s != ActionTransaction.Status.CANCELLED) {
                tx.cancel(reason);
                updateRecent(tx, ActionTransaction.Status.CANCELLED, latestObservation);
            }
        }
    }

    private void record(AgentEvent input) {
        AgentEvent event = ledger.append(input);
        state = AgentReducer.reduce(state, event);
        ledger.notifyListener(event, state);
    }

    private void trimMaps() {
        while (transactions.size() > MAX_TRANSACTIONS) removeFirst(transactions);
        while (recentByHash.size() > MAX_RECENT_ACTIONS) removeFirst(recentByHash);
    }

    private static <T> void removeFirst(LinkedHashMap<String, T> map) {
        String first = null;
        for (Map.Entry<String, T> entry : map.entrySet()) {
            first = entry.getKey();
            break;
        }
        if (first != null) map.remove(first);
    }

    private static boolean isDedupeSensitive(String runtimeName) {
        String n = runtimeName == null ? "" : runtimeName.trim();
        return "tap_screen".equals(n)
                || "tap_element".equals(n)
                || "type_text".equals(n)
                || "search_current_app".equals(n)
                || "send_text".equals(n)
                || "launch_app".equals(n);
    }

    private static boolean sameKnownScreen(String a, String b) {
        return a != null && b != null && !a.isEmpty() && a.equals(b);
    }

    private static String screenIdentity(ActionObservation observation) {
        if (observation == null || !observation.available) return "";
        if (!observation.stableScreenKey.isEmpty()) return "s:" + observation.stableScreenKey;
        if (!observation.fingerprint.isEmpty()) return "f:" + observation.fingerprint;
        return observation.packageName.isEmpty() ? "" : "p:" + observation.packageName;
    }

    static String hashSignature(String value) {
        String raw = value == null ? "" : value;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                out.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
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

    private static String safePackage(String value) { return safeName(value); }
    private static String safeCode(String value) { return safeName(value); }

    private static String compactFingerprint(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}

