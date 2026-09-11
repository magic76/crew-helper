package com.crewpocket.helper;

/** Authoritative runtime interpretation of one mutation attempt. */
final class ActionVerificationResult {
    enum Status {
        VERIFIED,
        LIKELY,
        PENDING,
        FAILED,
        BLOCKED,
        CANCELLED
    }

    final Status status;
    final String code;
    final boolean screenChanged;
    final boolean stableScreenChanged;
    final boolean packageChanged;
    final boolean focusChanged;

    ActionVerificationResult(Status status,
                             String code,
                             boolean screenChanged,
                             boolean stableScreenChanged,
                             boolean packageChanged,
                             boolean focusChanged) {
        this.status = status == null ? Status.FAILED : status;
        this.code = code == null ? "" : code;
        this.screenChanged = screenChanged;
        this.stableScreenChanged = stableScreenChanged;
        this.packageChanged = packageChanged;
        this.focusChanged = focusChanged;
    }

    boolean committed() {
        return status == Status.VERIFIED || status == Status.LIKELY;
    }

    boolean pending() {
        return status == Status.PENDING;
    }

    boolean failed() {
        return status == Status.FAILED || status == Status.BLOCKED || status == Status.CANCELLED;
    }

    /**
     * Model may continue only when the runtime has evidence, or when the action
     * was accepted but explicitly marked PENDING and the next requirement is observation.
     */
    boolean modelCanContinue() {
        return committed() || pending();
    }
}

