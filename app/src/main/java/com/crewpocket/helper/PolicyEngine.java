package com.crewpocket.helper;

import java.util.Locale;

/** Code-level action safety gate. Model prompting is not treated as enforcement. */
final class PolicyEngine {
    enum Decision { ALLOW, CONFIRM, BLOCK }

    static final class Result {
        final Decision decision;
        final String reason;
        Result(Decision decision, String reason) {
            this.decision = decision;
            this.reason = reason == null ? "" : reason;
        }
        boolean blocked() { return decision == Decision.BLOCK; }
    }

    private PolicyEngine() {}

    static Result evaluate(String action, String label, String id, boolean sensitiveTarget) {
        String a = lower(action);
        String target = lower((label == null ? "" : label) + " " + (id == null ? "" : id));

        if ("type".equals(a) && sensitiveTarget) {
            return new Result(Decision.BLOCK, "credential input is blocked");
        }
        if (SensitiveDataGuard.containsSensitiveFieldMarker(target) && "type".equals(a)) {
            return new Result(Decision.BLOCK, "credential input is blocked");
        }
        if (isPaymentOrTradeAction(target)) {
            return new Result(Decision.BLOCK, "payment, purchase, transfer, trading, or withdrawal actions are blocked");
        }
        if (isDestructiveAccountAction(target)) {
            return new Result(Decision.BLOCK, "destructive account or password actions are blocked");
        }
        return new Result(Decision.ALLOW, "");
    }

    private static boolean isPaymentOrTradeAction(String value) {
        return containsAny(value,
                "pay", "payment", "purchase", "buy now", "checkout",
                "transfer", "send money", "withdraw", "withdrawal",
                "place order", "market buy", "market sell",
                "付款", "支付", "購買", "购买", "結帳", "结账",
                "轉帳", "转账", "匯款", "汇款", "提領", "提现",
                "買入", "买入", "賣出", "卖出", "下單", "下单");
    }

    private static boolean isDestructiveAccountAction(String value) {
        return containsAny(value,
                "delete account", "remove account", "close account",
                "change password", "reset password",
                "刪除帳戶", "删除账户", "刪除帳號", "删除账号",
                "關閉帳戶", "关闭账户", "修改密碼", "修改密码",
                "重設密碼", "重置密码");
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
