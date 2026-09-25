package com.crewpocket.helper;

public final class UserActionScopeTest {
    private static int checks;

    public static void main(String[] args) {
        UserActionScope scope = new UserActionScope();

        scope.updateFromUserText("顯示元素");
        check(scope.consumeElementReferenceAuthorization(),
                "explicit user command grants one overlay open");
        check(!scope.consumeElementReferenceAuthorization(),
                "overlay authorization is one-shot");

        scope.updateFromUserText("播放 Apple Music 音樂");
        check(!scope.consumeElementReferenceAuthorization(),
                "ordinary task never grants element overlay");
        check(!scope.canStartFutureWait(),
                "ordinary media task never grants background wait");
        check(!scope.shouldAutoCommitSearch(),
                "media play does not require user to explicitly say search");
        scope.ensureSearchForGoal("MEDIA:PLAY");
        check(scope.shouldAutoCommitSearch(),
                "media play may create a goal-derived search transaction");
        check("MEDIA:PLAY".equals(scope.searchContinuation()),
                "goal-derived search preserves media continuation");

        scope.updateFromUserText("等到播放按鈕出現後點它");
        check(scope.canStartFutureWait(),
                "explicit future condition grants one background wait");
        scope.consumeFutureWaitAuthorization();
        check(!scope.canStartFutureWait(),
                "future wait authorization is one-shot");

        scope.updateFromTrustedAction("等到畫面變化後通知");
        check(!scope.canStartFutureWait(),
                "trusted/internal action cannot grant future wait authority");


        scope.updateFromUserText("搜尋蔡依林");
        check("STARTED".equals(scope.modelSearchPhase()),
                "search progress starts explicitly");
        check(scope.shouldAutoCommitSearch(),
                "pure search enables search transaction");
        scope.markSearchQueryEntered();
        check("QUERY_ENTERED".equals(scope.modelSearchPhase()),
                "query-entered search phase survives tool turns");
        check(scope.shouldBlockTapForSearch("蔡依林", false),
                "generic pure-search result remains blocked before commit");
        check(!scope.shouldBlockTapForSearch(
                        "蔡依林",
                        false,
                        true),
                "bounded low-risk media browse may open a result row");
        scope.markSearchCommitted();
        check("COMMIT_DISPATCHED".equals(scope.modelSearchPhase()),
                "commit-dispatched phase is explicit before result evidence");
        check(scope.shouldSuppressSearchCommit(),
                "already dispatched commit is suppressed");
        check(scope.shouldBlockTapForSearch("蔡依林", false),
                "pure search still blocks opening a result");

        scope.updateFromUserText("搜尋蔡依林播放");
        check(scope.shouldAutoCommitSearch(),
                "search then play enables search transaction");
        check("MEDIA:PLAY".equals(scope.searchContinuation()),
                "media play is preserved as post-search continuation");
        scope.markSearchQueryEntered();
        scope.markSearchResultsObserved();
        check("RESULTS_OBSERVED".equals(scope.modelSearchPhase()),
                "result evidence has its own persistent search phase");
        check(scope.shouldSuppressSearchCommit(),
                "result phase also suppresses duplicate commit");
        check(!scope.shouldBlockTapForSearch("蔡依林", false),
                "search then play may open the artist result");

        scope.updateFromUserText("搜尋大皇宮導航");
        scope.markSearchQueryEntered();
        scope.markSearchCommitted();
        check(!scope.shouldBlockTapForSearch("大皇宮", false),
                "navigation continuation remains allowed after search");

        scope.updateFromUserText("element_reference:open");
        check(!scope.consumeElementReferenceAuthorization(),
                "model marker text is not user authorization");

        scope.updateFromUserText("Show clickable elements");
        check(scope.consumeElementReferenceAuthorization(),
                "English explicit user request grants overlay");

        scope.updateFromUserText("顯示元素");
        scope.updateFromUserText("打開地圖");
        check(!scope.consumeElementReferenceAuthorization(),
                "new user turn replaces stale overlay authorization");

        System.out.println(
                "PASS UserActionScopeTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
