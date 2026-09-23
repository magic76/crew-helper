package com.crewpocket.helper;

import java.util.Locale;

/** Explicit user command policy for the Accessibility element overlay. */
final class ElementReferenceCommand {
    static final String MODEL_MARKER = "element_reference:open";
    static final String LEGACY_MODEL_MARKER = "visual_reference:open";

    private ElementReferenceCommand() {}

    static boolean isOpenRequest(String value) {
        return isModelMarker(value) || isUserOpenRequest(value);
    }

    static boolean isModelMarker(String value) {
        String raw = value == null ? "" : value.trim();
        return MODEL_MARKER.equalsIgnoreCase(raw)
                || LEGACY_MODEL_MARKER.equalsIgnoreCase(raw);
    }

    static boolean isUserOpenRequest(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;

        return normalized.equals("顯示元素")
                || normalized.equals("显示元素")
                || normalized.equals("顯示可點擊元素")
                || normalized.equals("显示可点击元素")
                || normalized.equals("顯示可以點的元素")
                || normalized.equals("显示可以点的元素")
                || normalized.equals("把可點的標出來")
                || normalized.equals("把可点的标出来")
                || normalized.equals("標出可點元素")
                || normalized.equals("标出可点元素")
                || normalized.equals("標出可以點的")
                || normalized.equals("标出可以点的")
                || normalized.equals("show elements")
                || normalized.equals("show clickable elements")
                || normalized.equals("show clickable items")
                || normalized.equals("show tappable elements")
                || normalized.equals("mark clickable elements");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[，,。.!！?？：:；;（）()]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
