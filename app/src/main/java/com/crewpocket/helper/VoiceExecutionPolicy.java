package com.crewpocket.helper;

/** Pure voice execution risk policy, independently replay/testable. */
final class VoiceExecutionPolicy {
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.72d;

    private VoiceExecutionPolicy() {}

    static boolean shouldRepeat(String finalizedText, double confidence) {
        if (confidence >= 0.0d
                && confidence < LOW_CONFIDENCE_THRESHOLD) {
            return true;
        }
        return VoiceCommandQualityPolicy.looksIncomplete(finalizedText);
    }

    static boolean bypassesMessageVoiceGate(
            String runtimeName,
            String metadata,
            boolean noConfirmationEnabled) {
        if (!noConfirmationEnabled) return false;
        if ("send_text".equals(runtimeName)
                || "start_conversation_loop".equals(runtimeName)) {
            return true;
        }
        return ("tap_screen".equals(runtimeName)
                        || "tap_element".equals(runtimeName))
                && SendAuthorization.looksLikeSendTarget(metadata);
    }

    static boolean requiresReliableTranscript(
            String runtimeName,
            String metadata) {
        if ("send_text".equals(runtimeName)
                || "start_conversation_loop".equals(runtimeName)) {
            return true;
        }
        if ("tap_screen".equals(runtimeName)
                || "tap_element".equals(runtimeName)) {
            return SendAuthorization.looksLikeSendTarget(metadata)
                    || ActionSafetyPolicy.blocks(metadata);
        }
        return false;
    }

    static boolean requiresCriticalEntityConfirmation(
            String runtimeName,
            String metadata) {
        // Conversation-loop delegation keeps one confirmation by default.
        // The user-controlled Message Send No Confirmation preference bypasses
        // this preflight in NativeGeminiLiveClient when explicitly enabled.
        if ("start_conversation_loop".equals(runtimeName)) {
            return true;
        }
        if (!"tap_screen".equals(runtimeName)
                && !"tap_element".equals(runtimeName)) {
            return false;
        }
        return ActionSafetyPolicy.blocks(metadata);
    }
}
