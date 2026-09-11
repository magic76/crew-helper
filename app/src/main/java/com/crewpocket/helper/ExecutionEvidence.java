package com.crewpocket.helper;

/** Raw execution evidence before semantic verification. */
final class ExecutionEvidence {
    final boolean executionAccepted;
    final boolean runtimeVerified;
    final boolean blocked;
    final boolean cancelled;
    final boolean allowDelayedUi;
    final String errorCode;

    ExecutionEvidence(boolean executionAccepted,
                      boolean runtimeVerified,
                      boolean blocked,
                      boolean cancelled,
                      boolean allowDelayedUi,
                      String errorCode) {
        this.executionAccepted = executionAccepted;
        this.runtimeVerified = runtimeVerified;
        this.blocked = blocked;
        this.cancelled = cancelled;
        this.allowDelayedUi = allowDelayedUi;
        this.errorCode = safe(errorCode);
    }

    static ExecutionEvidence accepted(boolean runtimeVerified) {
        return new ExecutionEvidence(true, runtimeVerified, false, false, false, "");
    }

    static ExecutionEvidence pendingAccepted() {
        return new ExecutionEvidence(true, false, false, false, true, "");
    }

    static ExecutionEvidence failed(String code) {
        return new ExecutionEvidence(false, false, false, false, false, code);
    }

    static ExecutionEvidence blocked(String code) {
        return new ExecutionEvidence(false, false, true, false, false, code);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

