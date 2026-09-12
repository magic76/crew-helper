package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Finds composer send buttons even when apps expose semantics and clickability
 * on different Accessibility nodes.
 *
 * Common modern UI shape:
 *   clickable parent
 *     -> non-clickable icon child with contentDescription="Send"
 *
 * Semantic evidence is required. Geometry only ranks an already-semantic match.
 */
final class ComposerSendResolver {
    private static final int MIN_SEND_SCORE = 115;
    private static final int MAX_CLICKABLE_ANCESTOR_DEPTH = 6;

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

        // 0076: search semantic nodes first, not only nodes that are themselves
        // clickable. Jetpack Compose and similar UI toolkits often put "Send"
        // semantics on a child icon while ACTION_CLICK belongs to its parent.
        ArrayList<AccessibilityNodeInfo> semanticNodes =
                new ArrayList<AccessibilityNodeInfo>();
        collectSendSemanticNodes(root, semanticNodes);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo semanticNode : semanticNodes) {
            AccessibilityNodeInfo clickable = null;
            try {
                clickable = findClickableAncestor(semanticNode);
                if (clickable == null) continue;

                Rect b = new Rect();
                clickable.getBoundsInScreen(b);
                if (b.width() <= 0 || b.height() <= 0) continue;

                String semanticMeta = metadata(semanticNode);
                String clickableMeta = metadata(clickable);
                String combinedMeta = semanticMeta + " " + clickableMeta;

                // Reject semantics still beat geometry, whether they live on the
                // icon itself or on its clickable container.
                if (hasRejectMarker(semanticMeta) || hasRejectMarker(clickableMeta)) {
                    continue;
                }

                int semanticScore = markerScore(semanticMeta);
                int clickableScore = markerScore(clickableMeta);
                int marker = Math.max(semanticScore, clickableScore);
                if (marker <= 0) continue;

                int score = marker;
                String cls = clickable.getClassName() == null
                        ? ""
                        : clickable.getClassName().toString().toLowerCase(Locale.ROOT);
                if (cls.contains("imagebutton")) score += 12;
                if (cls.contains("button")) score += 8;

                int yTolerance = Math.max(120, inputBounds.height());
                boolean sameRow = b.centerY() >= inputBounds.top - yTolerance
                        && b.centerY() <= inputBounds.bottom + yTolerance;
                boolean onRight = b.centerX() >= inputBounds.centerX();
                boolean closeToRightEdge =
                        b.left <= inputBounds.right + Math.max(220, inputBounds.height() * 3);
                if (sameRow) score += 35;
                if (onRight) score += 30;
                if (closeToRightEdge) score += 20;

                // Reject huge containers and obvious left-side controls.
                if (b.width() > Math.max(360, inputBounds.width())) score -= 80;
                if (b.centerX() < inputBounds.left) score -= 60;

                // Never synthesize Send from position alone. The candidate got
                // here only because semanticNode/clickable contained a send marker.
                if (score > bestScore) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(clickable);
                    bestScore = score;
                }
            } finally {
                if (clickable != null) clickable.recycle();
                semanticNode.recycle();
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
        return markerScore(metadata(node)) >= 100;
    }

    private static String metadata(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null
                ? ""
                : node.getContentDescription().toString();
        String id = node.getViewIdResourceName() == null
                ? ""
                : node.getViewIdResourceName().toString();
        return (text + " " + desc + " " + id).toLowerCase(Locale.ROOT);
    }

    private static int markerScore(String value) {
        if (value == null) return 0;
        if (hasRejectMarker(value)) return 0;
        if (value.contains("composer_send") || value.contains("message_send")
                || value.contains("action_send") || value.contains("send_btn")
                || value.contains("send_button") || value.contains("chat_send")
                || value.contains("ic_send") || value.contains("button_send")) return 160;
        if (value.contains("send") || value.contains("發送") || value.contains("发送")
                || value.contains("送出") || value.contains("傳送") || value.contains("传送")
                || value.contains("submit") || value.contains("發佈") || value.contains("发布")) return 140;
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
                || v.contains("microphone")
                || v.contains("voice")
                || v.contains("attach")
                || v.contains("attachment")
                || v.contains("emoji")
                || v.contains("camera")
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

    private static AccessibilityNodeInfo findLowestEditable(
            AccessibilityNodeInfo node, int[] bestBottom) {
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

    /**
     * Collect nodes that carry explicit send semantics regardless of whether the
     * semantic node itself is clickable.
     */
    private static void collectSendSemanticNodes(
            AccessibilityNodeInfo node, ArrayList<AccessibilityNodeInfo> out) {
        if (node == null) return;

        String meta = metadata(node);
        if (markerScore(meta) > 0 && node.isVisibleToUser()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectSendSemanticNodes(child, out);
            } finally {
                child.recycle();
            }
        }
    }

    /**
     * Return the semantic node itself when clickable, otherwise walk upward to
     * the nearest visible/enabled clickable ancestor. This is the key 0076 fix.
     */
    private static AccessibilityNodeInfo findClickableAncestor(
            AccessibilityNodeInfo semanticNode) {
        if (semanticNode == null) return null;

        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(semanticNode);
        for (int depth = 0;
                current != null && depth <= MAX_CLICKABLE_ANCESTOR_DEPTH;
                depth++) {
            if (current.isClickable() && current.isEnabled() && current.isVisibleToUser()) {
                return current;
            }

            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }

        if (current != null) current.recycle();
        return null;
    }
}
