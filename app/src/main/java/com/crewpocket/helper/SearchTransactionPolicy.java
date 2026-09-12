package com.crewpocket.helper;

/**
 * Pure policy for the generic SEARCH transaction.
 */
final class SearchTransactionPolicy {
    static final String FAILED = "FAILED";
    static final String QUERY_ENTERED = "QUERY_ENTERED";
    static final String PENDING_RESULTS = "PENDING_RESULTS";
    static final String LIVE_RESULTS_OBSERVED = "LIVE_RESULTS_OBSERVED";
    static final String RESULTS_OBSERVED = "RESULTS_OBSERVED";

    private SearchTransactionPolicy() {}

    static Decision decide(boolean textVerified,
                           boolean liveSurfaceChanged,
                           boolean commitDispatched,
                           boolean postCommitSurfaceChanged) {
        if (!textVerified) return new Decision(FAILED, false);
        if (postCommitSurfaceChanged) return new Decision(RESULTS_OBSERVED, true);
        if (liveSurfaceChanged) return new Decision(LIVE_RESULTS_OBSERVED, true);
        if (commitDispatched) return new Decision(PENDING_RESULTS, false);
        return new Decision(QUERY_ENTERED, false);
    }

    static final class Decision {
        final String state;
        final boolean resultsObserved;

        Decision(String state, boolean resultsObserved) {
            this.state = state == null ? FAILED : state;
            this.resultsObserved = resultsObserved;
        }
    }
}
