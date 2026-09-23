package com.crewpocket.helper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-purpose SEND permission state.
 *
 * This class answers only one question: did the latest user turn explicitly
 * request one message send attempt?
 *
 * It does NOT:
 * - locate or click the Send button (App Action Memory / Runtime HOW);
 * - decide whether the current screen is the right recipient (TARGET evidence);
 * - decide whether the action is sensitive (PolicyEngine / safety);
 * - verify whether the message actually left the composer (SendVerification).
 */
final class SendAuthorization {
    private boolean requested;
    private boolean consumed;
    private boolean commitDispatched;
    private boolean namedRecipientRequested;
    private String recipient = "";

    synchronized void updateFromUserText(String rawText) {
        if (isNonExecutingDiscussion(rawText)) {
            clear();
            return;
        }

        // Messaging is intentionally current-chat scoped. Recipient names in
        // speech may describe message content, but never gate or reroute SEND.
        namedRecipientRequested = false;
        recipient = "";
        requested = isExplicitSendRequest(rawText);
        consumed = false;
        commitDispatched = false;
    }

    synchronized void clear() {
        requested = false;
        consumed = false;
        commitDispatched = false;
        namedRecipientRequested = false;
        recipient = "";
    }

    synchronized boolean canAttempt() {
        return requested && !consumed;
    }

    synchronized void consume() {
        consumed = true;
    }

    synchronized void markCommitDispatched() {
        commitDispatched = true;
        consumed = true;
    }

    synchronized boolean isCommitDispatched() {
        return commitDispatched;
    }

    synchronized boolean requiresRecipientVerification() {
        return false;
    }

    synchronized boolean hasAmbiguousNamedRecipient() {
        return false;
    }

    synchronized String recipient() {
        return recipient == null ? "" : recipient;
    }

    private static boolean isNonExecutingDiscussion(String rawText) {
        String folded = TextMatch.caseFold(rawText == null ? "" : rawText).trim();
        String value = normalize(rawText);
        if (value.isEmpty()) return true;

        // Only treat command-level negation/questions as non-executing.
        // Message bodies may legitimately contain words such as "不要" or "如果".
        if (value.matches(
                "^(?:不要|別|别|不用|取消|停止|先不要|暫時不要|暂时不要)"
                        + ".*(?:送出|傳送|传送|發送|发送|傳給|传给|發給|发给|告訴|告诉|跟.+說|跟.+说|向.+說|向.+说|對.+說|对.+说).*")) {
            return true;
        }
        if (value.matches(
                "^(?:怎麼|怎么|如何|為什麼|为什么|如果|假如|能不能)"
                        + ".*(?:送出|傳送|传送|發送|发送|傳訊息|传讯息|發訊息|发讯息|send).*")) {
            return true;
        }
        return folded.matches(
                "^\\s*(?:don't|dont|do not|never|cancel|stop)\\s+.*\\b(?:send|message|text|tell)\\b.*")
                || folded.matches(
                "^\\s*(?:how|why|if|should)\\b.*\\b(?:send|message|text)\\b.*");
    }

    static boolean looksLikeSendTarget(String metadata) {
        String value = normalize(metadata);
        return containsAny(value,
                "send", "composer_send", "messagesend", "message_send", "action_send",
                "send_btn", "send_button", "paper_plane", "paperplane",
                "傳送", "传送", "發送", "发送", "送出");
    }

