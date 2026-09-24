package com.crewpocket.helper;

/**
 * Version boundary for Crew Experience evidence and learned lessons.
 *
 * Bump CURRENT_REVISION whenever a core Runtime policy changes enough that
 * historical friction should not confirm or promote rules under the new
 * behavior.
 */
final class ExperiencePolicyEpoch {
    static final int CURRENT_REVISION = 2;
    static final String STALE_REASON = "RUNTIME_POLICY_REVISION_CHANGED";

    private ExperiencePolicyEpoch() {}

    static boolean isCurrent(int revision) {
        return revision == CURRENT_REVISION;
    }

    static boolean shouldStale(int storedRevision) {
        return !isCurrent(storedRevision);
    }
}
