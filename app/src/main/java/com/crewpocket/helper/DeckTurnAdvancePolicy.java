package com.crewpocket.helper;

/** Pure decision for whether a completed Live model turn may auto-advance Deck. */
final class DeckTurnAdvancePolicy {
    private DeckTurnAdvancePolicy() {}

    static boolean shouldAdvance(
            boolean producedSpeech,
            boolean receivedAudio,
            boolean blockingToolCall) {
        return producedSpeech && receivedAudio && !blockingToolCall;
    }
}
