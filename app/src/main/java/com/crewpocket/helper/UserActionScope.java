package com.crewpocket.helper;

/**
 * Deterministic latest-turn action boundary.
 *
 * Cross-action latest-turn boundary for search, call ending and learning.
 * SEND permission is intentionally isolated in SendAuthorization; compatibility
 * accessors here only delegate to that single-purpose state object.
 */
final class UserActionScope {
    private static final long SCOPE_TTL_MS = 120_000L;

    private final SendAuthorization sendAuthorization = new SendAuthorization();

    private boolean searchIntent;
    private boolean openSearchResultAuthorized;
    private boolean searchResultSelectionRequested;
    private String searchContinuation = "";
    private boolean searchQueryEntered;
    private boolean searchCommitted;
    // 0105: ephemeral, current-turn search transaction identity. Never persisted.
    private boolean searchSubmissionDispatched;
    private String searchTransactionQuery = "";
    private String searchTransactionPackage = "";
    private long searchTransactionGeneration = -1L;
    private boolean searchResultSelected;
    private boolean searchResultSelectionDispatched;
    private String selectedSearchResult = "";
    private String dispatchedSearchResult = "";
    private long updatedAtMs;
    private boolean endCallAuthorized;
    private boolean appLearningAuthorized;
    private boolean elementReferenceAuthorized;

    synchronized boolean consumeEndCallAuthorization() {
        expireIfNeeded();
        boolean authorized = endCallAuthorized;
        endCallAuthorized = false;
        return authorized;
    }

    synchronized boolean consumeAppLearningAuthorization() {
        expireIfNeeded();
        boolean authorized = appLearningAuthorized;
        appLearningAuthorized = false;
        return authorized;
    }

    synchronized void updateFromUserText(String text) {
        TextEntryGoalGuard.updateFromUserText(text);
        update(text);
        elementReferenceAuthorized =
                ElementReferenceCommand.isUserOpenRequest(text);
    }

    synchronized void updateFromTrustedAction(String action) {
        update(action);
        elementReferenceAuthorized = false;
    }

    synchronized boolean consumeElementReferenceAuthorization() {
        expireIfNeeded();
        boolean authorized = elementReferenceAuthorized;
        elementReferenceAuthorized = false;
        return authorized;
    }

    private void update(String text) {
        String value = normalize(text);
        boolean explicitAppLearning = hasAppLearningIntent(text);

        // Only command-level negation/questions clear action grants.
        // Do not scan the entire utterance for words such as "不要" or "如果":
        // those may be literal message content (e.g. 跟小明說如果下雨就不要來).
        if (isCommandLevelNonExecuting(text, value)) {
            clearActionGrants();
            // Negated operational guidance such as “記住，以後不要點這個”
            // may be learned, while every phone-action grant remains cleared.
            appLearningAuthorized = explicitAppLearning;
            updatedAtMs = System.currentTimeMillis();
            return;
        }

        endCallAuthorized = value.matches("(?:請|幫我|请|帮我)?(?:結束通話|结束通话|掛斷電話|挂断电话|退出語音助理|退出语音助理)(?:吧|謝謝|谢谢)?")
                || value.matches("(?:please)?(?:endthecall|hangup|exitthevoiceassistant)(?:please)?");

        appLearningAuthorized = explicitAppLearning;

        boolean navigation = hasNavigationIntent(value);
        boolean search = hasSearchIntent(value) || navigation;
        // Runtime's deterministic result-selection adapter is currently Maps-only.
        // Reserve it for navigation flows. Generic "search then open/select" stays
        // authorized but returns the fresh result screen to Live for a semantic TAP.
        boolean resultSelection = navigation;
        boolean openResult = navigation || hasPostSearchOpenIntent(value);

        sendAuthorization.updateFromUserText(text);

        searchIntent = search;
        searchResultSelectionRequested = resultSelection;
        searchContinuation = navigation
                ? "NAVIGATE"
                : (openResult ? "OPEN_RESULT" : "RESULT_DETAILS");
        openSearchResultAuthorized = openResult;
        searchQueryEntered = false;
        searchCommitted = false;
        searchSubmissionDispatched = false;
        searchTransactionQuery = "";
        searchTransactionPackage = "";
        searchTransactionGeneration = -1L;
        searchResultSelected = false;
        searchResultSelectionDispatched = false;
        selectedSearchResult = "";
        dispatchedSearchResult = "";
        updatedAtMs = System.currentTimeMillis();
    }

