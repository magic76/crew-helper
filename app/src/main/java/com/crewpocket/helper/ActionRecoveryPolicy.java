package com.crewpocket.helper;

/**
 * 0121 bounded recovery policy for phone mutations.
 *
 * Runtime may admit at most one retry after an explicit unchanged observation,
 * and only for actions that are safe to repeat. Message send, text mutation,
 * search commit and other domain-sensitive actions are never retryable here.
 */
final class ActionRecoveryPolicy {
    enum Decision {
        NONE,
        OBSERVE,
        RETRY_ONCE,
        STOP
    }

    private ActionRecoveryPolicy() {}

    static Decision decide(String runtimeName,
                           ActionVerificationResult verification,
                           int recoveryRetriesUsed,
                           boolean explicitlyObservedAfterFailure) {
        if (verification == null) return Decision.STOP;
        if (verification.committed()) return Decision.NONE;
        if (verification.pending()) return Decision.OBSERVE;
        if (!verification.failed()) return Decision.STOP;

        if (explicitlyObservedAfterFailure
                && recoveryRetriesUsed < 1
                && isSafeRetry(runtimeName)) {
            return Decision.RETRY_ONCE;
        }
        return Decision.STOP;
    }

    static boolean isSafeRetry(String runtimeName) {
        String name = runtimeName == null ? "" : runtimeName.trim();
        return "tap_screen".equals(name)
                || "tap_element".equals(name)
                || "launch_app".equals(name)
                || "press_key".equals(name);
    }
}
