package com.crewpocket.helper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure policy for Crew Experience learning.
 *
 * Runtime decides whether evidence is strong enough to review. Gemini is only
 * allowed to compress already-qualified evidence into a reusable lesson.
 */
final class ReflectionLearningPolicy {
    static final double MIN_CANDIDATE_CONFIDENCE = 0.70d;
    static final double MIN_VERIFIED_CONFIDENCE = 0.78d;
    static final int CONFIRMATIONS_TO_VERIFY = 2;

    private static final String[] SENSITIVE_PACKAGE_TOKENS = new String[] {
            "bank", "wallet", "payment", "finance", "crypto", "binance",
            "authenticator", "auth", "password", "keychain", "keystore",
            "inputmethod", "systemui", "packageinstaller"
    };

    private static final String[] PROHIBITED_LESSON_TOKENS = new String[] {
            "send message", "send text", "recipient", "payment", "purchase",
            "buy ", "transfer", "wire ", "otp", "one-time password",
            "password", "credential", "login", "sign in", "account change",
            "delete", "remove account", "submit form", "confirm payment",
            "傳送訊息", "發送訊息", "收件人", "付款", "購買", "轉帳",
            "匯款", "驗證碼", "密碼", "憑證", "登入", "帳號變更",
            "刪除帳號", "確認付款"
    };

    private ReflectionLearningPolicy() {}

    static boolean allowsExperienceLearning(boolean activeTask,
                                            boolean cancelled,
                                            boolean usedSendText,
                                            String packageName) {
        if (activeTask || cancelled || usedSendText) return false;
        return !isSensitivePackage(packageName);
    }

    /**
     * Backward-compatible entry point used by tests/callers. Mutation count and
     * generic failure signals no longer trigger a model review on their own.
     * Only deterministic evidence that passed the Experience gate may do so.
     */
    static boolean shouldReflect(int mutationActions,
                                 int outcomeSignals,
                                 boolean hasRuleEvidence,
                                 boolean activeTask,
                                 boolean cancelled,
                                 boolean usedSendText,
                                 String packageName) {
        return allowsExperienceLearning(activeTask, cancelled, usedSendText, packageName)
                && hasRuleEvidence;
    }

    static boolean isSensitivePackage(String packageName) {
        String pkg = normalize(packageName);
        if (pkg.isEmpty() || "com.crewpocket.helper".equals(pkg)) return true;
        for (String token : SENSITIVE_PACKAGE_TOKENS) {
            if (pkg.contains(token)) return true;
        }
        return false;
    }

    static boolean isSafeRuleLesson(String lesson, double confidence) {
        if (confidence < MIN_CANDIDATE_CONFIDENCE) return false;
        String text = collapse(lesson);
        if (text.length() < 8 || text.length() > 220) return false;
        if (containsSensitiveValue(text)) return false;
        String normalized = normalize(text);
        for (String token : PROHIBITED_LESSON_TOKENS) {
            if (normalized.contains(normalize(token))) return false;
        }
        return true;
    }

    static boolean lessonsCompatible(String first, String second) {
        String a = collapse(first).toLowerCase(Locale.ROOT);
        String b = collapse(second).toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;

        Set<String> left = tokens(a);
        Set<String> right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return false;
        int overlap = 0;
        for (String token : left) if (right.contains(token)) overlap++;
        int union = left.size() + right.size() - overlap;
        return union > 0 && ((double) overlap / (double) union) >= 0.45d;
    }

    static boolean looksCancelled(String rawStatus) {
        String text = normalize(rawStatus);
        return text.contains("cancel")
                || rawStatus != null && (rawStatus.contains("取消")
                || rawStatus.contains("停止任務")
                || rawStatus.contains("任務已停止"));
    }

    private static boolean containsSensitiveValue(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.contains("@") || text.contains("http://") || text.contains("https://")) return true;
        if (text.matches(".*\\b\\d{4,}\\b.*")) return true;
        if (text.matches(".*\\b(?:\\d[ -]?){8,}\\b.*")) return true;
        return false;
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<String>();
        for (String token : value.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+")) {
            if (token.length() >= 3) out.add(token);
        }
        return out;
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return collapse(value).toLowerCase(Locale.ROOT);
    }
}
