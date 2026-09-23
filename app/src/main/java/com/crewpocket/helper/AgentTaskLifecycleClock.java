package com.crewpocket.helper;

/** Pure time accounting for Agent tasks that may sleep on external events. */
final class AgentTaskLifecycleClock {
    private AgentTaskLifecycleClock() {}

    static long effectiveStartedAt(
            long startedAtMs,
            long accumulatedSuspendedMs,
            long suspendedAtMs,
            boolean suspended,
            long nowMs) {
        long paused = Math.max(0L, accumulatedSuspendedMs);
        if (suspended && suspendedAtMs > 0L && nowMs > suspendedAtMs) {
            paused += nowMs - suspendedAtMs;
        }
        return startedAtMs + paused;
    }

    static long accumulatedAfterResume(
            long accumulatedSuspendedMs,
            long suspendedAtMs,
            long nowMs) {
        long accumulated = Math.max(0L, accumulatedSuspendedMs);
        if (suspendedAtMs > 0L && nowMs > suspendedAtMs) {
            accumulated += nowMs - suspendedAtMs;
        }
        return accumulated;
    }
}
