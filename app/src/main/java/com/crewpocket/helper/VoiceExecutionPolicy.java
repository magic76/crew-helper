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

    static boolean requiresCriticalEntityConfirmation(
            String runtimeName,
            String metadata) {
        // A one-shot explicit message send no longer needs an extra voice
        // confirmation. Runtime still requires fresh SEND authorization and,
        // for named recipients, Accessibility verification of the exact chat.
        // Persistent delegated conversation loops remain confirmation-gated.
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
