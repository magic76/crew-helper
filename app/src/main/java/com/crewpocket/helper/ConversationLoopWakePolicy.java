package com.crewpocket.helper;

/** Pure policy for deciding how a retained conversation wake acquires Agent execution. */
final class ConversationLoopWakePolicy {
    enum Decision {
        REUSE_LOOP_TASK,
        DEFER_FOR_FOREGROUND_TASK,
        CREATE_BACKGROUND_TASK
    }

    private ConversationLoopWakePolicy() {}

    static Decision decide(
            boolean activeTaskExists,
            boolean activeTaskOwnedByLoop) {
        if (!activeTaskExists) {
            return Decision.CREATE_BACKGROUND_TASK;
        }
        return activeTaskOwnedByLoop
                ? Decision.REUSE_LOOP_TASK
                : Decision.DEFER_FOR_FOREGROUND_TASK;
    }
}
