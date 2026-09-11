package com.crewpocket.helper;

/**
 * Runtime-owned lifecycle for one phone mutation.
 *
 * Unlike v1, v2 distinguishes EXECUTED from VERIFIED.  An Android callback or
 * dispatched gesture is never enough by itself to commit a mutation.
 */
final class ActionTransaction {
    enum Status {
        CREATED,
        PREFLIGHT_ALLOWED,
        STARTED,
        EXECUTED,
        PENDING_VERIFICATION,
        VERIFIED,
        COMMITTED,
        FAILED,
        REJECTED,
        CANCELLED
    }

    enum ExpectedEffect {
        UNKNOWN,
        ANY_OBSERVABLE_CHANGE,
        SCREEN_CHANGE,
        APP_CHANGE,
        FOCUS_CHANGE,
        TEXT_CHANGE,
        SCROLL_CHANGE,
        DOMAIN_VERIFIED,
        NO_UI_CHANGE
    }

    final String actionId;
    final long generation;
    final String goalId;
    final String taskId;
    final String toolCallId;
    final String requestedName;
    final String runtimeName;
    final ExpectedEffect expectedEffect;
    final String actionHash;
    final long createdAtMs;

    private Status status = Status.CREATED;
    private ActionObservation beforeObservation;
    private ActionObservation afterObservation;
    private ExecutionEvidence executionEvidence;
    private ActionVerificationResult verification;
    private String resultCode = "";
    private long updatedAtMs;

    ActionTransaction(String actionId,
                      long generation,
                      String goalId,
                      String taskId,
                      String toolCallId,
                      String requestedName,
                      String runtimeName,
                      ExpectedEffect expectedEffect,
                      String actionHash,
                      ActionObservation beforeObservation) {
        this.actionId = safe(actionId);
        this.generation = generation;
        this.goalId = safe(goalId);
        this.taskId = safe(taskId);
        this.toolCallId = safe(toolCallId);
        this.requestedName = safe(requestedName);
        this.runtimeName = safe(runtimeName);
        this.expectedEffect = expectedEffect == null ? ExpectedEffect.UNKNOWN : expectedEffect;
        this.actionHash = safe(actionHash);
        this.beforeObservation = beforeObservation == null
                ? ActionObservation.unavailable() : beforeObservation;
        this.afterObservation = ActionObservation.unavailable();
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = createdAtMs;
    }

    /**
     * v1 ShadowAgentRuntime source-compatibility constructor.
     * Remove after the old shadow adapter has been deleted from the app.
     */
    @Deprecated
    ActionTransaction(String actionId,
                      long generation,
                      String goalId,
                      String taskId,
                      String toolCallId,
                      String requestedName,
                      String runtimeName,
                      ExpectedEffect expectedEffect,
                      String beforeFingerprint) {
        this(actionId, generation, goalId, taskId, toolCallId, requestedName, runtimeName,
                expectedEffect, "", new ActionObservation(
                        beforeFingerprint != null && !beforeFingerprint.isEmpty(),
                        "", beforeFingerprint, "", "", "", 0, System.currentTimeMillis()));
    }

    synchronized Status status() { return status; }
    synchronized ActionObservation beforeObservation() { return beforeObservation; }
    synchronized ActionObservation afterObservation() { return afterObservation; }
    synchronized ExecutionEvidence executionEvidence() { return executionEvidence; }
    synchronized ActionVerificationResult verification() { return verification; }
    synchronized String resultCode() { return resultCode; }
    synchronized long updatedAtMs() { return updatedAtMs; }

    synchronized void allowPreflight() {
        if (status == Status.CREATED) set(Status.PREFLIGHT_ALLOWED);
    }

    synchronized void start() {
        if (status == Status.CREATED || status == Status.PREFLIGHT_ALLOWED) set(Status.STARTED);
    }

    /** v1 source compatibility. */
    @Deprecated
    synchronized void markExecuted() {
        markExecuted(new ExecutionEvidence(true, false, false, false, false, ""));
    }

    synchronized void markExecuted(ExecutionEvidence evidence) {
        if (isTerminal()) return;
        executionEvidence = evidence;
        if (status == Status.STARTED || status == Status.PREFLIGHT_ALLOWED) set(Status.EXECUTED);
    }

    /** v1 source compatibility. */
    @Deprecated
    synchronized void observe(String fingerprint) {
        observe(new ActionObservation(
                fingerprint != null && !fingerprint.isEmpty(),
                "", fingerprint, "", "", "", 0, System.currentTimeMillis()));
    }

    synchronized void observe(ActionObservation observation) {
        if (isTerminal() || observation == null) return;
        afterObservation = observation;
    }

    /** v1 source compatibility. */
    @Deprecated
    synchronized void verify(boolean success, String code) {
        boolean changed = screenChanged();
        applyVerification(new ActionVerificationResult(
                success ? ActionVerificationResult.Status.VERIFIED
                        : ActionVerificationResult.Status.FAILED,
                code, changed, false, false, false));
    }

    /** v1 source compatibility. */
    @Deprecated
    synchronized boolean screenChanged() {
        String before = beforeFingerprint();
        String after = afterFingerprint();
        return !before.isEmpty() && !after.isEmpty() && !before.equals(after);
    }

    synchronized void applyVerification(ActionVerificationResult value) {
        if (isTerminal() || value == null) return;
        verification = value;
        resultCode = safe(value.code);
        if (value.committed()) set(Status.VERIFIED);
        else if (value.pending()) set(Status.PENDING_VERIFICATION);
        else if (value.status == ActionVerificationResult.Status.BLOCKED) set(Status.REJECTED);
        else if (value.status == ActionVerificationResult.Status.CANCELLED) set(Status.CANCELLED);
        else set(Status.FAILED);
    }

    synchronized void commit() {
        if (status == Status.VERIFIED) set(Status.COMMITTED);
    }

    synchronized void fail(String code) {
        if (isTerminal()) return;
        resultCode = safe(code);
        set(Status.FAILED);
    }

    synchronized void reject(String code) {
        if (isTerminal()) return;
        resultCode = safe(code);
        set(Status.REJECTED);
    }

    synchronized void cancel(String code) {
        if (isTerminal()) return;
        resultCode = safe(code);
        set(Status.CANCELLED);
    }

    synchronized boolean isPendingVerification() {
        return status == Status.PENDING_VERIFICATION;
    }

    synchronized boolean isCommitted() {
        return status == Status.COMMITTED;
    }

    synchronized String beforeStableScreenKey() {
        return beforeObservation == null ? "" : beforeObservation.stableScreenKey;
    }

    synchronized String afterStableScreenKey() {
        return afterObservation == null ? "" : afterObservation.stableScreenKey;
    }

    synchronized String beforeFingerprint() {
        return beforeObservation == null ? "" : beforeObservation.fingerprint;
    }

    synchronized String afterFingerprint() {
        return afterObservation == null ? "" : afterObservation.fingerprint;
    }

    private void set(Status value) {
        status = value;
        updatedAtMs = System.currentTimeMillis();
    }

    private boolean isTerminal() {
        return status == Status.COMMITTED
                || status == Status.FAILED
                || status == Status.REJECTED
                || status == Status.CANCELLED;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