    synchronized boolean canSend() {
        expireIfNeeded();
        return sendAuthorization.canAttempt();
    }

    synchronized void consumeSendAuthorization() {
        sendAuthorization.consume();
    }

    synchronized boolean requiresRecipientVerification() {
        expireIfNeeded();
        return sendAuthorization.requiresRecipientVerification();
    }

    synchronized String authorizedRecipient() {
        expireIfNeeded();
        return sendAuthorization.recipient();
    }

    synchronized void markMessageCommitDispatched() {
        expireIfNeeded();
        sendAuthorization.markCommitDispatched();
    }

    synchronized boolean isMessageCommitDispatched() {
        expireIfNeeded();
        return sendAuthorization.isCommitDispatched();
    }

    synchronized boolean blocksNamedRecipientMessagingAction() {
        expireIfNeeded();
        return sendAuthorization.hasAmbiguousNamedRecipient();
    }

    synchronized boolean shouldAutoCommitSearch() {
        expireIfNeeded();
        return searchIntent;
    }

    /** Search suggestions are not results and must never trigger a result picker. */
    synchronized boolean isSearchInProgress() {
        expireIfNeeded();
        return searchIntent && !searchCommitted;
    }

    synchronized boolean hasCommittedSearch() {
        expireIfNeeded();
        return searchIntent && searchCommitted;
    }

    /** A Maps result was opened successfully; the next action must continue it. */
    synchronized boolean hasSelectedSearchResult() {
        expireIfNeeded();
        return searchIntent && searchCommitted && searchResultSelected;
    }

    synchronized boolean hasDispatchedSearchResultSelection() {
        expireIfNeeded();
        return searchIntent && searchCommitted
                && searchResultSelectionDispatched && !searchResultSelected;
    }

    synchronized String dispatchedSearchResult() {
        expireIfNeeded();
        return dispatchedSearchResult == null ? "" : dispatchedSearchResult;
    }

    synchronized String selectedSearchResult() {
        expireIfNeeded();
        return selectedSearchResult == null ? "" : selectedSearchResult;
    }

    synchronized boolean shouldSelectSearchResult() {
        expireIfNeeded();
        return searchIntent && searchCommitted && searchResultSelectionRequested;
    }

    synchronized String searchContinuation() {
        expireIfNeeded();
        return searchContinuation == null ? "" : searchContinuation;
    }

    synchronized boolean markSearchQueryEntered() {
        expireIfNeeded();
        if (!searchIntent) return false;
        searchQueryEntered = true;
        searchCommitted = false;
        searchSubmissionDispatched = false;
        searchTransactionQuery = "";
        searchTransactionPackage = "";
        searchTransactionGeneration = -1L;
        searchResultSelectionDispatched = false;
        searchResultSelected = false;
        dispatchedSearchResult = "";
        selectedSearchResult = "";
        return true;
    }

    /** 0105: bind the in-memory search transaction to this exact user turn. */
    synchronized boolean markSearchQueryEntered(
            String query, String packageName, long generation) {
        if (!markSearchQueryEntered()) return false;
        searchTransactionQuery = normalizeSearchTransactionQuery(query);
        searchTransactionPackage = normalizeSearchPackage(packageName);
        searchTransactionGeneration = generation;
        return true;
    }

    /** Commit/IME dispatch is enough to make an identical same-turn SEARCH non-idempotent. */
    synchronized void markSearchSubmissionDispatched() {
        expireIfNeeded();
        if (!searchIntent || !searchQueryEntered) return;
        searchSubmissionDispatched = true;
    }

    synchronized boolean shouldSuppressDuplicateSearch(
            String query, String packageName, long generation) {
        expireIfNeeded();
        if (!searchIntent || !searchSubmissionDispatched) return false;
        if (generation < 0L || generation != searchTransactionGeneration) return false;

        String normalizedQuery = normalizeSearchTransactionQuery(query);
        String normalizedPackage = normalizeSearchPackage(packageName);
        return !normalizedQuery.isEmpty()
                && normalizedQuery.equals(searchTransactionQuery)
                && !normalizedPackage.isEmpty()
                && normalizedPackage.equals(searchTransactionPackage);
    }

