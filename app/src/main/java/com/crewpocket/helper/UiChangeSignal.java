package com.crewpocket.helper;

/**
 * Monotonic UI-change signal used by Runtime verification.
 *
 * Accessibility events advance a revision and wake waiters. Callers still
 * perform semantic verification after waking; an event is only a timing hint,
 * never proof that an action succeeded.
 */
final class UiChangeSignal {
    private long revision;

    synchronized long revision() {
        return revision;
    }

    synchronized long markChanged() {
        revision++;
        notifyAll();
        return revision;
    }

    synchronized boolean awaitChange(long afterRevision, long timeoutMs) {
        long boundedTimeout = Math.max(0L, timeoutMs);
        long deadlineNanos = System.nanoTime() + boundedTimeout * 1_000_000L;

        while (revision <= afterRevision) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) break;
            long waitMs = Math.max(1L, remainingNanos / 1_000_000L);
            try {
                wait(waitMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return revision > afterRevision;
    }
}
