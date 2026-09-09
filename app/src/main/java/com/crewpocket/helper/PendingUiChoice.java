package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Short-lived Runtime-owned human decision.
 *
 * 0037: a choice belongs to one user-intent generation and package. Fingerprint
 * is diagnostic only; fast-changing Maps metadata must not invalidate a valid row.
 */
final class PendingUiChoice {
    static final long TTL_MS = 25_000L;

    static final class Option {
        final String elementId;
        final String label;
        final String role;
        final String rowSignature;

        Option(String elementId, String label, String role) {
            this(elementId, label, role, "");
        }

        Option(String elementId, String label, String role, String rowSignature) {
            this.elementId = elementId == null ? "" : elementId;
            this.label = label == null ? "" : label;
            this.role = role == null ? "" : role;
            this.rowSignature = rowSignature == null ? "" : rowSignature;
        }
    }

    final long taskGeneration;
    final String packageName;
    final String query;
    final String continuation;
    final String fingerprint; // diagnostic only
    final ArrayList<Option> options;
    final long expiresAt;

    PendingUiChoice(long taskGeneration,
                    String packageName,
                    String query,
                    String continuation,
                    String fingerprint,
                    ArrayList<Option> options) {
        this.taskGeneration = taskGeneration;
        this.packageName = packageName == null ? "" : packageName;
        this.query = query == null ? "" : query;
        this.continuation = continuation == null ? "" : continuation;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.options = options == null ? new ArrayList<Option>() : options;
        this.expiresAt = System.currentTimeMillis() + TTL_MS;
    }

    // Compatibility constructor for any out-of-tree/local caller.
    PendingUiChoice(String fingerprint, ArrayList<Option> options) {
        this(-1L, "", "", "", fingerprint, options);
    }

    boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }

    Option resolveVoice(String spoken) {
        String s = fold(spoken);
        if (s.isEmpty()) return null;

        int ordinal = ordinal(s);
        if (ordinal >= 1 && ordinal <= options.size()) {
            return options.get(ordinal - 1);
        }

        if (s.length() < 2) return null;
        for (Option option : options) {
            String label = fold(option.label);
            if (label.length() >= 2 && (s.contains(label) || label.contains(s))) {
                return option;
            }
        }
        return null;
    }

    boolean isCancel(String spoken) {
        String s = fold(spoken);
        return s.equals("取消")
                || s.equals("不要")
                || s.equals("停止")
                || s.equals("算了")
                || s.equals("cancel")
                || s.equals("nevermind")
                || s.equals("nevermind");
    }

    private static int ordinal(String s) {
        if (s.equals("1") || s.equals("first") || s.equals("firstone")
                || s.contains("第一個") || s.contains("第1個")
                || s.contains("第一項") || s.contains("第一")) return 1;
        if (s.equals("2") || s.equals("second") || s.equals("secondone")
                || s.contains("第二個") || s.contains("第2個")
                || s.contains("第二項") || s.contains("第二")) return 2;
        if (s.equals("3") || s.equals("third") || s.equals("thirdone")
                || s.contains("第三個") || s.contains("第3個")
                || s.contains("第三項") || s.contains("第三")) return 3;
        if (s.equals("4") || s.equals("fourth") || s.equals("fourthone")
                || s.contains("第四個") || s.contains("第4個")
                || s.contains("第四項") || s.contains("第四")) return 4;
        return -1;
    }

    private static String fold(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。,.!！?？：:；;（）()]", "")
                .trim();
    }
}