    synchronized boolean markSearchCommitted() {
        expireIfNeeded();
        if (!searchIntent) return false;
        searchQueryEntered = true;
        searchSubmissionDispatched = true;
        searchCommitted = true;
        return isSearchOnlyLocked();
    }

    synchronized void markSearchResultSelectionDispatched(String label) {
        expireIfNeeded();
        if (!searchIntent) return;
        searchQueryEntered = true;
        searchCommitted = true;
        searchResultSelectionDispatched = true;
        searchResultSelected = false;
        dispatchedSearchResult = label == null ? "" : label.trim();
    }

    synchronized void markSearchResultSelected(String label) {
        expireIfNeeded();
        if (!searchIntent) return;
        searchQueryEntered = true;
        searchCommitted = true;
        searchResultSelectionDispatched = true;
        searchResultSelected = true;
        selectedSearchResult = label == null ? "" : label.trim();
        dispatchedSearchResult = "";
    }

    synchronized boolean shouldBlockAdditionalTextEntry() {
        expireIfNeeded();
        return isSearchOnlyLocked() && searchQueryEntered;
    }

    synchronized boolean shouldBlockTapForSearch(String metadata, boolean coordinateOnly) {
        expireIfNeeded();
        if (!isSearchOnlyLocked() || openSearchResultAuthorized) return false;

        String meta = normalize(metadata);

        // Before query entry Runtime may need to expose/focus the search UI.
        if (!searchQueryEntered) {
            if (isSearchControl(meta)) return false;
            return !coordinateOnly;
        }

        // Query text is not completion. Until Runtime commits the search, only
        // a semantic Search/Go/Enter control may be used as a fallback.
        if (!searchCommitted) {
            return !isSearchCommitControl(meta);
        }

        // Once search is committed, search-only reached its task boundary.
        return true;
    }

    private boolean isSearchOnlyLocked() {
        return searchIntent && !sendAuthorization.canAttempt() && !openSearchResultAuthorized;
    }

    private void expireIfNeeded() {
        long age = System.currentTimeMillis() - updatedAtMs;
        if (updatedAtMs == 0L || age < 0L || age > SCOPE_TTL_MS) {
            clearActionGrants();
            updatedAtMs = 0L;
        }
    }

    private void clearActionGrants() {
        sendAuthorization.clear();
        endCallAuthorized = false;
        appLearningAuthorized = false;
        elementReferenceAuthorized = false;
        searchIntent = false;
        openSearchResultAuthorized = false;
        searchResultSelectionRequested = false;
        searchContinuation = "";
        searchQueryEntered = false;
        searchCommitted = false;
        searchSubmissionDispatched = false;
        searchTransactionQuery = "";
        searchTransactionPackage = "";
        searchTransactionGeneration = -1L;
        searchResultSelected = false;
        searchResultSelectionDispatched = false;
        selectedSearchResult = "";
        dispatchedSearchResult = "";
    }

    private static boolean isCommandLevelNonExecuting(
            String rawText,
            String normalized) {
        String value = normalized == null ? "" : normalized;
        String folded = TextMatch.caseFold(rawText == null ? "" : rawText).trim();

        if (value.matches(
                "^(?:不要|別|别|不用|取消|停止|先不要|暫時不要|暂时不要)"
                        + ".*(?:打開|打开|開啟|开启|搜尋|搜索|導航|导航|送出|傳送|传送|發送|发送|傳給|传给|發給|发给|跟.+說|跟.+说|向.+說|向.+说|對.+說|对.+说|點擊|点击|按下|輸入|输入|打字).*")) {
            return true;
        }
        if (value.matches(
                "^(?:怎麼|怎么|如何|為什麼|为什么|如果|假如)"
                        + ".*(?:打開|打开|開啟|开启|搜尋|搜索|導航|导航|送出|傳送|传送|發送|发送|傳訊息|传讯息|點擊|点击|輸入|输入).*")) {
            return true;
        }
        return folded.matches(
                "^\\s*(?:don't|dont|do not|never|cancel|stop)\\b.*"
                        + "\\b(?:open|search|navigate|send|tell|click|tap|type|input)\\b.*")
                || folded.matches(
                "^\\s*(?:how|why|if)\\b.*"
                        + "\\b(?:open|search|navigate|send|click|tap|type|input)\\b.*");
    }

