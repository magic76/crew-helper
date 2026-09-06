package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.security.MessageDigest;
import java.util.Locale;

/** Deterministic, privacy-aware signature used to detect whether an action changed the UI. */
final class ScreenFingerprint {
    private static final int MAX_NODES = 120;

    private ScreenFingerprint() {}

    static String create(AccessibilityNodeInfo root) {
        if (root == null) return "";
        StringBuilder state = new StringBuilder();
        CharSequence pkg = root.getPackageName();
        state.append(pkg == null ? "" : pkg.toString()).append('|');
        int[] count = new int[]{0};
        appendNode(root, state, count);
        return sha256(state.toString());
    }

    private static void appendNode(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0]++ >= MAX_NODES) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);

        String text = sensitive || node.getText() == null ? "" : normalize(node.getText().toString());
        String desc = sensitive || node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
        String id = node.getViewIdResourceName() == null ? "" : normalize(node.getViewIdResourceName().toString());
        String cls = node.getClassName() == null ? "" : normalize(node.getClassName().toString());

        out.append(cls).append(':')
                .append(id).append(':')
                .append(text).append(':')
                .append(desc).append(':')
                .append(node.isClickable() ? '1' : '0')
                .append(node.isEditable() ? '1' : '0')
                .append(node.isScrollable() ? '1' : '0')
                .append(':').append(b.left / 8).append(',')
                .append(b.top / 8).append(',')
                .append(b.right / 8).append(',')
                .append(b.bottom / 8).append('|');

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && count[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                appendNode(child, out, count);
            } finally {
                child.recycle();
            }
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
