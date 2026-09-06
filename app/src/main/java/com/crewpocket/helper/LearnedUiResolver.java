package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves learned structural selectors back to live Accessibility nodes.
 * Strong metadata must match; geometry is only a tie-breaker.
 */
final class LearnedUiResolver {
    static final class Match {
        final AccessibilityNodeInfo node;
        final LearnedUiMappingStore.Rule rule;
        final int score;

        Match(AccessibilityNodeInfo node, LearnedUiMappingStore.Rule rule, int score) {
            this.node = node;
            this.rule = rule;
            this.score = score;
        }
    }

    private LearnedUiResolver() {}

    static Match resolve(AccessibilityNodeInfo root,
                         List<LearnedUiMappingStore.Rule> rules,
                         AccessibilityNodeInfo referenceNode) {
        if (root == null || rules == null || rules.isEmpty()) return null;
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);

        Match best = null;
        try {
            for (LearnedUiMappingStore.Rule rule : rules) {
                for (AccessibilityNodeInfo node : nodes) {
                    int score = score(node, rule, referenceNode);
                    if (score < 80) continue;
                    if (best == null || score > best.score) {
                        if (best != null && best.node != null) best.node.recycle();
                        best = new Match(AccessibilityNodeInfo.obtain(node), rule, score);
                    }
                }
            }
        } finally {
            for (AccessibilityNodeInfo node : nodes) node.recycle();
        }
        return best;
    }

    private static int score(AccessibilityNodeInfo node,
                             LearnedUiMappingStore.Rule rule,
                             AccessibilityNodeInfo referenceNode) {
        if (node == null || rule == null || !node.isClickable()) return Integer.MIN_VALUE;

        String id = safe(node.getViewIdResourceName());
        String cls = safe(node.getClassName());
        String desc = safe(node.getContentDescription());

        int score = 0;
        if (!rule.viewId.isEmpty()) {
            if (rule.viewId.equals(id)) score += 100;
            else return Integer.MIN_VALUE;
        }
        if (!rule.className.isEmpty() && rule.className.equals(cls)) score += 30;
        if (!rule.contentDescription.isEmpty()) {
            if (rule.contentDescription.equals(desc)) score += 70;
            else score -= 20;
        }

        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            try {
                if (!rule.parentClassName.isEmpty()
                        && rule.parentClassName.equals(safe(parent.getClassName()))) score += 20;
            } finally {
                parent.recycle();
            }
        }

        if (!rule.relativePosition.isEmpty() && referenceNode != null) {
            String actual = relativePosition(node, referenceNode);
            if (rule.relativePosition.equals(actual)) score += 25;
            else score -= 10;
        }

        // Rules learned without a stable viewId need at least two other signals.
        if (rule.viewId.isEmpty() && score < 80) return Integer.MIN_VALUE;
        return score;
    }

    private static String relativePosition(AccessibilityNodeInfo node, AccessibilityNodeInfo ref) {
        Rect a = new Rect();
        Rect b = new Rect();
        node.getBoundsInScreen(a);
        ref.getBoundsInScreen(b);
        if (a.centerX() > b.right) return "RIGHT_OF_REFERENCE";
        if (a.centerX() < b.left) return "LEFT_OF_REFERENCE";
        if (a.centerY() < b.top) return "ABOVE_REFERENCE";
        if (a.centerY() > b.bottom) return "BELOW_REFERENCE";
        return "OVERLAPS_REFERENCE";
    }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isVisibleToUser()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child, out); }
            finally { child.recycle(); }
        }
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
