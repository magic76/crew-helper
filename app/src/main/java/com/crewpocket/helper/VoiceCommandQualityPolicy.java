package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure finalized-voice quality and critical-entity heuristics. */
final class VoiceCommandQualityPolicy {
    private static final Pattern NUMBER_OR_TIME = Pattern.compile(
            "(?<!\\p{L})(?:\\d{1,2}[:：]\\d{2}|\\d+(?:[.,]\\d+)?)(?!\\p{L})"
                    + "|[零〇一二兩两三四五六七八九十]{1,3}[點点時时](?:半|[零〇一二兩两三四五六七八九十]{1,3}分?)?");

    private VoiceCommandQualityPolicy() {}

    static boolean looksIncomplete(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) return true;

        // Valid short confirmations / choices must remain usable.
        if (isAffirmative(text)
                || isNegative(text)
                || text.matches("[1-9]")
                || text.matches("第?[一二三四五六七八九1-9]個?")
                || containsAny(text, "送出", "發送", "发送", "停止", "取消", "返回", "back", "send")) {
            return false;
        }

        if (text.length() == 1) return true;

        String folded = TextMatch.caseFold(text).trim();
        return endsWithAny(
                folded,
                "然後", "然后", "接著", "接着",
                "幫我", "帮我", "我要", "我想",
                "傳給", "传给", "發給", "发给",
                "跟", "給", "给",
                "send to", "tell", "message", "at", "to");
    }

    static boolean isAffirmative(String raw) {
        String value = compact(raw);
        return value.matches("^(對|对|是|好|好的|沒錯|没错|正確|正确|可以|確認|确认|嗯|嗯嗯|yes|yeah|yep|correct|confirm|ok|okay)$");
    }

    static boolean isNegative(String raw) {
        String value = compact(raw);
        return value.matches("^(不|不是|不要|錯|错|不對|不对|取消|改一下|no|nope|wrong|cancel)$");
    }

    static List<String> criticalEntities(String finalizedText) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String text = finalizedText == null ? "" : finalizedText.trim();

        String recipient = UserActionScope.extractNamedRecipient(text);
        if (recipient != null && !recipient.trim().isEmpty()) {
            out.add("對象「" + recipient.trim() + "」");
        }

        Matcher matcher = NUMBER_OR_TIME.matcher(text);
        while (matcher.find() && out.size() < 4) {
            String token = matcher.group();
            if (token == null || token.trim().isEmpty()) continue;
            out.add("數字/時間「" + token.trim() + "」");
        }

        return new ArrayList<String>(out);
    }

    static String summary(List<String> entities) {
        if (entities == null || entities.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String entity : entities) {
            if (entity == null || entity.trim().isEmpty()) continue;
            if (out.length() > 0) out.append("、");
            out.append(entity.trim());
        }
        return out.toString();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String compact(String raw) {
        return TextMatch.caseFold(raw == null ? "" : raw)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "")
                .trim();
    }

    private static boolean containsAny(String value, String... needles) {
        String compact = compact(value);
        for (String needle : needles) {
            if (compact.contains(compact(needle))) return true;
        }
        return false;
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
