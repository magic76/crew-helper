package com.crewpocket.helper;

/**
 * Deterministic latest-turn action boundary.
 *
 * 0042 current-screen messaging:
 * - Runtime never resolves, remembers, searches for, or verifies a recipient;
 * - message sending applies only to the composer already visible on screen;
 * - TYPE never implies Send;
 * - an explicit current-turn send verb grants exactly one send attempt;
 * - named-recipient messaging is intentionally unsupported for this mode.
 */
final class UserActionScope {
    private static final long SCOPE_TTL_MS = 120_000L;

    private boolean sendAuthorized;
    private boolean messageTransactionHandled;
    private boolean namedRecipientMessagingUnsupported;

    private boolean searchIntent;
    private boolean openSearchResultAuthorized;
    private boolean searchResultSelectionRequested;
    private String searchContinuation = "";
    private boolean searchQueryEntered;
    private boolean searchCommitted;
    private boolean searchResultSelected;
    private boolean searchResultSelectionDispatched;
    private String selectedSearchResult = "";
    private String dispatchedSearchResult = "";
    private long updatedAtMs;
    private boolean endCallAuthorized;

    synchronized boolean consumeEndCallAuthorization() {
        expireIfNeeded();
        boolean authorized = endCallAuthorized;
        endCallAuthorized = false;
        return authorized;
    }

    synchronized void updateFromUserText(String text) {
        update(text);
    }

    synchronized void updateFromTrustedAction(String action) {
        update(action);
    }

    private void update(String text) {
        String value = normalize(text);

        // Fail closed on negated / hypothetical / explanatory discussions.
        if (containsAny(value, "不要", "別", "不用", "取消", "停止", "怎麼", "如何", "如果", "假如",
                "don't", "dont", "do not", "never", "cancel", "stop", "how to", "if ")) {
            clearActionGrants();
            updatedAtMs = System.currentTimeMillis();
            return;
        }

        endCallAuthorized = value.matches("(?:請|幫我|请|帮我)?(?:結束通話|结束通话|掛斷電話|挂断电话|退出語音助理|退出语音助理)(?:吧|謝謝|谢谢)?")
                || value.matches("(?:please)?(?:endthecall|hangup|exitthevoiceassistant)(?:please)?");

        boolean navigation = hasNavigationIntent(value);
        boolean search = hasSearchIntent(value) || navigation;
        boolean resultSelection = search;
        boolean openResult = navigation || hasPostSearchOpenIntent(value);

        namedRecipientMessagingUnsupported = isNamedRecipientMessagingRequest(text);
        sendAuthorized = !namedRecipientMessagingUnsupported
                && hasCurrentScreenSendIntent(text);
        messageTransactionHandled = false;

        searchIntent = search;
        searchResultSelectionRequested = resultSelection;
        searchContinuation = navigation
                ? "NAVIGATE"
                : (openResult ? "OPEN_RESULT" : "RESULT_DETAILS");
        openSearchResultAuthorized = openResult;
        searchQueryEntered = false;
        searchCommitted = false;
        searchResultSelected = false;
        searchResultSelectionDispatched = false;
        selectedSearchResult = "";
        dispatchedSearchResult = "";
        updatedAtMs = System.currentTimeMillis();
    }

    synchronized boolean canSend() {
        expireIfNeeded();
        return sendAuthorized;
    }

    synchronized void consumeSendAuthorization() {
        sendAuthorized = false;
    }

    /**
     * Compatibility stubs for old callers outside the active Live path.
     * Recipient routing is intentionally disabled.
     */
    synchronized boolean requiresRecipientVerification() {
        return false;
    }

    synchronized String authorizedRecipient() {
        return "";
    }

    /** A single explicit-send turn owns one atomic transaction, never a tap loop. */
    synchronized void markMessageTransactionHandled() {
        messageTransactionHandled = true;
    }

    synchronized boolean shouldBlockFurtherMessageMutation() {
        expireIfNeeded();
        return messageTransactionHandled;
    }

    synchronized boolean blocksNamedRecipientMessagingAction() {
        expireIfNeeded();
        return namedRecipientMessagingUnsupported;
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
        searchResultSelectionDispatched = false;
        searchResultSelected = false;
        dispatchedSearchResult = "";
        selectedSearchResult = "";
        return true;
    }

    synchronized boolean markSearchCommitted() {
        expireIfNeeded();
        if (!searchIntent) return false;
        searchQueryEntered = true;
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
        return searchIntent && !sendAuthorized && !openSearchResultAuthorized;
    }

