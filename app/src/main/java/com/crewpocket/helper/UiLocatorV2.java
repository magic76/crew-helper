package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accessibility-first semantic locator.
 *
 * Coordinates are never sufficient evidence.  Geometry is used only for
 * relative position / region tie-breaking after structural semantics exist.
 */
final class UiLocatorV2 {
    private static final int MAX_NODES = 220;
    private static final int MAX_CLICKABLE_ANCESTOR_HOPS = 6;

    static final class Match {
        final AccessibilityNodeInfo node; // non-null only for AUTO
        final UiLocatorScorer.Decision decision;
        final double confidence;
        final double runnerUpConfidence;
        final int score;
        final String code;
        final String matchedElementId;
        final String matchedRole;
        final String matchedSemanticHint;
        final String source;
        final List<String> reasons;

        Match(AccessibilityNodeInfo node,
              UiLocatorScorer.Decision decision,
              double confidence,
              double runnerUpConfidence,
              int score,
              String code,
              UiNodeSnapshot snapshot,
              List<String> reasons) {
            this.node = node;
            this.decision = decision == null ? UiLocatorScorer.Decision.NOT_FOUND : decision;
            this.confidence = confidence;
            this.runnerUpConfidence = runnerUpConfidence;
            this.score = score;
            this.code = code == null ? "" : code;
            this.matchedElementId = snapshot == null ? "" : snapshot.elementId;
            this.matchedRole = snapshot == null ? "" : snapshot.role;
            this.matchedSemanticHint = snapshot == null ? "" : snapshot.semanticHint;
            this.source = "accessibility_v2";
            this.reasons = reasons == null
                    ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<String>(reasons));
        }

        boolean autoExecutable() {
            return decision == UiLocatorScorer.Decision.AUTO && node != null;
        }

