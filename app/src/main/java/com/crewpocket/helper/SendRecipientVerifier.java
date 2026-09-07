package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fail-closed recipient verification before a real message send.
 *
 * This deliberately prefers false negatives over sending to a wrong chat.
 * It accepts a named recipient only when the current semantic screen exposes
 * that name as an exact/near-exact label in a likely top/header location.
 */
final class SendRecipientVerifier {
    static final class Result {
        final boolean verified;
        final String reason;
        Result(boolean verified, String reason) {
            this.verified = verified;
            this.reason = reason == null ? "" : reason;
        }
    }

    private SendRecipientVerifier() {}

    static Result verify(JSONObject screen, String recipient) {
        String target = normalize(recipient);
        if (target.isEmpty()) return new Result(false, "recipient missing");
        if (screen == null || !screen.optBoolean("success", false)) {
            return new Result(false, "semantic screen unavailable");
        }

        JSONArray elements = screen.optJSONArray("elements");
        if (elements == null || elements.length() == 0) {
            return new Result(false, "no semantic elements");
        }

        int screenHeight = screen.optInt("screenHeight", 0);
        int limit = Math.min(elements.length(), 40);

        for (int i = 0; i < limit; i++) {
            JSONObject e = elements.optJSONObject(i);
            if (e == null) continue;

            String label = e.optString("label", "");
            String normalizedLabel = normalize(label);
            if (normalizedLabel.isEmpty()) continue;

            boolean exact = normalizedLabel.equals(target);
            boolean near = !exact
                    && normalizedLabel.startsWith(target)
                    && normalizedLabel.length() - target.length() <= 12;

            if (!exact && !near) continue;

            String meta = TextMatch.caseFold(
                    e.optString("viewId", "") + " "
                    + e.optString("semanticHint", "") + " "
                    + e.optString("role", ""));

            boolean headerHint = containsAny(meta,
                    "title", "toolbar", "header", "conversation", "chat",
                    "recipient", "contact", "profile", "name",
                    "標題", "标题", "聊天", "聯絡人", "联系人");

            JSONObject bounds = e.optJSONObject("bounds");
            int top = readTop(bounds);
            boolean topArea = screenHeight > 0 && top >= 0 && top <= Math.round(screenHeight * 0.34f);
            boolean earlyElement = i < 10;

            // Near matches are only trusted with stronger header evidence.
            if (exact && (topArea || headerHint || earlyElement)) {
                return new Result(true, "recipient visible in header region");
            }
            if (near && topArea && headerHint) {
                return new Result(true, "recipient near-match in header");
            }
        }

        return new Result(false, "recipient not verified in chat header");
    }

    private static int readTop(JSONObject bounds) {
        if (bounds == null) return -1;
        if (bounds.has("top")) return bounds.optInt("top", -1);
        if (bounds.has("y")) return bounds.optInt("y", -1);
        if (bounds.has("t")) return bounds.optInt("t", -1);
        return -1;
    }

    private static boolean containsAny(String value, String... needles) {
        String source = TextMatch.caseFold(value);
        for (String needle : needles) {
            if (source.contains(TextMatch.caseFold(needle))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return TextMatch.caseFold(value)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()\\[\\]【】]", "");
    }
}
