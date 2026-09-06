package com.crewpocket.helper;

import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/** Central credential-field detection shared by perception and action safety. */
final class SensitiveDataGuard {
    static final String REDACTED = "[REDACTED]";

    private SensitiveDataGuard() {}

    static boolean containsSensitiveFieldMarker(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replace('/', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.contains("password")
                || normalized.contains("passcode")
                || containsToken(normalized, "pin")
                || containsToken(normalized, "otp")
                || normalized.contains("one time password")
                || normalized.contains("verification code")
                || normalized.contains("security code")
                || containsToken(normalized, "cvv")
                || containsToken(normalized, "cvc")
                || normalized.contains("card number")
                || normalized.contains("credit card")
                || normalized.contains("debit card")
                || normalized.contains("密碼")
                || normalized.contains("密码")
                || normalized.contains("驗證碼")
                || normalized.contains("验证码")
                || normalized.contains("動態密碼")
                || normalized.contains("动态密码")
                || normalized.contains("安全碼")
                || normalized.contains("安全码")
                || normalized.contains("信用卡號")
                || normalized.contains("信用卡号");
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    static boolean isPasswordInputType(int inputType) {
        int variation = inputType & (InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION);
        return variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                || variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || variation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    /**
     * Hard-blocking typing must be narrower than inspection redaction.
     * OTP/PIN/CVV-like fields can remain redacted from model inspection without
     * accidentally disabling ordinary message/search/composer input.
     */
    static boolean isHardBlockedInput(AccessibilityNodeInfo node) {
        return node != null && (node.isPassword() || isPasswordInputType(node.getInputType()));
    }

    static boolean isSensitiveNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (isHardBlockedInput(node)) return true;

        String viewId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        if (containsSensitiveFieldMarker(viewId) || containsSensitiveFieldMarker(desc)) return true;

        // Restrict text-based marker matching to editable controls so ordinary
        // screen content mentioning "password" is not blanket-redacted.
        return node.isEditable()
                && node.getText() != null
                && containsSensitiveFieldMarker(node.getText().toString());
    }
}