    private void expireIfNeeded() {
        long age = System.currentTimeMillis() - updatedAtMs;
        if (updatedAtMs == 0L || age < 0L || age > SCOPE_TTL_MS) {
            clearActionGrants();
            updatedAtMs = 0L;
        }
    }

    private void clearActionGrants() {
        sendAuthorized = false;
        messageTransactionHandled = false;
        namedRecipientMessagingUnsupported = false;
        endCallAuthorized = false;
        searchIntent = false;
        openSearchResultAuthorized = false;
        searchResultSelectionRequested = false;
        searchContinuation = "";
        searchQueryEntered = false;
        searchCommitted = false;
        searchResultSelected = false;
        searchResultSelectionDispatched = false;
        selectedSearchResult = "";
        dispatchedSearchResult = "";
    }

    static boolean looksLikeSendTarget(String metadata) {
        String value = normalize(metadata);
        return containsAny(value,
                "send", "composer_send", "messagesend", "message_send", "action_send",
                "send_btn", "send_button", "paper_plane", "paperplane",
                "傳送", "传送", "發送", "发送", "送出");
    }

    /**
     * 0052 deterministic fast path for a send-only follow-up.
     * Keep this intentionally narrow: these phrases contain no new message text,
     * so Runtime can safely submit only the currently visible composer.
     */
    static boolean isStandaloneCurrentScreenSendCommand(String rawText) {
        String value = normalize(rawText == null ? "" : rawText);
        if (value.isEmpty()) return false;
        return value.equals("送出")
                || value.equals("送出吧")
                || value.equals("送出去")
                || value.equals("幫我送出")
                || value.equals("帮我送出")
                || value.equals("幫我送出去")
                || value.equals("帮我送出去")
                || value.equals("直接送出")
                || value.equals("請送出")
                || value.equals("请送出")
                || value.equals("然後送出")
                || value.equals("然后送出")
                || value.equals("那就送出")
                || value.equals("就送出")
                || value.equals("好送出")
                || value.equals("現在送出")
                || value.equals("现在送出")
                || value.equals("發送")
                || value.equals("发送")
                || value.equals("發送吧")
                || value.equals("发送吧")
                || value.equals("幫我發送")
                || value.equals("帮我发送")
                || value.equals("直接發送")
                || value.equals("直接发送")
                || value.equals("請發送")
                || value.equals("请发送")
                || value.equals("然後發送")
                || value.equals("然后发送")
                || value.equals("那就發送")
                || value.equals("那就发送")
                || value.equals("就發送")
                || value.equals("就发送")
                || value.equals("傳送")
                || value.equals("传送")
                || value.equals("傳送吧")
                || value.equals("传送吧")
                || value.equals("幫我傳送")
                || value.equals("帮我传送")
                || value.equals("直接傳送")
                || value.equals("直接传送")
                || value.equals("send")
                || value.equals("sendit")
                || value.equals("sendnow")
                || value.equals("sendthis")
                || value.equals("sendmessage")
                || value.equals("sendcurrentmessage")
                || value.equals("pleasesend")
                || value.equals("sendplease");
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

    /**
     * Current-screen-only send intent.
     *
     * Examples that authorize:
     * - 幫我輸入「晚點到」並送出
     * - 輸入測試123然後發送
     * - send this message
     *
     * Named-recipient commands are rejected before reaching this check.
     */
    private static boolean hasCurrentScreenSendIntent(String rawText) {
        String value = normalize(rawText == null ? "" : rawText);
        return containsAny(value,
                "送出", "傳送", "传送", "發送", "发送",
                "sendthismessage", "sendcurrentmessage",
                "sendmessage", "sendtext", "send");
    }

    /**
     * This mode deliberately does not route messages to people.
     * The user must manually open the intended chat first.
     */
    private static boolean isNamedRecipientMessagingRequest(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;
        String raw = TextMatch.caseFold(rawText).trim();
        String value = normalize(raw);

        boolean recipientVerb = containsAny(value,
                "傳給", "传给", "發給", "发给",
                "傳訊息給", "传讯息给", "發訊息給", "发讯息给",
                "傳消息給", "传消息给", "發消息給", "发消息给",
                "sendto", "sendmessageto", "sendtextto",
                "告訴", "告诉");

        boolean conversationalTell = raw.matches(
                ".*(?:跟|向|對|对)\\s*[^，,。！？!：:]+\\s*(?:說|说).*");

        return recipientVerb || conversationalTell;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            String n = normalize(needle);
            if (!n.isEmpty() && value.contains(n)) return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return TextMatch.caseFold(text == null ? "" : text)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
    }
}
