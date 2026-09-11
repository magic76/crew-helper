package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure scoring engine; Android Accessibility is only an adapter around this class. */
final class UiLocatorScorer {
    enum Decision {
        AUTO,
        RETRY_OBSERVE,
        FALLBACK,
        AMBIGUOUS,
        BLOCKED,
        NOT_FOUND
    }

    static final class Score {
        final UiNodeSnapshot node;
        final int points;
        final double confidence;
        final boolean blocked;
        final boolean exactElementId;
        final boolean exactViewId;
        final List<String> reasons;

        Score(UiNodeSnapshot node,
              int points,
              double confidence,
              boolean blocked,
              boolean exactElementId,
              boolean exactViewId,
              List<String> reasons) {
            this.node = node;
            this.points = points;
            this.confidence = confidence;
            this.blocked = blocked;
            this.exactElementId = exactElementId;
            this.exactViewId = exactViewId;
            this.reasons = reasons;
        }
    }

    static final class Ranking {
        final Decision decision;
        final Score best;
        final Score second;
        final String code;

        Ranking(Decision decision, Score best, Score second, String code) {
            this.decision = decision;
            this.best = best;
            this.second = second;
            this.code = code == null ? "" : code;
        }
    }

    private UiLocatorScorer() {}

    static Ranking rank(List<UiNodeSnapshot> nodes,
                        UiTargetSpec spec,
                        UiNodeSnapshot anchor) {
        if (spec == null || spec.isEmpty()) {
            return new Ranking(Decision.NOT_FOUND, null, null, "EMPTY_TARGET_SPEC");
        }
        if (nodes == null || nodes.isEmpty()) {
            return new Ranking(Decision.NOT_FOUND, null, null, "NO_UI_CANDIDATES");
        }

        // Several child labels can promote to the same clickable parent. Keep only
        // the strongest semantic description per physical action target.
        HashMap<String, Score> bestPerAction = new HashMap<String, Score>();
        Score strongestBlocked = null;
        for (UiNodeSnapshot node : nodes) {
            Score scored = score(node, spec, anchor);
            if (scored.points <= 0) continue;
            if (scored.blocked) {
                if (strongestBlocked == null || scored.points > strongestBlocked.points) {
                    strongestBlocked = scored;
                }
                continue;
            }
            String key = node.actionKey.isEmpty() ? node.instanceId : node.actionKey;
            Score previous = bestPerAction.get(key);
            if (previous == null || scored.points > previous.points) {
                bestPerAction.put(key, scored);
            }
        }

        Score best = null;
        Score second = null;
        for (Score scored : bestPerAction.values()) {
            if (best == null || scored.points > best.points) {
                second = best;
                best = scored;
            } else if (second == null || scored.points > second.points) {
                second = scored;
            }
        }

        if (best == null) {
            if (strongestBlocked != null && strongestBlocked.points >= 180) {
                return new Ranking(Decision.BLOCKED, strongestBlocked, null,
                        "TARGET_MATCHES_BLOCKED_NODE");
            }
            return new Ranking(Decision.NOT_FOUND, null, null, "NO_MATCHING_TARGET");
        }

        // A current semantic element id is the strongest possible structural proof.
        if (best.exactElementId && best.confidence >= 0.98) {
            return new Ranking(Decision.AUTO, best, second, "EXACT_CURRENT_ELEMENT_ID");
        }

        boolean ambiguous = second != null
                && second.confidence >= 0.70
                && (best.points - second.points) <= 55
                && !(best.exactViewId && !second.exactViewId);
        if (ambiguous) {
            return new Ranking(Decision.AMBIGUOUS, best, second, "TOP_CANDIDATES_TOO_CLOSE");
        }

        if (best.confidence >= 0.86) {
            return new Ranking(Decision.AUTO, best, second, "HIGH_CONFIDENCE");
        }
        if (best.confidence >= 0.68) {
            return new Ranking(Decision.RETRY_OBSERVE, best, second, "MEDIUM_CONFIDENCE");
        }
        if (best.confidence >= 0.50) {
            return new Ranking(Decision.FALLBACK, best, second, "LOW_CONFIDENCE");
        }
        return new Ranking(Decision.NOT_FOUND, best, second, "INSUFFICIENT_SIGNAL");
    }

