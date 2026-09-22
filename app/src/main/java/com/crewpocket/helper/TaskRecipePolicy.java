package com.crewpocket.helper;

import java.util.Locale;

/** Conservative policy for reusable deterministic phone task recipes. */
final class TaskRecipePolicy {
    static final int MAX_STEPS = 8;
    static final int MAX_SEARCH_CHARS = 120;
    static final String SPECIAL_CONVERSATION_LOOP =
            "CONVERSATION_LOOP";

    private TaskRecipePolicy() {}

    static boolean isAllowedTool(String name) {
        return "launch_app".equals(name)
                || "tap_screen".equals(name)
                || "search_current_app".equals(name)
                || "commit_search".equals(name)
                || "swipe_screen".equals(name)
                || "press_key".equals(name);
    }

    static boolean isSpecialConversationLoopRecipe(String type) {
        return SPECIAL_CONVERSATION_LOOP.equals(
                type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
    }

    static boolean isIgnorableObservation(String name) {
        return "inspect_ui".equals(name)
                || "wait".equals(name)
                || "take_screenshot".equals(name);
    }

    static boolean needsScreenGuard(String name) {
        return !"launch_app".equals(name);
    }

    static boolean goalsMatch(String left, String right) {
        String a = canonicalGoal(left);
        String b = canonicalGoal(right);
        return a.length() >= 3 && a.equals(b);
    }

    static String canonicalGoal(String value) {
        String out = safe(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\"'：:；;（）()\\[\\]{}<>]+", "");
        String previous;
        do {
            previous = out;
            out = stripPrefix(out,
                    "請幫我", "请帮我", "麻煩幫我", "麻烦帮我",
                    "幫我", "帮我", "麻煩", "麻烦", "請", "请",
                    "please", "canyou", "couldyou");
            out = stripSuffix(out,
                    "一下", "好嗎", "好吗", "吧", "謝謝", "谢谢", "please");
        } while (!previous.equals(out));
        return out;
    }

    static boolean isProvenNavigationTap(
            String beforeStableScreen,
            String afterStableScreen) {
        String before = safe(beforeStableScreen);
        String after = safe(afterStableScreen);
        return !before.isEmpty()
                && !after.isEmpty()
                && !before.equals(after);
    }

    static boolean canPersistSearch(String query) {
        String value = safe(query);
        if (value.isEmpty() || value.length() > MAX_SEARCH_CHARS) return false;
        String folded = value.toLowerCase(Locale.ROOT);
        return !containsAny(folded,
                "password", "passcode", "otp", "one-time password",
                "密碼", "密码", "驗證碼", "验证码", "動態碼", "动态码");
    }

    static boolean isUnsafeTapTarget(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        if (text.isEmpty()) return true;
        return containsAny(text,
                "send", "submit", "delete", "remove", "uninstall",
                "buy", "purchase", "pay", "checkout", "order",
                "transfer", "withdraw", "trade", "confirm", "save",
                "送出", "發送", "发送", "提交", "刪除", "删除", "移除",
                "卸載", "卸载", "購買", "购买", "付款", "支付", "結帳", "结账",
                "下單", "下单", "轉帳", "转账", "匯款", "汇款",
                "提款", "交易", "確認", "确认", "儲存", "储存");
    }

    private static String stripPrefix(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix) && value.length() > prefix.length()) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    private static String stripSuffix(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
