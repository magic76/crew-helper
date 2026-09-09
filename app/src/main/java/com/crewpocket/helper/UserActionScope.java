package com.crewpocket.helper;

/**
 * 0028: deterministic latest-turn action boundary.
 *
 * Send authorization is deliberately narrow:
 * - search/open/type never imply sending;
 * - vague phrases such as "跟他說", "告訴他", "傳給..." do not grant send;
 * - a named-recipient send stores only the current-turn recipient token in RAM;
 * - the grant is consumed after one send/uncertain-send attempt.
 */
final class UserActionScope {
    private static final long SCOPE_TTL_MS = 120_000L;

    private boolean sendAuthorized;
    private boolean sendRecipientRequired;
    private String sendRecipient = "";
    private boolean searchIntent;
    private boolean openSearchResultAuthorized;
    private boolean searchResultSelectionRequested;
    private String searchContinuation = "";
    private boolean searchQueryEntered;
    private boolean searchCommitted;
    private boolean searchResultSelected;
    private String selectedSearchResult = "";
    private long updatedAtMs;
    private boolean endCallAuthorized;

    private static final class SendGrant {
        boolean authorized;
        boolean recipientRequired;
        String recipient = "";
    }

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

        SendGrant grant = parseSendGrant(text);
        boolean navigation = hasNavigationIntent(value);
        boolean search = hasSearchIntent(value) || navigation;
        // A Maps result picker is safe only after Runtime has proved that real
        // result rows exist (not autocomplete suggestions). Let it be offered
        // for an ordinary search as well: the user still has to explicitly
        // choose a row before anything is opened. Navigation/open wording only
        // authorizes automatic continuation after that choice.
        boolean resultSelection = search;
        boolean openResult = navigation || hasPostSearchOpenIntent(value);