    static Score score(UiNodeSnapshot node, UiTargetSpec spec, UiNodeSnapshot anchor) {
        ArrayList<String> reasons = new ArrayList<String>();
        if (node == null || spec == null) {
            return new Score(node, 0, 0.0, false, false, false, reasons);
        }

        int points = 0;
        boolean exactElementId = false;
        boolean exactViewId = false;
        boolean blocked = false;

        if (!node.visible || !node.enabled) {
            return new Score(node, 0, 0.0, false, false, false, reasons);
        }

        if (node.sensitive && spec.actionKind != UiTargetSpec.ActionKind.GENERIC) {
            blocked = true;
            reasons.add("sensitive");
        }

        if (spec.actionKind == UiTargetSpec.ActionKind.TAP && !node.clickable) {
            return new Score(node, 0, 0.0, blocked, false, false, reasons);
        }
        if (spec.actionKind == UiTargetSpec.ActionKind.TYPE && !node.editable) {
            return new Score(node, 0, 0.0, blocked, false, false, reasons);
        }

        String targetElement = norm(spec.elementId);
        String nodeElement = norm(node.elementId);
        if (!targetElement.isEmpty() && targetElement.equals(nodeElement)) {
            points += 1000;
            exactElementId = true;
            reasons.add("elementId_exact");
        }

        String targetId = norm(spec.viewId);
        String nodeId = norm(node.viewId);
        if (!targetId.isEmpty() && !nodeId.isEmpty()) {
            if (targetId.equals(nodeId)) {
                points += 420;
                exactViewId = true;
                reasons.add("viewId_exact");
            } else if (resourceTail(targetId).equals(resourceTail(nodeId))) {
                points += 350;
                reasons.add("viewId_tail");
            } else if (nodeId.contains(targetId) || targetId.contains(nodeId)) {
                points += 220;
                reasons.add("viewId_contains");
            }
        }

        String targetHint = canonicalHint(!spec.semanticHint.isEmpty()
                ? spec.semanticHint : inferHint(spec.label + " " + spec.viewId));
        String nodeHint = canonicalHint(!node.semanticHint.isEmpty()
                ? node.semanticHint : inferHint(node.label + " " + node.viewId));
        if (!targetHint.isEmpty() && !nodeHint.isEmpty()) {
            if (targetHint.equals(nodeHint)) {
                points += 300;
                reasons.add("semantic_exact");
            } else {
                points -= 35;
            }
        }

        String targetLabel = norm(spec.label);
        if (!targetLabel.isEmpty()) {
            int labelPoints = textMatchPoints(targetLabel, norm(node.label));
            int textPoints = textMatchPoints(targetLabel, norm(node.text));
            int descPoints = textMatchPoints(targetLabel, norm(node.contentDescription));
            int bestText = Math.max(labelPoints, Math.max(textPoints, descPoints));
            points += bestText;
            if (bestText >= 250) reasons.add("label_exact");
            else if (bestText >= 150) reasons.add("label_contains");
            else if (bestText > 0) reasons.add("label_tokens");
        }

        String targetRole = norm(spec.role);
        if (!targetRole.isEmpty()) {
            if (targetRole.equals(norm(node.role))) {
                points += 120;
                reasons.add("role_exact");
            } else {
                points -= 35;
            }
        }

        String targetParent = norm(spec.parentRole);
        if (!targetParent.isEmpty() && targetParent.equals(norm(node.parentRole))) {
            points += 60;
            reasons.add("parent_role");
        }

        if (spec.actionKind == UiTargetSpec.ActionKind.TAP) {
            points += 80;
            reasons.add("clickable");
        } else if (spec.actionKind == UiTargetSpec.ActionKind.TYPE && node.editable) {
            points += 160;
            reasons.add("editable");
        }

        if (anchor != null && !spec.relation.isEmpty()) {
            if (relationMatches(node, anchor, spec.relation)) {
                points += 150;
                reasons.add("anchor_relation");
            } else {
                points -= 60;
            }
        }

        if (!spec.region.isEmpty()) {
            if (regionMatches(node, spec.region)) {
                points += 45;
                reasons.add("region");
            } else {
                points -= 15;
            }
        }

        // Huge containers are weak tap targets even when their descendants have labels.
        if (node.width() > node.screenWidth * 0.88
                && node.height() > node.screenHeight * 0.45) {
            points -= 45;
        }

        // Prefer shallower controls only as a tie-breaker, never as semantics.
        points += Math.max(0, 16 - Math.min(16, node.depth));

        double confidence = confidence(points, exactElementId, exactViewId);
        return new Score(node, points, confidence, blocked,
                exactElementId, exactViewId, reasons);
    }

    private static int textMatchPoints(String target, String value) {
        if (target.isEmpty() || value.isEmpty()) return 0;
        if (target.equals(value)) return 270;
        if (value.contains(target) || target.contains(value)) return 170;
        double similarity = tokenSimilarity(target, value);
        if (similarity >= 0.80) return 145;
        if (similarity >= 0.55) return 100;
        if (similarity >= 0.35) return 60;
        return 0;
    }

