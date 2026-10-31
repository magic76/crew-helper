package com.crewpocket.helper;

/**
 * Gemini Live Extended Thinking may complete an audio utterance while the
 * interaction, including asynchronous tool calls, is still in progress.
 * The explicit IDLE status is the only terminal interaction signal.
 */
final class LiveInteractionCompletionPolicy {
    private LiveInteractionCompletionPolicy() {}

    static boolean isComplete(
            String rawInteractionStatus,
            boolean freshUserInput,
            boolean frameHasToolCalls,
            int outstandingToolCalls) {
        return "IDLE".equalsIgnoreCase(
                        rawInteractionStatus == null
                                ? "" : rawInteractionStatus.trim())
                && !freshUserInput
                && !frameHasToolCalls
                && outstandingToolCalls == 0;
    }
}