    private static boolean hasAppLearningIntent(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;
        String value = normalize(rawText);
        String folded = TextMatch.caseFold(rawText);
        boolean remember = containsAny(value,
                "記住", "记住", "記起來", "记起来", "學起來", "学起来",
                "學會", "学会", "記得這個", "记得这个",
                "教你一個規則", "教你一个规则")
                || folded.matches(".*\\b(remember|learn|teach)\\b.*");
        if (!remember) return false;
        return containsAny(value,
                "這個app", "这个app", "這個應用", "这个应用",
                "這個程式", "这个程序", "這個操作", "这个操作",
                "這個流程", "这个流程", "剛剛的操作", "刚刚的操作",
                "剛剛的流程", "刚刚的流程", "按鈕", "按钮",
                "頁面", "页面", "畫面", "画面", "搜尋", "搜索",
                "導航", "导航", "欄位", "栏位",
                "規則", "规则", "操作經驗", "操作经验", "app經驗", "app经验")
                || folded.matches(".*\\b(app|application|workflow|flow|operation|screen|button|search|navigation|rule|guidance|tip)\\b.*");
    }

    static boolean looksLikeSendTarget(String metadata) {
        return SendAuthorization.looksLikeSendTarget(metadata);
    }

    static boolean isStandaloneCurrentScreenSendCommand(String rawText) {
        return SendAuthorization.isStandaloneCurrentScreenSendCommand(rawText);
    }

    static String extractNamedRecipient(String rawText) {
        return SendAuthorization.extractNamedRecipient(rawText);
    }

    static boolean isSearchControl(String metadata) {
        String value = normalize(metadata);
        return containsAny(value,
                "search", "query", "filter", "搜尋", "搜索", "查找",
                "clear", "clearquery", "clear_query", "清除",
                "back", "返回", "cancel", "取消", "close", "關閉", "关闭");
    }

    static boolean isSearchCommitControl(String metadata) {
        String value = normalize(metadata);
        return containsAny(value,
                "search", "query", "搜尋", "搜索", "查找",
                "imeaction", "go", "enter", "前往", "確定", "确定", "完成");
    }

    private static boolean hasSearchIntent(String value) {
        return containsAny(value,
                "搜尋", "搜索", "查找", "找一下", "幫我找", "帮我找",
                "找人", "找名字", "找群組", "找群组",
                "search", "find", "lookup", "look up");
    }

    private static boolean hasExplicitOpenIntent(String value) {
        return containsAny(value,
                "打開", "打开", "開啟", "开启", "點開", "点开",
                "進入", "进入", "點進", "点进", "選擇", "选择",
                "選第", "选第", "點第", "点第",
                "open", "enter", "select", "click", "tap");
    }

    private static boolean hasNavigationIntent(String value) {
        return containsAny(value,
                "導航", "导航", "帶我去", "带我去", "前往", "路線到", "路线到",
                "navigate", "navigation", "directions", "route", "goto", "takeme");
    }

    private static boolean hasPostSearchOpenIntent(String value) {
        int searchAt = indexOfAny(value,
                "搜尋", "搜索", "查找", "找一下", "幫我找", "帮我找",
                "search", "find", "lookup");
        if (searchAt < 0) return false;
        String tail = value.substring(searchAt);
        return hasExplicitOpenIntent(tail);
    }

    private static int indexOfAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return -1;
        int best = -1;
        for (String needle : needles) {
            String n = normalize(needle);
            if (n.isEmpty()) continue;
            int at = value.indexOf(n);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            String n = normalize(needle);
            if (!n.isEmpty() && value.contains(n)) return true;
        }
        return false;
    }

    private static String normalizeSearchTransactionQuery(String text) {
        return TextMatch.caseFold(text == null ? "" : text)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeSearchPackage(String packageName) {
        return TextMatch.caseFold(packageName == null ? "" : packageName).trim();
    }

    private static String normalize(String text) {
        return TextMatch.caseFold(text == null ? "" : text)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
    }
}
