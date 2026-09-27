package com.crewpocket.helper;

/**
 * Deterministic guard against repeatedly asking the model to inspect the same
 * semantic screen without any Runtime-observed progress.
 *
 * The guard never blocks merely because inspect_ui was called several times.
 * A changed fingerprint or a newly resolved Runtime revalidation resets it.
 */
final class ObservationLoopPolicy {
    static final int MAX_SAME_SCREEN_OBSERVATIONS = 1;
    static final String BLOCK_CODE = "NO_PROGRESS_LOOP";

    static final class Decision {
        final boolean blocked;
        final int nextSameScreenCount;
        final String fingerprint;

        Decision(
                boolean blocked,
                int nextSameScreenCount,
                String fingerprint) {
            this.blocked = blocked;
            this.nextSameScreenCount =
                    Math.max(0, nextSameScreenCount);
            this.fingerprint =
                    fingerprint == null ? "" : fingerprint;
        }
    }

    private ObservationLoopPolicy() {}

    static Decision evaluate(
            String previousFingerprint,
            int previousSameScreenCount,
            String currentFingerprint,
            boolean semanticProgress) {
        String previous = safe(previousFingerprint);
        String current = safe(currentFingerprint);

        if (current.isEmpty()) {
            return new Decision(false, 0, "");
        }

        if (semanticProgress
                || previous.isEmpty()
                || !previous.equals(current)) {
            return new Decision(false, 1, current);
        }

        int next = Math.max(1, previousSameScreenCount) + 1;
        if (next > MAX_SAME_SCREEN_OBSERVATIONS) {
            // Keep the latch at the threshold. Further identical observations
            // remain blocked until a different action or fingerprint resets it.
            return new Decision(
                    true,
                    MAX_SAME_SCREEN_OBSERVATIONS,
                    current);
        }
        return new Decision(false, next, current);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