    private static double tokenSimilarity(String a, String b) {
        Set<String> left = tokens(a);
        Set<String> right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        int intersection = 0;
        for (String token : left) if (right.contains(token)) intersection++;
        int union = left.size() + right.size() - intersection;
        return union <= 0 ? 0.0 : intersection / (double) union;
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String[] split = value.split("[^\\p{L}\\p{N}]+");
        for (String token : split) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    private static double confidence(int points, boolean exactElementId, boolean exactViewId) {
        if (exactElementId) return 0.995;
        if (points >= 760) return 0.985;
        if (points >= 620) return 0.965;
        if (points >= 500) return 0.94;
        if (points >= 420) return 0.90;
        if (points >= 340) return exactViewId ? 0.90 : 0.85;
        if (points >= 270) return 0.78;
        if (points >= 200) return 0.69;
        if (points >= 140) return 0.58;
        if (points >= 90) return 0.48;
        return Math.max(0.0, points / 200.0 * 0.45);
    }

    private static boolean relationMatches(UiNodeSnapshot node,
                                           UiNodeSnapshot anchor,
                                           String relation) {
        String r = relation == null ? "" : relation.toUpperCase(Locale.ROOT);
        int dx = node.centerX() - anchor.centerX();
        int dy = node.centerY() - anchor.centerY();
        int nearX = Math.max(96, anchor.width() * 2);
        int nearY = Math.max(96, anchor.height() * 2);
        if ("RIGHT_OF".equals(r) || "RIGHT_OF_REFERENCE".equals(r)) {
            return node.centerX() >= anchor.right && Math.abs(dy) <= nearY;
        }
        if ("LEFT_OF".equals(r) || "LEFT_OF_REFERENCE".equals(r)) {
            return node.centerX() <= anchor.left && Math.abs(dy) <= nearY;
        }
        if ("ABOVE".equals(r) || "ABOVE_REFERENCE".equals(r)) {
            return node.centerY() <= anchor.top && Math.abs(dx) <= nearX;
        }
        if ("BELOW".equals(r) || "BELOW_REFERENCE".equals(r)) {
            return node.centerY() >= anchor.bottom && Math.abs(dx) <= nearX;
        }
        if ("NEAR".equals(r)) {
            return Math.abs(dx) <= nearX && Math.abs(dy) <= nearY;
        }
        if ("OVERLAPS".equals(r) || "OVERLAPS_REFERENCE".equals(r)) {
            return node.left < anchor.right && node.right > anchor.left
                    && node.top < anchor.bottom && node.bottom > anchor.top;
        }
        return false;
    }

    private static boolean regionMatches(UiNodeSnapshot node, String region) {
        String r = region == null ? "" : region.toUpperCase(Locale.ROOT);
        double x = node.centerX() / (double) Math.max(1, node.screenWidth);
        double y = node.centerY() / (double) Math.max(1, node.screenHeight);
        if ("TOP".equals(r)) return y <= 0.35;
        if ("BOTTOM".equals(r)) return y >= 0.65;
        if ("LEFT".equals(r)) return x <= 0.35;
        if ("RIGHT".equals(r)) return x >= 0.65;
        if ("TOP_LEFT".equals(r)) return x <= 0.45 && y <= 0.45;
        if ("TOP_RIGHT".equals(r)) return x >= 0.55 && y <= 0.45;
        if ("BOTTOM_LEFT".equals(r)) return x <= 0.45 && y >= 0.55;
        if ("BOTTOM_RIGHT".equals(r)) return x >= 0.55 && y >= 0.55;
        if ("CENTER".equals(r)) return x >= 0.30 && x <= 0.70 && y >= 0.25 && y <= 0.75;
        return false;
    }

    static String inferHint(String value) {
        String h = norm(value);
        if (containsAny(h, "search", "find", "搜尋", "搜索", "查找")) return "search";
        if (containsAny(h, "send", "submit", "發送", "送出", "傳送", "发送", "提交")) return "send";
        if (containsAny(h, "back", "返回", "上一頁", "上一页")) return "back";
        if (containsAny(h, "close", "cancel", "取消", "關閉", "关闭")) return "close";
        if (containsAny(h, "more", "menu", "更多", "選單", "菜单")) return "more";
        if (containsAny(h, "add", "新增", "加入", "添加")) return "add";
        if (containsAny(h, "next", "下一步", "繼續", "继续")) return "next";
        if (containsAny(h, "confirm", "done", "確定", "确认", "完成")) return "confirm";
        return "";
    }

    private static String canonicalHint(String value) {
        String inferred = inferHint(value);
        return inferred.isEmpty() ? norm(value) : inferred;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(norm(needle))) return true;
        return false;
    }

    private static String resourceTail(String value) {
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private static String norm(String value) {
        return UiTargetSpec.normalizeText(value);
    }
}