        sendAuthorized = grant.authorized;
        sendRecipientRequired = grant.recipientRequired;
        sendRecipient = grant.recipient;
        searchIntent = search;
        // App launch ("open Maps, search X") must not silently authorize
        // opening a search result. Only a post-search open/select or navigation
        // continuation grants result selection.
        searchResultSelectionRequested = resultSelection;
        searchContinuation = navigation
                ? "NAVIGATE"
                : (openResult ? "OPEN_RESULT" : "RESULT_DETAILS");
        openSearchResultAuthorized = openResult || grant.authorized;
        searchQueryEntered = false;
        searchCommitted = false;
        searchResultSelected = false;
        selectedSearchResult = "";
        updatedAtMs = System.currentTimeMillis();
    }

    synchronized boolean canSend() {
        expireIfNeeded();
        return sendAuthorized;
    }

    synchronized boolean requiresRecipientVerification() {
        expireIfNeeded();
        return sendAuthorized && sendRecipientRequired;
    }

    synchronized String authorizedRecipient() {
        expireIfNeeded();
        return sendRecipient == null ? "" : sendRecipient;
    }

    synchronized void consumeSendAuthorization() {
        sendAuthorized = false;
        sendRecipientRequired = false;
        sendRecipient = "";
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
        return true;
    }

    synchronized boolean markSearchCommitted() {
        expireIfNeeded();
        if (!searchIntent) return false;
        searchQueryEntered = true;
        searchCommitted = true;
        return isSearchOnlyLocked();
    }

    synchronized void markSearchResultSelected(String label) {
        expireIfNeeded();
        if (!searchIntent) return;
        searchQueryEntered = true;
        searchCommitted = true;
        searchResultSelected = true;
        selectedSearchResult = label == null ? "" : label.trim();
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
        // Block even another Search-key tap so weak models cannot double-submit.
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
        sendRecipientRequired = false;
        sendRecipient = "";
        endCallAuthorized = false;
        searchIntent = false;
        openSearchResultAuthorized = false;
        searchResultSelectionRequested = false;
        searchContinuation = "";
        searchQueryEntered = false;
        searchCommitted = false;
        searchResultSelected = false;
        selectedSearchResult = "";
    }

    static boolean looksLikeSendTarget(String metadata) {
        String value = normalize(metadata);
        return containsAny(value,
                "composer_send", "messagesend", "message_send", "action_send",
                "send_btn", "send_button", "paper_plane", "paperplane",
                "傳送", "传送", "發送", "发送", "送出");
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

    /**
     * Result-opening permission must appear after the search phrase. This keeps
     * "open Google Maps, search Grand Palace" search-only, while allowing
     * "search Grand Palace then open/select it".
     */
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
     * Explicit send means the utterance itself names the communication action.
     * "跟他說 / 告訴他 / 傳給小明" remain conversationally ambiguous and do
     * NOT authorize a real send.
     */
    private static SendGrant parseSendGrant(String text) {
        SendGrant out = new SendGrant();
        String raw = TextMatch.caseFold(text == null ? "" : text).trim();
        String value = normalize(raw);

        // Explicit submit of the CURRENT composer. These phrases authorize
        // pressing Send for the already-prepared message without inventing a
        // recipient. "幫我送出訊息" is a real send instruction, not a vague
        // conversational phrase such as "跟他說".
        boolean currentComposerVerb = containsAny(value,
                "送出這則訊息", "送出这则讯息", "送出這則消息", "送出这则消息",
                "傳送這則訊息", "传送这则讯息", "發送這則訊息", "发送这则讯息",
                "送出目前訊息", "送出当前讯息",
                "送出訊息", "送出讯息", "送出消息",
                "把訊息送出", "把讯息送出", "把消息送出",
                "sendthismessage", "sendcurrentmessage");

        // If the same utterance names a recipient, do NOT use the current-
        // composer shortcut: preserve recipient verification.
        boolean recipientMentioned = hasExplicitRecipientMarker(raw);
        boolean currentComposer = currentComposerVerb && !recipientMentioned;

        boolean explicitMessageVerb = currentComposerVerb || containsAny(value,
                "傳訊息", "传讯息", "傳消息", "传消息",
                "發訊息", "发讯息", "發消息", "发消息",
                "傳送訊息", "传送讯息", "傳送消息", "传送消息",
                "發送訊息", "发送讯息", "發送消息", "发送消息",
                "回覆", "回复",
                "sendmessage", "sendtext", "sendamsg", "replyto");

        if (!currentComposer && !explicitMessageVerb) return out;

        out.authorized = true;
        if (currentComposer) {
            out.recipientRequired = false;
            return out;
        }

        out.recipientRequired = true;
        out.recipient = extractRecipient(raw);
        return out;
    }

    private static boolean hasExplicitRecipientMarker(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String folded = TextMatch.caseFold(raw);

        int giveAt = firstIndex(raw, "給", "给");
        if (giveAt >= 0 && giveAt + 1 < raw.length()) {
            String afterGive = raw.substring(giveAt + 1).trim();
            // "給我把訊息送出" means "send it for me", not recipient=我.
            if (!afterGive.startsWith("我")) return true;
        }
        return folded.contains(" to ");
    }

    private static String extractRecipient(String raw) {
        if (raw == null) return "";
        String text = raw.trim();

        // Chinese: 傳訊息給小明說... / 發訊息給小明：...
        int giveAt = firstIndex(text, "給", "给");
        if (giveAt >= 0) {
            String candidate = text.substring(giveAt + 1);
            candidate = cutAt(candidate, "說", "说", "：", ":", "，", ",", "。", "！", "!", "內容", "内容");
            return cleanRecipient(candidate);
        }

        // Chinese: 回覆小明：...
        int replyAt = firstIndex(text, "回覆", "回复");
        if (replyAt >= 0) {
            String candidate = text.substring(replyAt + 2);
            candidate = candidate.replaceFirst("^(一下|訊息|讯息|消息|給|给)+", "");
            candidate = cutAt(candidate, "說", "说", "：", ":", "，", ",", "。", "！", "!");
            return cleanRecipient(candidate);
        }

        // English: send a message to John saying ...
        String lower = TextMatch.caseFold(text);
        int toAt = lower.indexOf(" to ");
        if (toAt >= 0) {
            String candidate = text.substring(toAt + 4);
            candidate = cutAtIgnoreCase(candidate, " saying ", " with ", ":", ",", ".", "!");
            return cleanRecipient(candidate);
        }

        return "";
    }

    private static String cleanRecipient(String value) {
        String out = value == null ? "" : value.trim();
        out = out.replaceAll("^[給给對对向\\s]+|[\\s，,。！？!：:]+$", "");
        String normalized = normalize(out);
        if (normalized.isEmpty() || normalized.length() > 48) return "";
        if (containsAny(normalized,
                "他", "她", "它", "他們", "他们", "她們", "她们", "對方", "对方",
                "某人", "那個人", "那个人", "him", "her", "them", "someone")) return "";
        return out;
    }

    private static int firstIndex(String text, String... tokens) {
        int result = -1;
        if (text == null) return -1;
        for (String token : tokens) {
            int at = text.indexOf(token);
            if (at >= 0 && (result < 0 || at < result)) result = at;
        }
        return result;
    }

    private static String cutAt(String text, String... tokens) {
        int at = firstIndex(text, tokens);
        return at < 0 ? text : text.substring(0, at);
    }

    private static String cutAtIgnoreCase(String text, String... tokens) {
        String lower = TextMatch.caseFold(text == null ? "" : text);
        int best = -1;
        for (String token : tokens) {
            int at = lower.indexOf(TextMatch.caseFold(token));
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best < 0 ? text : text.substring(0, best);
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
        return TextMatch.caseFold(text)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
    }
}
