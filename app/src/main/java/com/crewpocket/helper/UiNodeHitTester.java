package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** Picks the smallest visible node containing the taught screen coordinate. */
final class UiNodeHitTester {
    private UiNodeHitTester() {}

    static AccessibilityNodeInfo findBest(AccessibilityNodeInfo root, int x, int y) {
        Candidate c = visit(root, x, y, 0);
        return c == null ? null : c.node;
    }

    private static Candidate visit(AccessibilityNodeInfo node, int x, int y, int depth) {
        if (node == null || !node.isVisibleToUser()) return null;

        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (!b.contains(x, y)) return null;

        Candidate best = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                Candidate c = visit(child, x, y, depth + 1);
                if (better(c, best)) {
                    if (best != null && best.node != null) best.node.recycle();
                    best = c;
                } else if (c != null && c.node != null) {
                    c.node.recycle();
                }
            } finally {
                child.recycle();
            }
        }

        int area = Math.max(1, b.width() * b.height());
        int semantic = 0;
        if (node.isClickable()) semantic += 100;
        if (node.getViewIdResourceName() != null) semantic += 40;
        if (node.getContentDescription() != null) semantic += 30;

        Candidate self = new Candidate(AccessibilityNodeInfo.obtain(node), depth, area, semantic);
        if (better(self, best)) {
            if (best != null && best.node != null) best.node.recycle();
            return self;
        }
        self.node.recycle();
        return best;
    }

    private static boolean better(Candidate a, Candidate b) {
        if (a == null) return false;
        if (b == null) return true;
        if (a.semantic != b.semantic) return a.semantic > b.semantic;
        if (a.depth != b.depth) return a.depth > b.depth;
        return a.area < b.area;
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final int depth;
        final int area;
        final int semantic;

        Candidate(AccessibilityNodeInfo node, int depth, int area, int semantic) {
            this.node = node;
            this.depth = depth;
            this.area = area;
            this.semantic = semantic;
        }
    }
}