    /**
     * Deterministic fast path for a send-only follow-up.
     *
     * Accepts control wording such as "幫我送出" while rejecting new text,
     * recipient routing, explanations, negation and hypothetical questions.
     */
    static boolean isStandaloneCurrentScreenSendCommand(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;

        String folded = TextMatch.caseFold(rawText).trim();
        String value = normalize(rawText);
        if (value.isEmpty()) return false;

        if (containsAny(value,
                "不要", "別", "别", "不用", "取消", "停止",
                "不是", "不能", "不可以", "先不要", "暫時不要", "暂时不要",
                "怎麼", "怎么", "如何", "為什麼", "为什么", "如果", "假如", "能不能")) {
            return false;
        }
        if (folded.matches(".*\\b(don't|dont|do not|never|cancel|stop|how|why|if|should)\\b.*")) {
            return false;
        }

        if (isNamedRecipientMessagingRequest(rawText)) {
            return false;
        }

        if (containsAny(value,
                "輸入", "输入", "打字", "寫", "写",
                "貼上", "贴上", "填入", "填上", "加上")) {
            return false;
        }
        if (folded.matches(".*\\b(type|input|enter|write|paste|append)\\b.*")) {
            return false;
        }

        boolean hasSendVerb = containsAny(value,
                "送出去", "發出去", "发出去", "傳出去", "传出去",
                "送出", "發送", "发送", "傳送", "传送")
                || folded.matches(".*\\bsend\\b.*");
        if (!hasSendVerb) return false;

        String residual = value;
        String[] controlTokens = new String[]{
                "麻煩你", "麻烦你", "幫我", "帮我", "幫忙", "帮忙",
                "請", "请", "給我", "给我", "替我", "你",
                "好啦", "好的", "好啊", "好", "那就", "然後", "然后",
                "那", "就", "再", "直接", "現在", "现在", "可以",

                "目前", "當前", "当前", "這則", "这则",
                "訊息", "讯息", "消息", "這個", "这个", "把", "它",

                "按鈕", "按钮",
                "按一下", "點一下", "点一下",
                "按下", "點下", "点下",
                "點擊", "点击", "按", "點", "点",
                "鍵", "键", "一下",

                "送出去", "發出去", "发出去", "傳出去", "传出去",
                "送出", "發送", "发送", "傳送", "传送",

                "吧", "了", "喔", "哦", "啦", "呢", "嘛", "啊", "呀",
                "嗎", "吗", "謝謝", "谢谢",

                "goaheadand", "goahead", "couldyou", "wouldyou", "canyou",
                "please", "helpme", "okay", "then", "just", "now",
                "click", "tap", "press", "hit",
                "current", "message", "button", "this", "it",
                "send", "the", "for", "me", "and", "ok"
        };

        for (String token : controlTokens) {
            String normalizedToken = normalize(token);
            if (!normalizedToken.isEmpty()) {
                residual = residual.replace(normalizedToken, "");
            }
        }
        return residual.isEmpty();
    }

    static boolean isExplicitTypeOnlyRequest(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;

        String folded = TextMatch.caseFold(rawText).trim();
        String value = normalize(rawText);
        if (!hasTypeVerb(rawText)) return false;

        // Questions/hypotheticals about typing are not execution requests.
        if (value.matches("^(?:怎麼|怎么|如何|為什麼|为什么|如果|假如|能不能).*")
                || folded.matches("^\\s*(?:how|why|if|should)\\b.*")) {
            return false;
        }

        // Negating the typing itself is not executable. Negating SEND is fine:
        // "打字 X，不要送出" remains a type-only request.
        if (containsAny(value,
                "不要輸入", "不要输入", "別輸入", "别输入",
                "不要打字", "別打字", "别打字",
                "不要寫", "不要写", "別寫", "别写",
                "不要貼上", "不要贴上", "不要填入")
                || folded.matches(
                        ".*\\b(don't|dont|do not|never)\\s+(type|input|enter|write|paste|fill)\\b.*")) {
            return false;
        }

        if (isNamedRecipientMessagingRequest(rawText)
                || hasTypeThenSendIntent(rawText)) {
            return false;
        }

        // Keep pure TYPE completion narrow. Compound follow-up actions remain
        // model-driven, but the TYPE itself is still allowed by Runtime.
        if (containsAny(value,
                "然後", "然后", "接著", "接着",
                "再按", "再點", "再点", "點擊", "点击", "按下",
                "打開", "打开", "開啟", "开启",
                "搜尋", "搜索", "查找", "返回", "提交")) {
            return false;
        }
        if (folded.matches(
                ".*\\b(and then|then (?:click|tap|press|open|search)|submit)\\b.*")) {
            return false;
        }

        return true;
    }

