package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure Accessibility tree traversal/query helpers.
 *
 * This class owns no Android service lifecycle and performs no global actions.
 * Returned nodes are obtained copies unless documented otherwise; callers remain
 * responsible for recycling them, matching the previous service behavior.
 */
final class AccessibilityNodeRepository {
    private AccessibilityNodeRepository() {}

    static void collectClickableNodes(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> list) {
        if (node == null || list == null) return;
        if (node.isClickable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            // Preserve the existing guard against giant full-screen containers.
            if (bounds.width() > 0
                    && bounds.height() > 0
                    && (bounds.width() < 600 || bounds.height() < 400)) {
                list.add(AccessibilityNodeInfo.obtain(node));
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectClickableNodes(child, list);
            } finally {
                child.recycle();
            }
        }
    }

    static AccessibilityNodeInfo findActiveEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> editable =
                new ArrayList<AccessibilityNodeInfo>();
        collectEditableNodes(root, editable);

        AccessibilityNodeInfo lowest = null;
        int maxBottom = -1;
        for (AccessibilityNodeInfo node : editable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.bottom > maxBottom && bounds.height() > 10) {
                maxBottom = bounds.bottom;
                if (lowest != null) lowest.recycle();
                lowest = AccessibilityNodeInfo.obtain(node);
            }
            node.recycle();
        }
        return lowest;
    }

    static void collectEditableNodes(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> list) {
        if (node == null || list == null) return;
        if (isEditableCandidate(node)) {
            list.add(AccessibilityNodeInfo.obtain(node));
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectEditableNodes(child, list);
            } finally {
                child.recycle();
            }
        }
    }

    static boolean isEditableCandidate(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        return node.isEditable()
                || (cls != null
                        && cls.toString()
                                .toLowerCase(Locale.ROOT)
                                .contains("edittext"));
    }

    static AccessibilityNodeInfo findFocusedEditableNode(
            AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isEditableCandidate(node) && node.isFocused()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo hit =
                        findFocusedEditableNode(child);
                if (hit != null) return hit;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    static AccessibilityNodeInfo findEditableNode(
            AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isEditableCandidate(node)) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo result = findEditableNode(child);
                if (result != null) return result;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    static AccessibilityNodeInfo findMatchingNodeById(
            AccessibilityNodeInfo node,
            String id) {
        if (node == null) return null;
        String query = id == null ? "" : id.toLowerCase(Locale.ROOT);
        CharSequence viewId = node.getViewIdResourceName();
        if (viewId != null
                && viewId.toString().toLowerCase(Locale.ROOT).contains(query)) {
            AccessibilityNodeInfo clickable = findClickableAncestor(node);
            if (clickable != null) return clickable;
            return AccessibilityNodeInfo.obtain(node);
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo result =
                        findMatchingNodeById(child, id);
                if (result != null) return result;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    static AccessibilityNodeInfo findMatchingClickableNode(
            AccessibilityNodeInfo node,
            String label,
            boolean exact) {
        if (node == null) return null;
        String safeLabel = label == null ? "" : label;
        String query = safeLabel.toLowerCase(Locale.ROOT).trim();
        String text = node.getText() == null
                ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null
                ? "" : node.getContentDescription().toString().trim();
        String viewId = node.getViewIdResourceName() == null
                ? "" : node.getViewIdResourceName().toString().trim();

        boolean matched = exact
                ? (text.equalsIgnoreCase(safeLabel)
                        || desc.equalsIgnoreCase(safeLabel)
                        || viewId.equalsIgnoreCase(safeLabel))
                : (text.toLowerCase(Locale.ROOT).contains(query)
                        || desc.toLowerCase(Locale.ROOT).contains(query)
                        || viewId.toLowerCase(Locale.ROOT).contains(query));

        if (!matched && !exact && isSendIntent(safeLabel, "")) {
            String combined =
                    (text + " " + desc + " " + viewId)
                            .toLowerCase(Locale.ROOT);
            matched = hasSendMarker(combined);
        }

        if (matched) {
            AccessibilityNodeInfo clickable = findClickableAncestor(node);
            if (clickable != null) return clickable;
            return AccessibilityNodeInfo.obtain(node);
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo result =
                        findMatchingClickableNode(child, safeLabel, exact);
                if (result != null) return result;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    static AccessibilityNodeInfo findClickableAncestor(
            AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        while (current != null) {
            if (current.isClickable()) return current;
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        return null;
    }

    static AccessibilityNodeInfo findScrollableNode(
            AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo result = findScrollableNode(child);
                if (result != null) return result;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    static AccessibilityNodeInfo findActionNodeAtPoint(
            AccessibilityNodeInfo node,
            int x,
            int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo nested =
                        findActionNodeAtPoint(child, x, y);
                if (nested != null) return nested;
            } finally {
                child.recycle();
            }
        }
        return node.isClickable()
                ? AccessibilityNodeInfo.obtain(node)
                : null;
    }

    static void dumpNodesJson(
            AccessibilityNodeInfo node,
            StringBuilder out) {
        if (node == null || out == null) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        CharSequence viewId = node.getViewIdResourceName();
        boolean clickable = node.isClickable();
        boolean scrollable = node.isScrollable();
        boolean editable = node.isEditable();
        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);

        boolean hasContent =
                (text != null && text.length() > 0)
                        || (desc != null && desc.length() > 0)
                        || (viewId != null && viewId.length() > 0);
        if (hasContent || clickable || scrollable || editable) {
            out.append("{");
            out.append("\"class\":\"")
                    .append(cls == null ? "" : escape(cls.toString()))
                    .append("\",");
            out.append("\"text\":\"")
                    .append(sensitive
                            ? SensitiveDataGuard.REDACTED
                            : (text == null ? "" : escape(text.toString())))
                    .append("\",");
            out.append("\"desc\":\"")
                    .append(sensitive
                            ? SensitiveDataGuard.REDACTED
                            : (desc == null ? "" : escape(desc.toString())))
                    .append("\",");
            out.append("\"id\":\"")
                    .append(viewId == null ? "" : escape(viewId.toString()))
                    .append("\",");
            if (sensitive) out.append("\"sensitive\":true,");
            out.append("\"clickable\":").append(clickable).append(",");
            out.append("\"scrollable\":").append(scrollable).append(",");
            out.append("\"editable\":").append(editable).append(",");
            out.append("\"bounds\":{\"left\":")
                    .append(bounds.left)
                    .append(",\"top\":").append(bounds.top)
                    .append(",\"right\":").append(bounds.right)
                    .append(",\"bottom\":").append(bounds.bottom)
                    .append("}");
            out.append("},");
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                dumpNodesJson(child, out);
            } finally {
                child.recycle();
            }
        }
    }

    static boolean isSendIntent(String label, String id) {
        String query = ((label == null ? "" : label)
                + " "
                + (id == null ? "" : id))
                .toLowerCase(Locale.ROOT);
        return query.contains("發送")
                || query.contains("送出")
                || query.contains("傳送")
                || query.contains("send")
                || query.contains("提交")
                || query.contains("傳訊")
                || query.contains("reply");
    }

    private static boolean hasSendMarker(String value) {
        String text = value == null ? "" : value;
        return text.contains("發送")
                || text.contains("送出")
                || text.contains("傳送")
                || text.contains("提交")
                || text.contains("send")
                || text.contains("send-btn")
                || text.contains("send_button")
                || text.contains("composer_send")
                || text.contains("message_send")
                || text.contains("action_send")
                || text.contains("reply")
                || text.contains("arrow_upward")
                || text.contains("up_arrow");
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
