package com.crewpocket.helper;

public final class PendingActionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        allow("element_appears", "完成", "TAP", "下一步", "");
        allow("text_disappears", "載入中", "BACK", "", "");
        allow("screen_change", "", "NOTIFY", "", "");
        allow("text_appears", "搜尋完成", "TYPE", "", "hello");
        allow("text_appears", "結果", "COMMIT_SEARCH", "", "");

        deny("text_appears", "", "TAP", "下一步", "", "CONDITION_TEXT_REQUIRED");
        deny("text_appears", "完成", "TAP", "", "", "TARGET_REQUIRED");
        deny("text_appears", "完成", "TYPE", "", "", "TEXT_REQUIRED");
        deny("text_appears", "完成", "TAP", "送出", "", "HIGH_RISK_PENDING_ACTION_BLOCKED");
        deny("text_appears", "完成", "TAP", "立即付款", "", "HIGH_RISK_PENDING_ACTION_BLOCKED");
        deny("text_appears", "完成", "TAP", "Buy now", "", "HIGH_RISK_PENDING_ACTION_BLOCKED");
        deny("text_appears", "完成", "SEND", "", "", "UNSAFE_PENDING_ACTION");

        check(PendingActionPolicy.CONDITION_TEXT_APPEARS.equals(
                PendingActionPolicy.normalizeCondition("element_appears")),
                "element alias should normalize");
        check(PendingActionPolicy.ACTION_NOTIFY.equals(
                PendingActionPolicy.normalizeAction(" notify ")),
                "action should normalize");

        System.out.println("PendingActionPolicyTest passed " + checks + " checks");
    }

    private static void allow(
            String condition,
            String conditionText,
            String action,
            String target,
            String text) {
        PendingActionPolicy.Validation result =
                PendingActionPolicy.validate(condition, conditionText, action, target, text);
        check(result.allowed,
                "expected allowed: " + condition + " / " + action + " got " + result.code);
    }

    private static void deny(
            String condition,
            String conditionText,
            String action,
            String target,
            String text,
            String code) {
        PendingActionPolicy.Validation result =
                PendingActionPolicy.validate(condition, conditionText, action, target, text);
        check(!result.allowed, "expected denied: " + action);
        check(code.equals(result.code),
                "expected " + code + " got " + result.code);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
