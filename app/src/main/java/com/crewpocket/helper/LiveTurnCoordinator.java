package com.crewpocket.helper;

/**
 * Runtime-owned ordering primitive for Gemini Live user turns.
 *
 * Gemini may deliver finalized user transcription and tool calls in separate
 * WebSocket frames with no useful cross-frame ordering guarantee. This class
 * is the single synchronization point for authoritative finalized user turns.
 *
 * It does not authorize actions and never derives intent from tool calls.
 */
final class LiveTurnCoordinator {
    static final class FinalizedTurn {
        final long generation;
        final String text;

        FinalizedTurn(long generation, String text) {
            this.generation = generation;
            this.text = text == null ? "" : text;
        }

        boolean availableAfter(long olderGeneration) {
            return generation > olderGeneration;
        }
    }

    private long finalizedGeneration = -1L;
    private String finalizedText = "";
    private long operationalGeneration = -1L;
    private boolean operationalOpen;

    synchronized void reset() {
        finalizedGeneration = -1L;
        finalizedText = "";
        operationalGeneration = -1L;
        operationalOpen = false;
        notifyAll();
    }

    synchronized void onFinalizedUserTurn(long generation, String text) {
        if (generation < finalizedGeneration) return;
        finalizedGeneration = generation;
        finalizedText = text == null ? "" : text.trim();
        operationalGeneration = generation;
        operationalOpen = true;
        notifyAll();
    }

    synchronized void openOperationalGeneration(long generation) {
        operationalGeneration = generation;
        operationalOpen = true;
        notifyAll();
    }

    synchronized void closeOperationalGeneration(long generation) {
        if (operationalGeneration != generation) return;
        operationalOpen = false;
        notifyAll();
    }

    synchronized boolean isOperationalGenerationOpen(long generation) {
        return operationalOpen && operationalGeneration == generation;
    }

    synchronized FinalizedTurn latest() {
        return new FinalizedTurn(finalizedGeneration, finalizedText);
    }

    synchronized FinalizedTurn awaitNextAfter(
            long olderGeneration,
            long timeoutMs) {
        long boundedTimeout = Math.max(0L, timeoutMs);
        long deadlineNanos = System.nanoTime() + boundedTimeout * 1_000_000L;

        while (finalizedGeneration <= olderGeneration) {
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
        return new FinalizedTurn(finalizedGeneration, finalizedText);
    }
}
