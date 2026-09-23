package com.crewpocket.helper;

/** Pure policy for distinguishing real human composer edits from chat/UI noise. */
final class ConversationLoopTakeoverPolicy {
    private ConversationLoopTakeoverPolicy() {}

    static boolean isHumanComposerEdit(
            boolean textChangedEvent,
            boolean sourceEditable,
            boolean sourceFocused,
            boolean runtimeTextMutationSuppressed) {
        return textChangedEvent
                && sourceEditable
                && sourceFocused
                && !runtimeTextMutationSuppressed;
    }
}
