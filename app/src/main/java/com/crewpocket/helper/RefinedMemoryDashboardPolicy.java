package com.crewpocket.helper;

/** Pure truth rules for Refined Memory dashboard statistics. */
final class RefinedMemoryDashboardPolicy {
    static final int MIN_COMPARISON_SAMPLE = 5;

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

    static boolean hasSufficientComparisonSample(int sampleCount) {
        return sampleCount >= MIN_COMPARISON_SAMPLE;
    }
}