        void recycle() {
            if (node != null) {
                try { node.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private static final class Candidate {
        AccessibilityNodeInfo actionNode;
        final UiNodeSnapshot snapshot;

        Candidate(AccessibilityNodeInfo actionNode, UiNodeSnapshot snapshot) {
            this.actionNode = actionNode;
            this.snapshot = snapshot;
        }

        void recycle() {
            if (actionNode != null) {
                try { actionNode.recycle(); } catch (Exception ignored) {}
                actionNode = null;
            }
        }
    }

    private UiLocatorV2() {}

    static Match resolve(AccessibilityNodeInfo root, UiTargetSpec spec) {
        if (root == null) return empty(UiLocatorScorer.Decision.NOT_FOUND, "NO_ACCESSIBILITY_ROOT");
        if (spec == null || spec.isEmpty()) return empty(UiLocatorScorer.Decision.NOT_FOUND, "EMPTY_TARGET_SPEC");

        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int screenWidth = Math.max(1, rootBounds.right > 0 ? rootBounds.right : rootBounds.width());
        int screenHeight = Math.max(1, rootBounds.bottom > 0 ? rootBounds.bottom : rootBounds.height());

        ArrayList<Candidate> candidates = new ArrayList<Candidate>();
        collect(root, null, -1, 0, screenWidth, screenHeight, spec, candidates, new int[]{0});
        if (candidates.isEmpty()) return empty(UiLocatorScorer.Decision.NOT_FOUND, "NO_UI_CANDIDATES");

        try {
            ArrayList<UiNodeSnapshot> snapshots = new ArrayList<UiNodeSnapshot>();
            for (Candidate candidate : candidates) snapshots.add(candidate.snapshot);

            UiNodeSnapshot anchor = null;
            if (spec.hasAnchor()) {
                UiTargetSpec anchorSpec = UiTargetSpec.builder()
                        .actionKind(UiTargetSpec.ActionKind.GENERIC)
                        .label(spec.anchorLabel)
                        .viewId(spec.anchorViewId)
                        .build();
                UiLocatorScorer.Ranking anchorRanking = UiLocatorScorer.rank(snapshots, anchorSpec, null);
                if (anchorRanking.best != null && anchorRanking.best.confidence >= 0.68) {
                    anchor = anchorRanking.best.node;
                }
            }

            UiLocatorScorer.Ranking ranking = UiLocatorScorer.rank(snapshots, spec, anchor);
            UiLocatorScorer.Score best = ranking.best;
            UiLocatorScorer.Score second = ranking.second;
            if (best == null) {
                return new Match(null, ranking.decision, 0.0, 0.0, 0,
                        ranking.code, null, null);
            }

            AccessibilityNodeInfo resolved = null;
            if (ranking.decision == UiLocatorScorer.Decision.AUTO) {
                Candidate candidate = findCandidate(candidates, best.node.instanceId);
                if (candidate != null && candidate.actionNode != null) {
                    resolved = candidate.actionNode;
                    candidate.actionNode = null; // transfer ownership to Match
                }
                if (resolved == null) {
                    return new Match(null, UiLocatorScorer.Decision.FALLBACK,
                            best.confidence,
                            second == null ? 0.0 : second.confidence,
                            best.points,
                            "AUTO_MATCH_HAS_NO_ACTION_NODE",
                            best.node,
                            best.reasons);
                }
            }

            return new Match(resolved, ranking.decision,
                    best.confidence,
                    second == null ? 0.0 : second.confidence,
                    best.points,
                    ranking.code,
                    best.node,
                    best.reasons);
        } finally {
            for (Candidate candidate : candidates) candidate.recycle();
        }
    }

    private static void collect(AccessibilityNodeInfo node,
                                AccessibilityNodeInfo parent,
                                int siblingIndex,
                                int depth,
                                int screenWidth,
                                int screenHeight,
                                UiTargetSpec spec,
                                List<Candidate> out,
                                int[] visited) {
        if (node == null || visited[0]++ >= MAX_NODES) return;

        boolean visible = false;
        try { visible = node.isVisibleToUser(); } catch (Exception ignored) {}
        if (visible) {
            AccessibilityNodeInfo actionNode = actionNodeFor(node, spec.actionKind);
            boolean actionable = actionNode != null;
            boolean editable = false;
            if (spec.actionKind == UiTargetSpec.ActionKind.TYPE) {
                editable = isEditable(node);
            } else {
                editable = isEditable(node);
            }

            boolean sensitive = false;
            try {
                sensitive = SensitiveDataGuard.isSensitiveNode(node)
                        || (actionNode != null && SensitiveDataGuard.isSensitiveNode(actionNode));
            } catch (Exception ignored) {}

            Rect b = new Rect();
            node.getBoundsInScreen(b);
            String text = sensitive ? "" : safe(node.getText());
            String desc = sensitive ? "" : safe(node.getContentDescription());
            String label = !text.trim().isEmpty() ? text.trim() : desc.trim();
            String viewId = safe(node.getViewIdResourceName());
            String cls = safe(node.getClassName());
            String role = inferRole(node);
            String hint = UiLocatorScorer.inferHint(label + " " + viewId);
            String parentRole = parent == null ? "" : inferRole(parent);
            String elementId = "";
            try { elementId = SemanticScreenState.elementId(node, siblingIndex, depth); }
            catch (Exception ignored) {}

            String instanceId = "n" + visited[0] + "_" + depth + "_" + siblingIndex;
            String actionKey = actionNode == null
                    ? rawActionKey(node)
                    : rawActionKey(actionNode);

            UiNodeSnapshot snapshot = new UiNodeSnapshot(
                    instanceId,
                    actionKey,
                    elementId,
                    text,
                    desc,
                    label,
                    viewId,
                    cls,
                    role,
                    hint,
                    parentRole,
                    spec.actionKind == UiTargetSpec.ActionKind.TAP ? actionable : node.isClickable(),
                    editable,
                    node.isEnabled(),
                    visible,
                    sensitive,
                    depth,
                    siblingIndex,
                    b.left,
                    b.top,
                    b.right,
                    b.bottom,
                    screenWidth,
                    screenHeight);
            out.add(new Candidate(actionNode, snapshot));
        }

        int count = node.getChildCount();
        for (int i = 0; i < count && visited[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collect(child, node, i, depth + 1, screenWidth, screenHeight,
                        spec, out, visited);
            } finally {
                child.recycle();
            }
        }
    }

    private static AccessibilityNodeInfo actionNodeFor(AccessibilityNodeInfo node,
                                                       UiTargetSpec.ActionKind actionKind) {
        if (node == null) return null;
        if (actionKind == UiTargetSpec.ActionKind.TYPE) {
            return isEditable(node) ? AccessibilityNodeInfo.obtain(node) : null;
        }
        if (actionKind == UiTargetSpec.ActionKind.TAP) {
            return nearestClickable(node);
        }
        if (node.isClickable() || isEditable(node)) return AccessibilityNodeInfo.obtain(node);
        return null;
    }

    private static AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < MAX_CLICKABLE_ANCESTOR_HOPS && cursor != null; i++) {
            if (cursor.isClickable() && cursor.isEnabled()) return cursor;
            AccessibilityNodeInfo parent = cursor.getParent();
            cursor.recycle();
            cursor = parent;
        }
        if (cursor != null) cursor.recycle();
        return null;
    }

    private static boolean isEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        String cls = safe(node.getClassName()).toLowerCase(Locale.ROOT);
        return cls.contains("edittext") || cls.contains("textfield");
    }

    private static String inferRole(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String cls = safe(node.getClassName()).toLowerCase(Locale.ROOT);
        if (isEditable(node)) return "text_field";
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

    private static String rawActionKey(AccessibilityNodeInfo node) {
        if (node == null) return "";
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        return safe(node.getViewIdResourceName()) + "|"
                + safe(node.getClassName()) + "|"
                + b.left + "," + b.top + "," + b.right + "," + b.bottom;
    }

    private static Candidate findCandidate(List<Candidate> candidates, String instanceId) {
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.snapshot != null
                    && candidate.snapshot.instanceId.equals(instanceId)) return candidate;
        }
        return null;
    }

    private static Match empty(UiLocatorScorer.Decision decision, String code) {
        return new Match(null, decision, 0.0, 0.0, 0, code, null, null);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

