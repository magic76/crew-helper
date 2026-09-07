package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Compact semantic screen model. Accessibility first, Vision only as fallback. */
final class SemanticScreenState {
    private static final int MAX_ELEMENTS = 120;
    private SemanticScreenState() {}

    static JSONObject capture(AccessibilityNodeInfo root) {
        JSONObject out = new JSONObject();
        if (root == null) {
            try {
                out.put("success", false)
                   .put("error", "NO_ACCESSIBILITY_ROOT")
                   .put("visionRecommended", true)
                   .put("visionReason", "NO_ACCESSIBILITY_ROOT");
            } catch (Exception ignored) {}
            return out;
        }
        JSONArray elements = new JSONArray();
        Stats stats = new Stats();
        collect(root, null, -1, 0, elements, stats);
        try {
            out.put("success", true)
               .put("package", root.getPackageName() == null ? "" : root.getPackageName().toString())
               .put("fingerprint", ScreenFingerprint.create(root))
               .put("stableScreenKey", StableScreenKey.create(root))
               .put("elements", elements)
               .put("elementCount", elements.length())
               .put("actionableCount", stats.actionable)
               .put("labeledActionableCount", stats.labeledActionable)
               .put("unlabeledIconCount", stats.unlabeledIcon)
               .put("visionRecommended", shouldRecommendVision(stats))
               .put("visionReason", visionReason(stats));
        } catch (Exception ignored) {}
        return out;
    }

    static String elementId(AccessibilityNodeInfo node, int siblingIndex, int depth) {
        if (node == null) return "";
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);
        String raw = safe(node.getViewIdResourceName()) + "|" + safe(node.getClassName()) + "|"
                + (sensitive ? "" : normalize(safe(node.getText()))) + "|"
                + (sensitive ? "" : normalize(safe(node.getContentDescription()))) + "|"
                + b.left + "," + b.top + "," + b.right + "," + b.bottom + "|"
                + siblingIndex + "|" + depth;
        return "e_" + shortHash(raw);
    }

    private static void collect(AccessibilityNodeInfo node, AccessibilityNodeInfo parent,
                                int siblingIndex, int depth, JSONArray out, Stats stats) {
        if (node == null || out.length() >= MAX_ELEMENTS) return;
        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);
        boolean actionable = node.isClickable() || node.isEditable() || node.isScrollable();
        String text = sensitive ? SensitiveDataGuard.REDACTED : safe(node.getText()).trim();
        String desc = sensitive ? SensitiveDataGuard.REDACTED : safe(node.getContentDescription()).trim();
        String label = !text.isEmpty() ? text : desc;
        String role = inferRole(node);
        String viewId = safe(node.getViewIdResourceName());
        String semanticHint = semanticHint(label, viewId, role);

        if (actionable || !label.isEmpty() || "list".equals(role) || "scroll_container".equals(role)) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            try {
                JSONObject element = new JSONObject()
                    .put("id", elementId(node, siblingIndex, depth))
                    .put("role", role)
                    .put("label", label)
                    .put("semanticHint", semanticHint)
                    .put("viewId", viewId)
                    .put("clickable", node.isClickable())
                    .put("editable", node.isEditable())
                    .put("scrollable", node.isScrollable())
                    .put("enabled", node.isEnabled())
                    .put("selected", node.isSelected())
                    .put("focused", node.isFocused())
                    .put("sensitive", sensitive)
                    .put("depth", depth)
                    .put("index", siblingIndex)
                    .put("source", "accessibility")
                    .put("confidence", confidence(node, label, semanticHint))
                    .put("bounds", new JSONObject()
                        .put("left", b.left).put("top", b.top)
                        .put("right", b.right).put("bottom", b.bottom));
                if (parent != null) element.put("parentRole", inferRole(parent));
                out.put(element);

                if (actionable) {
                    stats.actionable++;
                    if (!label.isEmpty() || !semanticHint.isEmpty() || !viewId.isEmpty()) stats.labeledActionable++;
                    if (isIconLike(node) && label.isEmpty() && semanticHint.isEmpty() && viewId.isEmpty()) stats.unlabeledIcon++;
                }
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < node.getChildCount() && out.length() < MAX_ELEMENTS; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child, node, i, depth + 1, out, stats); }
            finally { child.recycle(); }
        }
    }

    private static String inferRole(AccessibilityNodeInfo node) {
        String cls = safe(node.getClassName()).toLowerCase(Locale.US);
        if (node.isEditable()) return "text_field";
        if (cls.contains("checkbox")) return "checkbox";
        if (cls.contains("switch")) return "switch";
        if (cls.contains("radiobutton")) return "radio";
        if (cls.contains("imagebutton")) return "icon_button";
        if (cls.contains("button") || node.isClickable()) return "button";
        if (cls.contains("recyclerview") || cls.contains("listview")) return "list";
        if (node.isScrollable()) return "scroll_container";
        if (cls.contains("image")) return "image";
        if (cls.contains("text")) return "text";
        return "container";
    }

    private static String semanticHint(String label, String viewId, String role) {
        String h = (label + " " + viewId).toLowerCase(Locale.US);
        if (containsAny(h, "search", "搜尋", "搜索")) return "search";
        if (containsAny(h, "send", "送出", "傳送", "发送")) return "send";
        if (containsAny(h, "back", "返回", "上一頁", "上一页")) return "back";
        if (containsAny(h, "close", "cancel", "取消", "關閉", "关闭")) return "close";
        if (containsAny(h, "more", "menu", "更多", "選單", "菜单")) return "more";
        if (containsAny(h, "add", "新增", "加入", "添加")) return "add";
        if ("icon_button".equals(role) && !label.isEmpty()) return normalize(label);
        return "";
    }

    private static double confidence(AccessibilityNodeInfo node, String label, String hint) {
        if (!hint.isEmpty()) return 0.96;
        if (!label.isEmpty() && node.isClickable()) return 0.92;
        if (!safe(node.getViewIdResourceName()).isEmpty()) return 0.86;
        if (node.isClickable()) return 0.55;
        return 0.70;
    }

    private static boolean shouldRecommendVision(Stats s) {
        return s.actionable == 0 || s.labeledActionable == 0
                || (s.unlabeledIcon >= 2 && s.labeledActionable * 2 < s.actionable);
    }

    private static String visionReason(Stats s) {
        if (s.actionable == 0) return "NO_ACTIONABLE_ACCESSIBILITY_ELEMENTS";
        if (s.labeledActionable == 0) return "ACTIONABLE_ELEMENTS_HAVE_NO_SEMANTICS";
        if (s.unlabeledIcon >= 2 && s.labeledActionable * 2 < s.actionable) return "MANY_UNLABELED_ICONS";
        return "";
    }

    private static boolean isIconLike(AccessibilityNodeInfo node) {
        String cls = safe(node.getClassName()).toLowerCase(Locale.US);
        return cls.contains("image") || (node.isClickable() && safe(node.getText()).trim().isEmpty());
    }

    private static boolean containsAny(String h, String... needles) {
        for (String n : needles) if (h.contains(n)) return true;
        return false;
    }

    private static String safe(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    private static String shortHash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(raw.hashCode()); }
    }

    private static final class Stats {
        int actionable;
        int labeledActionable;
        int unlabeledIcon;
    }
}
