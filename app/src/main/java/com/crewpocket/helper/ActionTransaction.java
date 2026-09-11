package com.crewpocket.helper;

/**
 * One mutation/action lifecycle. v1 records lifecycle only; it does not yet
 * decide whether NativeGeminiLiveClient may execute an action.
 */
final class ActionTransaction {
    enum Status {
        CREATED,
        STARTED,
        EXECUTED,
        OBSERVED,
        VERIFIED,
        COMMITTED,
        FAILED,
        CANCELLED
    }

    enum ExpectedEffect {
        UNKNOWN,
        ANY_OBSERVABLE_CHANGE,
        SCREEN_CHANGE,
        APP_CHANGE,
        FOCUS_CHANGE,
        TEXT_CHANGE,
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
    final long createdAtMs;

    private Status status = Status.CREATED;
    private String beforeFingerprint = "";
    private String afterFingerprint = "";
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
                      String beforeFingerprint) {
        this.actionId = safe(actionId);
        this.generation = generation;
        this.goalId = safe(goalId);
        this.taskId = safe(taskId);
        this.toolCallId = safe(toolCallId);
        this.requestedName = safe(requestedName);
        this.runtimeName = safe(runtimeName);
        this.expectedEffect = expectedEffect == null ? ExpectedEffect.UNKNOWN : expectedEffect;
        this.beforeFingerprint = safe(beforeFingerprint);
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = createdAtMs;
    }

    synchronized Status status() { return status; }
    synchronized String beforeFingerprint() { return beforeFingerprint; }
    synchronized String afterFingerprint() { return afterFingerprint; }
    synchronized String resultCode() { return resultCode; }
    synchronized long updatedAtMs() { return updatedAtMs; }

    synchronized void start() {
        transition(Status.CREATED, Status.STARTED);
    }

    synchronized void markExecuted() {
        if (status == Status.STARTED) set(Status.EXECUTED);
    }

    synchronized void observe(String fingerprint) {
        if (isTerminal()) return;
        afterFingerprint = safe(fingerprint);
        if (status == Status.STARTED || status == Status.EXECUTED) set(Status.OBSERVED);
    }

    synchronized void verify(boolean success, String code) {
        if (isTerminal()) return;
        resultCode = safe(code);
        if (success) set(Status.VERIFIED);
        else set(Status.FAILED);
    }

    synchronized void commit() {
        if (status == Status.VERIFIED || status == Status.OBSERVED || status == Status.EXECUTED || status == Status.STARTED) {
            set(Status.COMMITTED);
        }
    }

    synchronized void fail(String code) {
        if (isTerminal()) return;
        resultCode = safe(code);
        set(Status.FAILED);
    }

    synchronized void cancel(String code) {
        if (isTerminal()) return;
        resultCode = safe(code);
        set(Status.CANCELLED);
    }

    synchronized boolean screenChanged() {
        return !beforeFingerprint.isEmpty()
                && !afterFingerprint.isEmpty()
                && !beforeFingerprint.equals(afterFingerprint);
    }

    private void transition(Status expected, Status next) {
        if (status == expected) set(next);
    }

    private void set(Status value) {
        status = value;
        updatedAtMs = System.currentTimeMillis();
    }

    private boolean isTerminal() {
        return status == Status.COMMITTED || status == Status.FAILED || status == Status.CANCELLED;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

