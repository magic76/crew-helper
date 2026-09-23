package com.crewpocket.helper;

/**
 * Pure ordering policy for Gemini Live tool calls vs authoritative finalized
 * user transcription.
 *
 * Tool frames and input transcription may arrive independently. A user-originated
 * operational tool may use its queued generation only while Runtime has an open
 * operational turn for that generation. Internal Runtime directives bypass this
 * user-transcript barrier.
 */
final class LiveTurnOrderingPolicy {
    enum Decision {
        BYPASS_INTERNAL,
        USE_QUEUED_GENERATION,
        USE_NEXT_FINALIZED_GENERATION,
        WAIT_FOR_FINALIZED
    }

    private LiveTurnOrderingPolicy() {}

    static Decision decide(
            boolean internalDirective,
            long queuedGeneration,
            long latestFinalizedGeneration,
            boolean queuedGenerationOpen) {
        if (internalDirective) {
            return Decision.BYPASS_INTERNAL;
        }
        if (queuedGenerationOpen) {
            return Decision.USE_QUEUED_GENERATION;
        }
        if (latestFinalizedGeneration == queuedGeneration + 1L) {
            return Decision.USE_NEXT_FINALIZED_GENERATION;
        }
        return Decision.WAIT_FOR_FINALIZED;
    }
}