    static boolean isExplicitSendRequest(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;
        if (isNonExecutingDiscussion(rawText)) return false;
        if (isNamedRecipientMessagingRequest(rawText)) {
            return true;
        }
        if (hasTypeVerb(rawText)) {
            return hasTypeThenSendIntent(rawText);
        }
        return isStandaloneCurrentScreenSendCommand(rawText)
                || hasDirectSendWithPayloadIntent(rawText);
    }

    private static boolean hasDirectSendWithPayloadIntent(String rawText) {
        String folded = TextMatch.caseFold(rawText == null ? "" : rawText).trim();
        String value = normalize(rawText);

        boolean chinese = value.matches(
                "^(?:麻煩你|麻烦你|幫我|帮我|請|请|替我|直接|現在|现在)*"
                        + "(?:送出|發送|发送|傳送|传送).+");
        boolean english = folded.matches(
                "^\\s*(?:(?:please|just|now)\\s+)*send\\s+.+");
        return chinese || english;
    }

    private static boolean hasTypeVerb(String rawText) {
        String value = normalize(rawText);
        String folded = TextMatch.caseFold(rawText == null ? "" : rawText);
        return containsAny(value,
                "輸入", "输入", "打字", "寫入", "写入", "寫", "写",
                "貼上", "贴上", "填入", "填上")
                || folded.matches(".*\\b(type|input|enter|write|paste|fill)\\b.*");
    }

    private static boolean hasTypeThenSendIntent(String rawText) {
        String value = normalize(rawText);
        String folded = TextMatch.caseFold(rawText == null ? "" : rawText).trim();

        boolean chinese = value.matches(
                ".*(?:輸入|输入|打字|寫入|写入|寫|写|貼上|贴上|填入|填上).+"
                        + "(?:並|并|然後|然后|接著|接着|再)"
                        + ".*(?:送出|傳送|传送|發送|发送|送出去|傳出去|传出去|發出去|发出去).*");
        boolean english = folded.matches(
                ".*\\b(?:type|input|enter|write|paste|fill)\\b.+"
                        + "\\b(?:and\\s+)?(?:then\\s+)?send\\b.*");
        return chinese || english;
    }

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
        boolean englishRecipient = raw.matches(
                ".*\\b(?:send(?:\\s+(?:a\\s+)?(?:message|text))?\\s+to|tell)\\s+[^,.!?;:]{1,40}.*");

        return recipientVerb || conversationalTell || englishRecipient;
    }

    static String extractNamedRecipient(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return "";
        String raw = TextMatch.caseFold(rawText).trim();

        String recipient = firstGroup(raw,
                "(?:跟|向|對|对)\\s*([^，,。！？!：:\\n]{1,40}?)\\s*(?:說|说)");
        if (!recipient.isEmpty()) return cleanRecipient(recipient);

        recipient = firstGroup(raw,
                "(?:傳訊息給|传讯息给|發訊息給|发讯息给|傳消息給|传消息给|發消息給|发消息给|傳給|传给|發給|发给)\\s*"
                        + "([^，,。！？!：:\\n]{1,40}?)(?=\\s*(?:說|说|：|:|，|,|「|『|\\\"))");
        if (!recipient.isEmpty()) return cleanRecipient(recipient);

        recipient = firstGroup(raw,
                "(?:告訴|告诉)\\s*([^，,。！？!：:\\n]{1,40}?)(?=\\s*(?:說|说|：|:|，|,|「|『|\\\"))");
        if (!recipient.isEmpty()) return cleanRecipient(recipient);

        recipient = firstGroup(raw,
                "\\b(?:send(?:\\s+(?:a\\s+)?(?:message|text))?\\s+to|tell)\\s+"
                        + "([^,.!?;:]{1,40}?)(?=\\s+(?:that|saying|say)\\b|[,.:!?])");
        return cleanRecipient(recipient);
    }

    private static String firstGroup(String raw, String regex) {
        try {
            Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(raw);
            return matcher.find() ? matcher.group(1) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cleanRecipient(String value) {
        if (value == null) return "";
        String out = value.replaceAll("\\s+", " ").trim();
        if (out.length() > 40) out = out.substring(0, 40).trim();
        return out;
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
