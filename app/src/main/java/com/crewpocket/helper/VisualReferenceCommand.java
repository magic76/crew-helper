package com.crewpocket.helper;

import java.util.Locale;

/**
 * Explicit-only command policy for Shared Visual Reference.
 *
 * The grid is a user-invoked assistive mode, never an automatic recovery from
 * UI_TARGET_NOT_FOUND. Keep this vocabulary deliberately small so ordinary UI
 * labels, search results, and misunderstood speech cannot accidentally arm it.
 */
final class VisualReferenceCommand {
    static final String MODEL_MARKER = "visual_reference:open";

    private VisualReferenceCommand() {}

    static boolean isOpenRequest(String value) {
        String raw = value == null ? "" : value.trim();
        if (MODEL_MARKER.equalsIgnoreCase(raw)) return true;

        String normalized = normalize(raw);
        if (normalized.isEmpty()) return false;

        return normalized.equals("開方格")
                || normalized.equals("显示方格")
                || normalized.equals("顯示方格")
                || normalized.equals("把方格顯示出來")
                || normalized.equals("把方格显示出来")
                || normalized.equals("打开方格")
                || normalized.equals("打開方格")
                || normalized.equals("開啟方格")
                || normalized.equals("开启方格")
                || normalized.equals("我來選位置")
                || normalized.equals("我来选位置")
                || normalized.equals("讓我選位置")
                || normalized.equals("让我选位置")
                || normalized.equals("讓我指位置")
                || normalized.equals("让我指位置")
                || normalized.equals("讓我指給你")
                || normalized.equals("让我指给你")
                || normalized.equals("我告訴你位置")
                || normalized.equals("我告诉你位置")
                || normalized.equals("選位置")
                || normalized.equals("选位置")
                || normalized.equals("open grid")
                || normalized.equals("show grid")
                || normalized.equals("show the grid")
                || normalized.equals("let me choose the position")
                || normalized.equals("let me point it out");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[，,。.!！?？：:；;（）()]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
