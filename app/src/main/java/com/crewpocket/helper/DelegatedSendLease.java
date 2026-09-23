package com.crewpocket.helper;

/**
 * Task-scoped authority for repeated SEND commits in the current visible chat.
 *
 * Semantics live with Gemini; this object only records the capability boundary
 * that Runtime granted after start_conversation_loop verified the chat surface.
 */
final class DelegatedSendLease {
    private boolean active;
    private String taskId = "";
    private long generation = -1L;
    private long expiresAtMs;
    private int maxSends;
    private int sentSends;

    synchronized void start(
            String taskId,
            long generation,
            int requestedMaxSends,
            int requestedTimeoutMinutes) {
        this.active = true;
        this.taskId = taskId == null ? "" : taskId;
        this.generation = generation;
        this.maxSends = Math.max(1, Math.min(20, requestedMaxSends));
        int minutes = Math.max(1, Math.min(30, requestedTimeoutMinutes));
        this.expiresAtMs =
                System.currentTimeMillis() + minutes * 60_000L;
        this.sentSends = 0;
    }

    synchronized boolean canSend(String currentTaskId) {
        expireIfNeeded();
        if (!active) return false;
        String current = currentTaskId == null ? "" : currentTaskId;
        return !taskId.isEmpty()
                && taskId.equals(current)
                && sentSends < maxSends;
    }

    synchronized boolean recordSend(String currentTaskId) {
        if (!canSend(currentTaskId)) return false;
        sentSends++;
        if (sentSends >= maxSends) {
            active = false;
        }
        return active;
    }

    synchronized void revoke(String reason) {
        active = false;
        taskId = "";
        generation = -1L;
        expiresAtMs = 0L;
        maxSends = 0;
        sentSends = 0;
    }

    synchronized boolean isActive() {
        expireIfNeeded();
        return active;
    }

    synchronized String taskId() {
        expireIfNeeded();
        return taskId;
    }

    synchronized long generation() {
        expireIfNeeded();
        return generation;
    }

    synchronized int sentSends() {
        expireIfNeeded();
        return sentSends;
    }

    synchronized int maxSends() {
        expireIfNeeded();
        return maxSends;
    }

    synchronized long expiresAtMs() {
        expireIfNeeded();
        return expiresAtMs;
    }

    private void expireIfNeeded() {
        if (active && System.currentTimeMillis() > expiresAtMs) {
            active = false;
        }
    }
}
