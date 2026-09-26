package com.crewpocket.helper;

/**
 * Pure counter transition for a reliable post-task correction.
 *
 * The entire causal set is conservatively quarantined. Only a memory that got
 * an independent-success vote from the corrected task retracts one success.
 */
final class RefinedMemoryCorrectionPolicy {
    static final class Result {
        final int successCount;
        final int failureCount;
        final String state;
        final double confidence;

        Result(
                int successCount,
                int failureCount,
                String state,
                double confidence) {
            this.successCount = Math.max(0, successCount);
            this.failureCount = Math.max(0, failureCount);
            this.state = state == null
                    ? RefinedMemoryPolicy.STATE_SUSPECT
                    : state;
            this.confidence = confidence;
        }
    }

    private RefinedMemoryCorrectionPolicy() {}

    static Result apply(
            int successCount,
            int failureCount,
            boolean learnedByCorrectedTask) {
        int success = Math.max(0, successCount);
        int failure = Math.max(0, failureCount);

        if (learnedByCorrectedTask && success > 0) {
            success--;
        }
        failure++;

        return new Result(
                success,
                failure,
                RefinedMemoryPolicy.STATE_SUSPECT,
                RefinedMemoryPolicy.confidenceFor(
                        success,
                        failure));
    }
}
