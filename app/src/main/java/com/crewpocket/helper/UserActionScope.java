package com.crewpocket.helper;


/**
 * 0027-hotfix2: deterministic latest-turn action boundary.
 * Stores capabilities only, never user plaintext.
 */
final class UserActionScope {
    private static final long SCOPE_TTL_MS = 120_000L;

    private boolean sendAuthorized;
    private boolean searchIntent;
    private boolean openSearchResultAuthorized;
    private boolean searchQueryEntered;
    private long updatedAtMs;

    synchronized void updateFromUserText(String text) {
        update(text);
    }

    synchronized void updateFromTrustedAction(String action) {
        update(action);
    }

    private void update(String text) {
        String value = normalize(text);
        boolean send = hasExplicitSendIntent(value);
        boolean search = hasSearchIntent(value);
        boolean open = hasExplicitOpenIntent(value);

        sendAuthorized = send;
        searchIntent = search;
        openSearchResultAuthorized = open || send;
        searchQueryEntered = false;
        updatedAtMs = System.currentTimeMillis();
    }

    synchronized boolean canSend() {
        expireIfNeeded();
        return sendAuthorized;
    }

    synchronized void consumeSendAuthorization() {
        sendAuthorized = false;
    }

    synchronized boolean markSearchQueryEntered() {
        expireIfNeeded();
        if (!isSearchOnlyLocked()) return false;
        searchQueryEntered = true;
        return true;
    }

    synchronized boolean shouldBlockAdditionalTextEntry() {
        expireIfNeeded();
        return isSearchOnlyLocked() && searchQueryEntered;
    }

    synchronized boolean shouldBlockTapForSearch(String metadata, boolean coordinateOnly) {
        expireIfNeeded();
        if (!isSearchOnlyLocked() || openSearchResultAuthorized) return false;

        String meta = normalize(metadata);
        if (isSearchControl(meta)) return false;

        // Before query entry, an unlabeled icon/coordinate tap may be needed
        // to expose search. Labeled non-search targets are still blocked.
        if (!searchQueryEntered) return !coordinateOnly;

        // After query entry, search-only has reached its task boundary.
        return true;
    }

    private boolean isSearchOnlyLocked() {
        return searchIntent && !sendAuthorized && !openSearchResultAuthorized;
    }

    private void expireIfNeeded() {
        long age = System.currentTimeMillis() - updatedAtMs;
        if (updatedAtMs == 0L || age < 0L || age > SCOPE_TTL_MS) {
            sendAuthorized = false;
            searchIntent = false;
            openSearchResultAuthorized = false;
            searchQueryEntered = false;
            updatedAtMs = 0L;
        }
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

    private static boolean hasExplicitSendIntent(String value) {
        return containsAny(value,
                "傳訊息", "传讯息", "傳消息", "传消息",
                "發訊息", "发讯息", "發消息", "发消息",
                "傳送訊息", "传送讯息", "傳送消息", "传送消息",
                "發送訊息", "发送讯息", "發送消息", "发送消息",
                "送出訊息", "送出讯息", "送出消息",
                "回覆", "回复", "回訊息", "回讯息", "回消息",
                "傳給", "传给", "發給", "发给",
                "跟他說", "跟她說", "跟他講", "跟她講",
                "告訴他", "告訴她", "告诉他", "告诉她",
                "留言給", "留言给",
                "send", "replyto", "reply to",
                "messagehim", "messageher", "message him", "message her",
                "texthim", "texther", "text him", "text her",
                "tellhim", "tellher", "tell him", "tell her");
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
                .replaceAll("[\\s，,。！？!「」『』\"'：:；;（）()]", "");
    }
}
