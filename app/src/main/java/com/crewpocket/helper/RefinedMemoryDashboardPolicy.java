package com.crewpocket.helper;

/** Pure truth and aggregation rules for Refined Memory dashboard statistics. */
final class RefinedMemoryDashboardPolicy {
    static final int MIN_COMPARISON_SAMPLE = 5;

    static final class UsageAccumulator {
        int usedTasks;
        int verifiedTasks;
        int appliedTasks;
        long usedSteps;
        long usedDuration;
        int baselineTasks;
        int baselineVerified;
        long baselineSteps;
        long baselineDuration;

        void observe(
                boolean sameScope,
                boolean memoryUsageKnown,
                boolean containsMemory,
                boolean hasAnyMemory,
                boolean terminalVerified,
                boolean corrected,
                boolean applied,
                int stepCount,
                long durationMs) {
            if (!sameScope) return;
            int steps = Math.max(0, stepCount);
            long duration = Math.max(0L, durationMs);

            if (containsMemory) {
                usedTasks++;
                usedSteps += steps;
                usedDuration += duration;
                if (isVerifiedWin(terminalVerified, corrected)) {
                    verifiedTasks++;
                }
                if (applied) appliedTasks++;
                return;
            }

            if (shouldCountAsBaseline(
                    memoryUsageKnown,
                    hasAnyMemory ? 1 : 0)) {
                baselineTasks++;
                baselineSteps += steps;
                baselineDuration += duration;
                if (isVerifiedWin(terminalVerified, corrected)) {
                    baselineVerified++;
                }
            }
        }
    }

    static final class OverallAccumulator {
        int memoryTasks;
        int memoryVerified;
        int noMemoryTasks;
        int noMemoryVerified;

        void observe(
                boolean memoryUsageKnown,
                boolean hasAnyMemory,
                boolean terminalVerified,
                boolean corrected) {
            if (!memoryUsageKnown) return;
            if (hasAnyMemory) {
                memoryTasks++;
                if (isVerifiedWin(terminalVerified, corrected)) {
                    memoryVerified++;
                }
            } else {
                noMemoryTasks++;
                if (isVerifiedWin(terminalVerified, corrected)) {
                    noMemoryVerified++;
                }
            }
        }
    }

    private RefinedMemoryDashboardPolicy() {}

    static boolean shouldCountAsBaseline(
            boolean memoryUsageKnown,
            int usedMemoryCount) {
        return memoryUsageKnown
                && Math.max(0, usedMemoryCount) == 0;
    }

    static boolean isVerifiedWin(
            boolean terminalVerified,
            boolean corrected) {
        return terminalVerified && !corrected;
    }

    static boolean isAppliedPattern(
            String storedPattern,
            String actualPattern) {
        return RefinedMemoryPolicy.patternsEquivalent(
                storedPattern,
                actualPattern);
    }

    static boolean hasSufficientComparisonSample(int sampleCount) {
        return sampleCount >= MIN_COMPARISON_SAMPLE;
    }
}
