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
    private static final int ANCHORED_TARGET_RADIUS_PX = 110;

    static final class Match {
        final AccessibilityNodeInfo node;  // null when coordinate-only
        final LearnedUiMappingStore.Rule rule;
        final int score;
        /** True when this match is coordinate-based (no reliable node found). */
        final boolean coordinateFallback;

        Match(AccessibilityNodeInfo node, LearnedUiMappingStore.Rule rule, int score) {
            this.node = node;
            this.rule = rule;
            this.score = score;
            this.coordinateFallback = false;
        }

        Match(LearnedUiMappingStore.Rule rule) {
            this.node = null;
            this.rule = rule;
            this.score = 0;
            this.coordinateFallback = true;
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
                if (!composerStateCompatible(rule, referenceNode)) continue;
                for (AccessibilityNodeInfo node : nodes) {
                    int score = score(node, rule, referenceNode);
                    if (score < 80) continue;
                    if (best == null || (!best.coordinateFallback && score > best.score)) {
                        if (best != null && best.node != null) best.node.recycle();
                        best = new Match(AccessibilityNodeInfo.obtain(node), rule, score);
                    }
                }
            }
        } finally {
            for (AccessibilityNodeInfo node : nodes) node.recycle();
        }

        // Structural match succeeded — use it.
        if (best != null && !best.coordinateFallback) return best;

        // 0020: NEVER execute a learned Send from absolute coordinates alone.
        // Coordinates remain supporting evidence only. If structural resolution
        // fails, let the caller fall back to strict semantic resolver / Vision.
        return null;
    }

    /**
     * 0022: resolve an anchored rule against the CURRENT accessibility tree.
     * Never taps the remembered absolute coordinate. The live anchor is first
     * resolved, then the expected target point is projected from that anchor.
     * A real clickable node must exist near that projected point.
     */
    static Match resolveAnchored(AccessibilityNodeInfo root,
                                 List<LearnedUiMappingStore.Rule> rules) {
        if (root == null || rules == null) return null;
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        try {
            Match best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (LearnedUiMappingStore.Rule rule : rules) {
                if (rule == null || !rule.anchored) continue;
                AccessibilityNodeInfo anchor = findAnchor(nodes, rule);
                if (anchor == null) continue;
                Rect ab = new Rect();
                anchor.getBoundsInScreen(ab);
                int expectedX = ab.centerX() + rule.targetOffsetX;
                int expectedY = ab.centerY() + rule.targetOffsetY;
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null || !node.isClickable()) continue;
                    Rect b = new Rect();
                    node.getBoundsInScreen(b);
                    if (b.isEmpty()) continue;
                    int dx = b.centerX() - expectedX;
                    int dy = b.centerY() - expectedY;
                    int distance = (int)Math.sqrt((double)dx * dx + (double)dy * dy);
                    if (distance > ANCHORED_TARGET_RADIUS_PX) continue;
                    // Target still needs structural compatibility with the
                    // learned target. Position is not enough by itself.
                    int structural = score(node, rule, anchor);
                    if (structural == Integer.MIN_VALUE) continue;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        if (best != null && best.node != null) best.node.recycle();
                        best = new Match(AccessibilityNodeInfo.obtain(node), rule,
                                structural + Math.max(0, 40 - distance / 3));
                    }
                }
                anchor.recycle();
            }
            return best;
        } finally {
            for (AccessibilityNodeInfo n : nodes) n.recycle();
        }
    }

    private static AccessibilityNodeInfo findAnchor(
            List<AccessibilityNodeInfo> nodes,
            LearnedUiMappingStore.Rule rule) {
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            int s = 0;
            String id = safe(node.getViewIdResourceName());
            String cls = safe(node.getClassName());
            String desc = safe(node.getContentDescription());
            if (!rule.anchorViewId.isEmpty()) {
                if (!rule.anchorViewId.equals(id)) continue;
                s += 100;
            }
            if (!rule.anchorClassName.isEmpty() && rule.anchorClassName.equals(cls)) s += 30;
            if (!rule.anchorContentDescription.isEmpty()
                    && rule.anchorContentDescription.equals(desc)) s += 50;
            if (s > bestScore) {
                bestScore = s;
                best = node;
            }
        }
        return best == null ? null : AccessibilityNodeInfo.obtain(best);
    }

    private static boolean composerStateCompatible(
            LearnedUiMappingStore.Rule rule,
            AccessibilityNodeInfo composer) {
        if (rule == null) return false;
        String learned = safe(rule.composerState).trim();
        if (learned.isEmpty() || "UNKNOWN".equals(learned)) return true;
        return learned.equals(LearnedUiMappingStore.composerState(composer));
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

        if (referenceNode != null) {
            if (!rule.composerClassName.isEmpty()
                    && rule.composerClassName.equals(safe(referenceNode.getClassName()))) {
                score += 12;
            }
            if (!rule.composerViewId.isEmpty()
                    && rule.composerViewId.equals(safe(referenceNode.getViewIdResourceName()))) {
                score += 24;
            }
        }

        // Coordinates are supporting evidence only. Never enough on their own.
        if (rule.hasCoordinate()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (!b.isEmpty()) {
                int dx = Math.abs(b.centerX() - rule.centerX);
                int dy = Math.abs(b.centerY() - rule.centerY);
                if (dx <= 96 && dy <= 96) score += 18;
                else if (dx <= 180 && dy <= 180) score += 8;
                else score -= 8;
            }
        }

        // Rules learned without a stable viewId need at least two strong
        // structural signals. Geometry alone is insufficient.
        if (rule.viewId.isEmpty()) {
            int strongSignals = 0;
            if (!rule.className.isEmpty() && rule.className.equals(cls)) strongSignals++;
            if (!rule.contentDescription.isEmpty() && rule.contentDescription.equals(desc)) strongSignals++;
            if (!rule.parentClassName.isEmpty()) {
                AccessibilityNodeInfo p = node.getParent();
                if (p != null) {
                    try {
                        if (rule.parentClassName.equals(safe(p.getClassName()))) strongSignals++;
                    } finally {
                        p.recycle();
                    }
                }
            }
            if (!rule.relativePosition.isEmpty() && referenceNode != null
                    && rule.relativePosition.equals(relativePosition(node, referenceNode))) {
                strongSignals++;
            }
            if (strongSignals < 2 || score < 80) return Integer.MIN_VALUE;
        }
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
