package com.crewpocket.helper;

public final class SearchTransactionPolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        SearchTransactionPolicy.Decision failed =
                SearchTransactionPolicy.decide(false, true, true, true);
        check(SearchTransactionPolicy.FAILED.equals(failed.state),
                "unverified text cannot become search success");
        check(!failed.resultsObserved, "failed has no results");

        SearchTransactionPolicy.Decision postCommit =
                SearchTransactionPolicy.decide(true, false, true, true);
        check(SearchTransactionPolicy.RESULTS_OBSERVED.equals(postCommit.state),
                "post-commit surface change is results");
        check(postCommit.resultsObserved, "post-commit results observed");

        SearchTransactionPolicy.Decision liveNoCommit =
                SearchTransactionPolicy.decide(true, true, false, false);
        check(SearchTransactionPolicy.LIVE_RESULTS_OBSERVED.equals(liveNoCommit.state),
                "live-filter search supported without IME commit");
        check(liveNoCommit.resultsObserved, "live results observed");

        SearchTransactionPolicy.Decision liveWithCommitNoExtra =
                SearchTransactionPolicy.decide(true, true, true, false);
        check(SearchTransactionPolicy.LIVE_RESULTS_OBSERVED.equals(liveWithCommitNoExtra.state),
                "live evidence survives accepted no-op commit");

        SearchTransactionPolicy.Decision pending =
                SearchTransactionPolicy.decide(true, false, true, false);
        check(SearchTransactionPolicy.PENDING_RESULTS.equals(pending.state),
                "IME accepted without result evidence stays pending");
        check(!pending.resultsObserved, "pending is not results");

        SearchTransactionPolicy.Decision typedOnly =
                SearchTransactionPolicy.decide(true, false, false, false);
        check(SearchTransactionPolicy.QUERY_ENTERED.equals(typedOnly.state),
                "typed query without commit/result is query-entered");
        check(!typedOnly.resultsObserved, "query-entered is not results");

        System.out.println("PASS SearchTransactionPolicyTest: "
                + assertions + " checks");
    }
}
