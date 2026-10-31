package com.crewpocket.helper;

/**
 * Protocol gates for Gemini 3.8 Live Extended Thinking.
 * turnComplete ends a spoken utterance, NOT its asynchronous interaction.
 * Ordinary Live preserves the existing turnComplete lifecycle.
 */
final class LiveExtendedThinkingPolicy {
    static final String STANDARD_MODEL = "gemini-3.8-live";
    static final String EXTENDED_MODEL = "gemini-3.8-live-extended-thinking";

    private LiveExtendedThinkingPolicy() {}

    static boolean isExtended(String model) {
        return EXTENDED_MODEL.equals(model);
    }

    static String safeModel(String model) {
        return isExtended(model) ? EXTENDED_MODEL : STANDARD_MODEL;
    }

    static String toolBehavior(boolean extended) {
        return extended ? "NON_BLOCKING" : "BLOCKING";
    }

    static boolean interactionComplete(
            boolean extended,
            boolean turnComplete,
            String interactionStatus,
            boolean pendingTools) {
        if (!extended) return turnComplete;
        return !pendingTools && "IDLE".equalsIgnoreCase(
                interactionStatus == null ? "" : interactionStatus.trim());
    }

    static boolean interactionInProgress(
            boolean extended,
            String interactionStatus) {
        return extended && "IN_PROGRESS".equalsIgnoreCase(
                interactionStatus == null ? "" : interactionStatus.trim());
    }
}
