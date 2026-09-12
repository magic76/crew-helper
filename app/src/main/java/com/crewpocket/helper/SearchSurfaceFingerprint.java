package com.crewpocket.helper;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Privacy-aware fingerprint of the app's SEARCH RESULT SURFACE.
 *
 * Differs from ScreenFingerprint:
 * - ignores editable/query text;
 * - excludes IME windows;
 * - ignores bounds so keyboard/layout movement alone is not "results";
 * - ignores common search chrome;
 * - returns only a hash.
 */
final class SearchSurfaceFingerprint {
    private static final int MAX_NODES = 160;

    private SearchSurfaceFingerprint() {}

    static String capture(CrewAccessibilityService service) {
        if (service == null) return "";
        AccessibilityNodeInfo root = null;
        try {
            root = findAppRoot(service);
            return create(root);
        } catch (Exception ignored) {
            return "";
        } finally {
            recycle(root);
        }
    }

    static Observation awaitChange(CrewAccessibilityService service,
                                   String baseline,
                                   long timeoutMs,
                                   long pollMs) {
        String base = baseline == null ? "" : baseline;
        String latest = capture(service);
        if (!base.isEmpty() && !latest.isEmpty() && !base.equals(latest)) {
            return new Observation(true, latest);
        }

        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        long pause = Math.max(40L, pollMs);
        while (System.currentTimeMillis() < deadline
                && !Thread.currentThread().isInterrupted()) {
            sleep(pause);
            latest = capture(service);
            if (!base.isEmpty() && !latest.isEmpty() && !base.equals(latest)) {
                return new Observation(true, latest);
            }
        }
        return new Observation(false, latest);
    }

    private static AccessibilityNodeInfo findAppRoot(CrewAccessibilityService service) {
        AccessibilityNodeInfo fallback = null;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null
                            || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue;
                    }

                    AccessibilityNodeInfo candidate = null;
                    try {
                        candidate = window.getRoot();
                        if (candidate == null) continue;

                        boolean preferred = false;
                        try {
                            preferred = window.isActive() || window.isFocused();
                        } catch (Exception ignored) {}

                        if (preferred) {
                            AccessibilityNodeInfo out = AccessibilityNodeInfo.obtain(candidate);
                            recycle(fallback);
                            return out;
                        }
                        if (fallback == null) {
                            fallback = AccessibilityNodeInfo.obtain(candidate);
                        }
                    } finally {
                        recycle(candidate);
                    }
                }
            }
        } catch (Exception ignored) {}

        if (fallback != null) return fallback;

        AccessibilityNodeInfo active = null;
        try {
            active = service.getRootInActiveWindow();
            return active == null ? null : AccessibilityNodeInfo.obtain(active);
        } catch (Exception ignored) {
            return null;
        } finally {
            recycle(active);
        }
    }

    private static String create(AccessibilityNodeInfo root) {
        if (root == null) return "";
        StringBuilder raw = new StringBuilder();
        raw.append(safe(root.getPackageName())).append('|');
        int[] count = new int[]{0};
        append(root, raw, count);
        return hash(raw.toString());
    }

    private static void append(AccessibilityNodeInfo node,
                               StringBuilder out,
                               int[] count) {
        if (node == null || count[0]++ >= MAX_NODES) return;

        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);
        String cls = normalize(safe(node.getClassName()));
        String id = normalize(safe(node.getViewIdResourceName()));
        String text = sensitive ? "" : normalize(safe(node.getText()));
        String desc = sensitive ? "" : normalize(safe(node.getContentDescription()));

        if (node.isEditable()) {
            // Query text itself is never search-result evidence.
            out.append("editable:")
                    .append(cls).append(':')
                    .append(id.length() > 96 ? id.substring(0, 96) : id)
                    .append('|');
        } else {
            String meta = (text + " " + desc + " " + id).trim();
            if (!isSearchChrome(meta)) {
                boolean meaningful = !text.isEmpty()
                        || !desc.isEmpty()
                        || !id.isEmpty()
                        || node.isScrollable()
                        || node.getChildCount() > 0;
                if (meaningful) {
                    out.append(cls).append(':')
                            .append(id).append(':')
                            .append(text).append(':')
                            .append(desc).append(':')
                            .append(node.isClickable() ? '1' : '0')
                            .append(node.isScrollable() ? '1' : '0')
                            .append(':').append(node.getChildCount())
                            .append('|');
                }
            }
        }

        int children = node.getChildCount();
        for (int i = 0; i < children && count[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                append(child, out, count);
            } finally {
                recycle(child);
            }
        }
    }

    private static boolean isSearchChrome(String meta) {
        String value = normalize(meta);
        if (value.isEmpty()) return false;

        if (equalsAny(value,
                "search", "搜尋", "搜索", "查找",
                "clear", "清除", "cancel", "取消",
                "back", "返回", "close", "關閉", "关闭",
                "voice", "microphone", "mic")) {
            return true;
        }

        // Deliberately do not reject "search_result_*".
        return containsAny(value,
                "search_field", "searchfield",
                "search_box", "searchbox",
                "search_bar", "searchbar",
                "query_field", "queryfield",
                "clear_query", "clearquery",
                "clear_text", "cleartext",
                "search_input", "searchinput",
                "voice_search", "voicesearch");
    }

    private static boolean equalsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.equals(normalize(needle))) return true;
        }
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(normalize(needle))) return true;
        }
        return false;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String clean = value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void recycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try { node.recycle(); } catch (Exception ignored) {}
        }
    }

    static final class Observation {
        final boolean changed;
        final String fingerprint;

        Observation(boolean changed, String fingerprint) {
            this.changed = changed;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }
}
