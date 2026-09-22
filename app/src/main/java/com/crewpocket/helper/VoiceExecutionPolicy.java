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
        // Explicit one-shot SEND and explicit bounded conversation delegation
        // do not need a second redundant "確認嗎". Runtime still requires the
        // original user intent, a bound recipient, lease limits, and exact-chat
        // Accessibility verification before every actual SEND.
        if ("start_conversation_loop".equals(runtimeName)) {
            return false;
        }
        if (!"tap_screen".equals(runtimeName)
                && !"tap_element".equals(runtimeName)) {
            return false;
        }
        return ActionSafetyPolicy.blocks(metadata);
    }
}
