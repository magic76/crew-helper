package com.crewpocket.helper;

import android.view.accessibility.AccessibilityNodeInfo;

/** Resolves an element id against the CURRENT Accessibility tree. */
final class SemanticElementResolver {
    private SemanticElementResolver() {}

    static AccessibilityNodeInfo resolve(AccessibilityNodeInfo root, String elementId) {
        if (root == null || elementId == null || elementId.trim().isEmpty()) return null;
        return find(root, elementId.trim(), -1, 0);
    }

    private static AccessibilityNodeInfo find(
            AccessibilityNodeInfo node, String target, int siblingIndex, int depth) {
        if (node == null) return null;
        if (target.equals(SemanticScreenState.elementId(node, siblingIndex, depth))) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo hit = find(child, target, i, depth + 1);
                if (hit != null) return hit;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    static AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo cursor = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < 5 && cursor != null; i++) {
            if (cursor.isClickable() && cursor.isEnabled()) return cursor;
            AccessibilityNodeInfo parent = cursor.getParent();
            cursor.recycle();
            cursor = parent;
        }
        if (cursor != null) cursor.recycle();
        return null;
    }
}
