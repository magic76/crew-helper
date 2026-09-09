package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Locale;

/** Short-lived, screen-bound user choice. Never persists UI text or selectors. */
final class PendingUiChoice {
    static final long TTL_MS = 25_000L;

    static final class Option {
        final String elementId;
        final String label;
        final String role;
        Option(String elementId, String label, String role) {
            this.elementId = elementId == null ? "" : elementId;
            this.label = label == null ? "" : label;
            this.role = role == null ? "" : role;
        }
    }

    final String fingerprint;
    final ArrayList<Option> options;
    final long expiresAt;

    PendingUiChoice(String fingerprint, ArrayList<Option> options) {
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.options = options == null ? new ArrayList<Option>() : options;
        this.expiresAt = System.currentTimeMillis() + TTL_MS;
    }

    boolean expired() { return System.currentTimeMillis() > expiresAt; }

    Option resolveVoice(String spoken) {
        String s = fold(spoken);
        if (s.isEmpty()) return null;
        int ordinal = ordinal(s);
        if (ordinal >= 1 && ordinal <= options.size()) return options.get(ordinal - 1);
        for (Option option : options) {
            String label = fold(option.label);
            if (!label.isEmpty() && (s.contains(label) || label.contains(s))) return option;
        }
        return null;
    }

    boolean isCancel(String spoken) {
        String s = fold(spoken);
        return s.equals("取消") || s.equals("不要") || s.equals("停止") || s.equals("cancel");
    }

    private static int ordinal(String s) {
        if (s.contains("第一") || s.contains("第1") || s.equals("1") || s.contains("一個")) return 1;
        if (s.contains("第二") || s.contains("第2") || s.equals("2") || s.contains("兩個")) return 2;
        if (s.contains("第三") || s.contains("第3") || s.equals("3")) return 3;
        if (s.contains("第四") || s.contains("第4") || s.equals("4")) return 4;
        return -1;
    }

    private static String fold(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。,.!！?？]", "").trim();
    }
}
