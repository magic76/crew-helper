package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds composer send buttons even when apps expose them as unlabeled ImageButtons.
 * Metadata wins; geometry near the active editable composer is the fallback.
 */
final class ComposerSendResolver {
    private static final int MIN_SEND_SCORE = 115;

    private ComposerSendResolver() {}

    static AccessibilityNodeInfo find(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo input = findActiveEditable(root);
        if (input == null) return null;

        Rect inputBounds = new Rect();
        input.getBoundsInScreen(inputBounds);
        if (inputBounds.width() <= 0 || inputBounds.height() <= 0) {
            input.recycle();
            return null;
        }
        if (isSearchInput(input)) {
            input.recycle();
            return null;
        }

        ArrayList<AccessibilityNodeInfo> clickable = new ArrayList<AccessibilityNodeInfo>();
        collectClickable(root, clickable);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : clickable) {
            try {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (b.width() <= 0 || b.height() <= 0) continue;

                String text = node.getText() == null ? "" : node.getText().toString();
                String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
                String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toString();
                String cls = node.getClassName() == null ? "" : node.getClassName().toString();
                String meta = (text + " " + desc + " " + id).toLowerCase(Locale.ROOT);

                // Strong negative semantics always win over geometry. Search bars
                // commonly expose a clear-text X at the exact position where a
                // chat composer exposes Send.
                if (hasRejectMarker(meta)) continue;

                int score = markerScore(meta);
                if (cls.toLowerCase(Locale.ROOT).contains("imagebutton")) score += 12;
                if (cls.toLowerCase(Locale.ROOT).contains("button")) score += 8;

                int yTolerance = Math.max(120, inputBounds.height());
                boolean sameRow = b.centerY() >= inputBounds.top - yTolerance
                        && b.centerY() <= inputBounds.bottom + yTolerance;
                boolean onRight = b.centerX() >= inputBounds.centerX();
                boolean closeToRightEdge = b.left <= inputBounds.right + Math.max(220, inputBounds.height() * 3);
                if (sameRow) score += 35;
                if (onRight) score += 30;
                if (closeToRightEdge) score += 20;

                // Reject huge containers and obvious left-side controls.
                if (b.width() > Math.max(360, inputBounds.width())) score -= 80;
                if (b.centerX() < inputBounds.left) score -= 60;

                // Geometry is supporting evidence only. Never synthesize Send
                // from position alone.
                if (markerScore(meta) == 0) score = Math.min(score, MIN_SEND_SCORE - 1);

                if (score > bestScore) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(node);
                    bestScore = score;
                }
            } finally {
                node.recycle();
            }
        }
        input.recycle();

        if (bestScore < MIN_SEND_SCORE) {
            if (best != null) best.recycle();
            return null;
        }
        return best;
    }

    static boolean hasSendMarker(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toString();
        return markerScore((text + " " + desc + " " + id).toLowerCase(Locale.ROOT)) >= 100;
    }

    private static int markerScore(String value) {
        if (value == null) return 0;
        if (hasRejectMarker(value)) return 0;
        if (value.contains("send") || value.contains("發送") || value.contains("送出")
                || value.contains("傳送")) return 140;
        if (value.contains("composer_send") || value.contains("message_send")
                || value.contains("action_send") || value.contains("send_btn")
                || value.contains("send_button")) return 160;
        if (value.contains("arrow_upward") || value.contains("up_arrow")
                || value.contains("paper_plane")) return 110;
        return 0;
    }

    private static boolean hasRejectMarker(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.contains("clear")
                || v.contains("clear_text")
                || v.contains("clear_query")
                || v.contains("close")
                || v.contains("cancel")
                || v.contains("dismiss")
                || v.contains("reset")
                || v.contains("erase")
                || v.contains("delete")
                || v.contains("remove")
                || v.contains("cross")
                || v.contains("times")
                || v.contains("清除")
                || v.contains("取消")
                || v.contains("關閉")
                || v.contains("关闭")
                || v.contains("刪除")
                || v.contains("删除");
    }

    static boolean isSearchInput(AccessibilityNodeInfo input) {
        if (input == null) return false;
        String text = input.getText() == null ? "" : input.getText().toString();
        String desc = input.getContentDescription() == null ? "" : input.getContentDescription().toString();
        String id = input.getViewIdResourceName() == null ? "" : input.getViewIdResourceName().toString();
        String cls = input.getClassName() == null ? "" : input.getClassName().toString();
        String hint = "";
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && input.getHintText() != null) {
                hint = input.getHintText().toString();
            }
        } catch (Exception ignored) {}
        String meta = (text + " " + desc + " " + id + " " + cls + " " + hint).toLowerCase(Locale.ROOT);
        return meta.contains("search") || meta.contains("query") || meta.contains("filter")
                || meta.contains("搜尋") || meta.contains("搜索") || meta.contains("查找");
    }

    private static AccessibilityNodeInfo findActiveEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()
                && !SensitiveDataGuard.isHardBlockedInput(focused)) {
            return focused;
        }
        if (focused != null) focused.recycle();
        return findLowestEditable(root, new int[]{-1});
    }

    private static AccessibilityNodeInfo findLowestEditable(AccessibilityNodeInfo node, int[] bestBottom) {
        if (node == null) return null;
        AccessibilityNodeInfo best = null;
        if (node.isEditable() && !SensitiveDataGuard.isHardBlockedInput(node)) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.height() > 10 && b.bottom > bestBottom[0]) {
                bestBottom[0] = b.bottom;
                best = AccessibilityNodeInfo.obtain(node);
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo candidate = findLowestEditable(child, bestBottom);
                if (candidate != null) {
                    if (best != null) best.recycle();
                    best = candidate;
                }
            } finally {
                child.recycle();
            }
        }
        return best;
    }

    private static void collectClickable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable()) out.add(AccessibilityNodeInfo.obtain(node));
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectClickable(child, out);
            } finally {
                child.recycle();
            }
        }
    }
}
