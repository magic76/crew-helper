package com.crewpocket.helper;

/** Pure visual precedence for the floating Crew logo. */
final class BubbleLogoStatePolicy {
    enum Mode {
        IDLE,
        LISTENING,
        SPEAKING,
        WORKING,
        WAITING_USER,
        CONVERSATION_WAITING,
        ERROR
    }

    private BubbleLogoStatePolicy() {}

    static Mode resolve(
            int nativeVoiceState,
            boolean agentWorking,
            boolean agentNeedsAttention,
            boolean conversationWaiting) {
        if (nativeVoiceState == 3) return Mode.ERROR;
        if (agentNeedsAttention) return Mode.WAITING_USER;
        if (agentWorking) return Mode.WORKING;
        if (nativeVoiceState == 2) return Mode.SPEAKING;
        if (conversationWaiting) return Mode.CONVERSATION_WAITING;
        if (nativeVoiceState == 1) return Mode.LISTENING;
        return Mode.IDLE;
    }
}
