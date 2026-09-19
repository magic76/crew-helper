package com.crewpocket.helper;

import java.util.Locale;

/** Deterministic safety boundary for delayed Runtime actions. */
final class PendingActionPolicy {
    static final String CONDITION_TEXT_APPEARS = "TEXT_APPEARS";
    static final String CONDITION_TEXT_DISAPPEARS = "TEXT_DISAPPEARS";
    static final String CONDITION_SCREEN_CHANGE = "SCREEN_CHANGE";

    static final String ACTION_TAP = "TAP";
    static final String ACTION_TYPE = "TYPE";
    static final String ACTION_BACK = "BACK";
    static final String ACTION_HOME = "HOME";
    static final String ACTION_COMMIT_SEARCH = "COMMIT_SEARCH";
    static final String ACTION_NOTIFY = "NOTIFY";

    static final class Validation {
        final boolean allowed;
        final String conditionType;
        final String action;
        final String code;
        final String message;

        Validation(
                boolean allowed,
                String conditionType,
                String action,
                String code,
                String message) {
            this.allowed = allowed;
            this.conditionType = conditionType;
            this.action = action;
            this.code = code;
            this.message = message;
        }
    }

    private PendingActionPolicy() {}

    static Validation validate(
            String rawConditionType,
            String conditionText,
            String rawAction,
            String target,
            String text) {
        String conditionType = normalizeCondition(rawConditionType);
        String action = normalizeAction(rawAction);

        if (conditionType.isEmpty()) {
            return reject("BAD_CONDITION", "只支援文字出現、文字消失或畫面改變。");
        }
        if ((CONDITION_TEXT_APPEARS.equals(conditionType)
                        || CONDITION_TEXT_DISAPPEARS.equals(conditionType))
                && blank(conditionText)) {
            return reject("CONDITION_TEXT_REQUIRED", "等待文字條件需要 condition_text。");
        }

        if (action.isEmpty()) {
            return reject(
                    "UNSAFE_PENDING_ACTION",
                    "延後動作只支援 TAP、TYPE、BACK、HOME、COMMIT_SEARCH 或 NOTIFY。");
        }

        if (ACTION_TAP.equals(action)) {
            if (blank(target)) {
                return reject("TARGET_REQUIRED", "延後 TAP 需要語意 target。");
            }
            if (UserActionScope.looksLikeSendTarget(target)
                    || looksLikeHighRiskCommit(target)) {
                return reject(
                        "HIGH_RISK_PENDING_ACTION_BLOCKED",
                        "送出訊息、付款、購買、刪除等提交型操作不能設成延後自動動作。");
            }
        }

        if (ACTION_TYPE.equals(action) && blank(text)) {
            return reject("TEXT_REQUIRED", "延後 TYPE 需要 text；TYPE 只輸入、不送出。");
        }

        return new Validation(true, conditionType, action, "", "");
    }

    static String normalizeCondition(String value) {
        String v = normalizeToken(value);
        if ("TEXT_APPEARS".equals(v)
                || "ELEMENT_APPEARS".equals(v)
                || "APPEARS".equals(v)) {
            return CONDITION_TEXT_APPEARS;
        }
        if ("TEXT_DISAPPEARS".equals(v)
                || "ELEMENT_DISAPPEARS".equals(v)
                || "DISAPPEARS".equals(v)) {
            return CONDITION_TEXT_DISAPPEARS;
        }
        if ("SCREEN_CHANGE".equals(v) || "SCREEN_CHANGES".equals(v)) {
            return CONDITION_SCREEN_CHANGE;
        }
        return "";
    }

    static String normalizeAction(String value) {
        String v = normalizeToken(value);
        if (ACTION_TAP.equals(v)
                || ACTION_TYPE.equals(v)
                || ACTION_BACK.equals(v)
                || ACTION_HOME.equals(v)
                || ACTION_COMMIT_SEARCH.equals(v)
                || ACTION_NOTIFY.equals(v)) {
            return v;
        }
        return "";
    }

    static boolean looksLikeHighRiskCommit(String value) {
        String v = compact(value);
        if (v.isEmpty()) return false;
        return containsAny(
                v,
                "付款", "支付", "購買", "购买", "買入", "买入", "下單", "下单",
                "結帳", "结账", "訂購", "订购", "訂閱", "订阅",
                "刪除", "删除", "移除帳號", "移除账号", "註銷帳號", "注销账号",
                "paynow", "payment", "purchase", "buynow", "placeorder",
                "checkout", "subscribe", "delete", "removeaccount",
                "closeaccount", "uninstall");
    }

    private static Validation reject(String code, String message) {
        return new Validation(false, "", "", code, message);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizeToken(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String compact(String value) {
        return TextMatch.caseFold(value == null ? "" : value)
                .replaceAll("[\\s，,。！？!「」『』\"'：:；;（）()_-]", "");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            String n = compact(needle);
            if (!n.isEmpty() && value.contains(n)) return true;
        }
        return false;
    }
}
