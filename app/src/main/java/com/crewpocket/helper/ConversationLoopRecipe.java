package com.crewpocket.helper;

/**
 * Bounded persistent conversation TaskRecipe.
 *
 * The model may understand/compose text, but Runtime owns the lease, recipient,
 * state transitions, expiry and reply count.
 */
final class ConversationLoopRecipe {
    enum State {
        IDLE,
        READY_TO_SEND,
        WAITING_FOR_MESSAGE,
        MESSAGE_PENDING,
        STOPPED,
        EXPIRED
    }

    static final class StartResult {
        final boolean success;
        final String code;
        StartResult(boolean success, String code) {
            this.success = success;
            this.code = code == null ? "" : code;
        }
    }

    private State state = State.IDLE;
    private String recipient = "";
    private long generation = -1L;
    private long startedAtMs;
    private long expiresAtMs;
    private int maxReplies;
    private int sentReplies;
    private String baselineFingerprint = "";
    private long epoch;

    synchronized StartResult start(
            String recipient,
            long generation,
            int requestedMaxReplies,
            int requestedTimeoutMinutes) {
        String target = recipient == null ? "" : recipient.trim();
        if (target.isEmpty()) {
            return new StartResult(false, "RECIPIENT_REQUIRED");
        }

        this.recipient = target;
        this.generation = generation;
        this.maxReplies = Math.max(1, Math.min(20, requestedMaxReplies));
        int minutes = Math.max(1, Math.min(30, requestedTimeoutMinutes));
        this.startedAtMs = System.currentTimeMillis();
        this.expiresAtMs = startedAtMs + minutes * 60_000L;
        this.sentReplies = 0;
        this.baselineFingerprint = "";
        this.state = State.READY_TO_SEND;
        this.epoch++;
        return new StartResult(true, "STARTED");
    }

    synchronized void stop(String reason) {
        state = State.STOPPED;
        baselineFingerprint = "";
        epoch++;
    }

    synchronized boolean isActive() {
        expireIfNeeded();
        return state == State.READY_TO_SEND
                || state == State.WAITING_FOR_MESSAGE
                || state == State.MESSAGE_PENDING;
    }

    synchronized boolean canSend() {
        expireIfNeeded();
        return state == State.READY_TO_SEND
                || state == State.MESSAGE_PENDING;
    }

    synchronized boolean isWaiting() {
        expireIfNeeded();
        return state == State.WAITING_FOR_MESSAGE;
    }

    synchronized boolean markSent(String fingerprint) {
        expireIfNeeded();
        if (!canSend()) return false;
        sentReplies++;
        baselineFingerprint = fingerprint == null ? "" : fingerprint;
        if (sentReplies >= maxReplies) {
            state = State.STOPPED;
            epoch++;
            return false;
        }
        state = State.WAITING_FOR_MESSAGE;
        epoch++;
        return true;
    }

    synchronized boolean rearmWait(String fingerprint) {
        expireIfNeeded();
        if (!isActive()) return false;
        baselineFingerprint = fingerprint == null ? "" : fingerprint;
        state = State.WAITING_FOR_MESSAGE;
        epoch++;
        return true;
    }

    synchronized boolean markMessagePending(String fingerprint) {
        expireIfNeeded();
        if (state != State.WAITING_FOR_MESSAGE) return false;
        String current = fingerprint == null ? "" : fingerprint;
        if (!baselineFingerprint.isEmpty()
                && baselineFingerprint.equals(current)) {
            return false;
        }
        state = State.MESSAGE_PENDING;
        baselineFingerprint = current;
        epoch++;
        return true;
    }

    synchronized boolean allowsTool(String name) {
        expireIfNeeded();
        if (!isActive()) return true;
        if ("stop_conversation_loop".equals(name)
                || "inspect_ui".equals(name)) {
            return true;
        }
        if (state == State.READY_TO_SEND) {
            return "send_text".equals(name)
                    || "launch_app".equals(name)
                    || "search_current_app".equals(name)
                    || "commit_search".equals(name)
                    || "tap_screen".equals(name)
                    || "tap_element".equals(name)
                    || "press_key".equals(name)
                    || "continue_conversation_loop".equals(name);
        }
        if (state == State.MESSAGE_PENDING) {
            return "send_text".equals(name)
                    || "continue_conversation_loop".equals(name);
        }
        return "continue_conversation_loop".equals(name);
    }

    synchronized String recipient() {
        expireIfNeeded();
        return recipient;
    }

    synchronized long generation() {
        expireIfNeeded();
        return generation;
    }

    synchronized long epoch() {
        return epoch;
    }

    synchronized String baselineFingerprint() {
        return baselineFingerprint;
    }

    synchronized State state() {
        expireIfNeeded();
        return state;
    }

    synchronized int sentReplies() {
        expireIfNeeded();
        return sentReplies;
    }

    synchronized int maxReplies() {
        expireIfNeeded();
        return maxReplies;
    }

    synchronized long expiresAtMs() {
        expireIfNeeded();
        return expiresAtMs;
    }

    private void expireIfNeeded() {
        if ((state == State.READY_TO_SEND
                || state == State.WAITING_FOR_MESSAGE
                || state == State.MESSAGE_PENDING)
                && System.currentTimeMillis() > expiresAtMs) {
            state = State.EXPIRED;
            epoch++;
        }
    }
}
