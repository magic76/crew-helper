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

        // 0132: generic search + open/select must not enter the Maps-only
        // deterministic result picker. Live receives the fresh screen and may
        // perform a normal semantic TAP on the visible first result.
        UserActionScope contacts = new UserActionScope();
        contacts.updateFromUserText("搜尋聯絡人後選第一個");
        check(contacts.shouldAutoCommitSearch(),
                "contact search should still use Runtime search transaction");
        contacts.markSearchQueryEntered();
        contacts.markSearchCommitted();
        check(!contacts.shouldSelectSearchResult(),
                "generic contact search must not use Maps-only result picker");
        check("OPEN_RESULT".equals(contacts.searchContinuation()),
                "generic contact search should preserve open-result intent");
        check(!contacts.shouldBlockTapForSearch("first result", false),
                "semantic TAP after generic search must remain authorized");

        UserActionScope maps = new UserActionScope();
        maps.updateFromUserText("導航到台北101");
        maps.markSearchQueryEntered();
        maps.markSearchCommitted();
        check(maps.shouldSelectSearchResult(),
                "navigation flow should retain deterministic result selection");

        // 0133: once a search submit has left Runtime, the same human intent is
        // monotonic. A weak model may emit SEARCH/TYPE again after results
        // appear; Runtime must preserve the result surface instead of reopening
        // the editor. Identity drift must not bypass this guard.
        UserActionScope monotonic = new UserActionScope();
        monotonic.updateFromUserText("搜尋林家花園");
        monotonic.markSearchQueryEntered(
                "林家花園", "com.google.android.apps.maps", 7L);
        monotonic.markSearchSubmissionDispatched();
        check(monotonic.shouldSuppressDuplicateSearch(
                        "林家花園",
                        "com.google.android.apps.maps",
                        7L),
                "identical search after submit must be suppressed");
        check(monotonic.shouldSuppressDuplicateSearch(
                        "林家花園 ",
                        "com.google.android.apps.maps",
                        8L),
                "generation drift must not reopen submitted search");
        check(monotonic.shouldSuppressDuplicateSearch(
                        "林家花園景點",
                        "com.google.android.apps.maps",
                        7L),
                "query drift from weak model must not overwrite result surface");
        check(monotonic.shouldSuppressDuplicateSearch(
                        "林家花園",
                        "com.google.android.apps.maps.beta",
                        7L),
                "package observation drift must not reopen submitted search");

        monotonic.markSearchResultsObserved();
        check(monotonic.shouldSuppressDuplicateSearch(
                        "林家花園",
                        "com.google.android.apps.maps",
                        7L),
                "results-observed search must never regress to query entry");

        // A genuinely new human instruction resets the search transaction and
        // permits a new search.
        monotonic.updateFromUserText("搜尋板橋車站");
        check(!monotonic.shouldSuppressDuplicateSearch(
                        "板橋車站",
                        "com.google.android.apps.maps",
                        8L),
                "new human search intent must release prior monotonic guard");

        System.out.println("PASS SearchTransactionPolicyTest: "
                + assertions + " checks");
    }
}
