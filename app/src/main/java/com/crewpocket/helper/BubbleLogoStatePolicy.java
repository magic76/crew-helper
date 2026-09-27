package com.crewpocket.helper;

/** Pure visual precedence for the floating Crew logo. */
final class BubbleLogoStatePolicy {
    enum Mode {
        IDLE,
        LISTENING,
        SPEAKING,
        THINKING,
        ACTING,
        WAITING,
        WAITING_USER,
        CONVERSATION_WAITING,
        STUCK,
        ERROR
    }

    private BubbleLogoStatePolicy() {}

    static Mode resolve(
            int nativeVoiceState,
            BubbleTaskPhasePolicy.Phase agentPhase,
            boolean agentNeedsAttention,
            boolean conversationWaiting) {
        if (nativeVoiceState == 3) return Mode.ERROR;
        if (agentNeedsAttention) return Mode.WAITING_USER;

        BubbleTaskPhasePolicy.Phase phase =
                agentPhase == null
                        ? BubbleTaskPhasePolicy.Phase.NONE
                        : agentPhase;
        if (phase == BubbleTaskPhasePolicy.Phase.STUCK) return Mode.STUCK;
        if (phase == BubbleTaskPhasePolicy.Phase.ACTING) return Mode.ACTING;
        if (phase == BubbleTaskPhasePolicy.Phase.WAITING) return Mode.WAITING;
        if (phase == BubbleTaskPhasePolicy.Phase.THINKING) return Mode.THINKING;

        if (nativeVoiceState == 2) return Mode.SPEAKING;
        if (conversationWaiting) return Mode.CONVERSATION_WAITING;
        if (nativeVoiceState == 1) return Mode.LISTENING;
        return Mode.IDLE;
    }
}
