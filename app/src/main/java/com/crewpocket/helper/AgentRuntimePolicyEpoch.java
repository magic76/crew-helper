package com.crewpocket.helper;

/**
 * Version boundary for persistent Agent performance comparisons.
 *
 * Bump this when a Runtime lifecycle or execution policy changes enough that
 * pre-change task outcomes should not be mixed into the current-regression
 * window. The rolling 50-task history remains available separately.
 */
final class AgentRuntimePolicyEpoch {
    static final int CURRENT_REVISION = 2;

    private AgentRuntimePolicyEpoch() {}
}
