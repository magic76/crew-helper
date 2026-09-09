package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0037 narrow search-result selection detector.
 *
 * Deliberately supports Google Maps only in v1. Generic TAP failures must never
 * become human-choice cards. Candidate labels come from visible node TEXT, not
 * accessibility helper descriptions.
 */
final class SearchResultSelectionRuntime {
    static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    private static final int MAX_OPTIONS = 4;

    private SearchResultSelectionRuntime() {}

    static JSONObject analyze(CrewAccessibilityService service, String query) {
        if (service == null) return state("UNAVAILABLE", "ACCESSIBILITY_SERVICE_UNAVAILABLE");

        AccessibilityNodeInfo root = null;
        try {
            // Resolve the foreground app BEFORE interpreting the IME. Otherwise a
            // non-Maps search with a visible keyboard would be accidentally held
            // inside the Maps-only state machine.
            root = findMapsRoot(service);
            if (root == null) {
                AccessibilityNodeInfo active = null;
                try {
                    active = service.getRootInActiveWindow();
                    String pkg = active == null ? "" : safe(active.getPackageName());
                    return state("UNSUPPORTED", "PACKAGE_NOT_SUPPORTED")
                            .put("package", pkg);
                } finally {
                    if (active != null) active.recycle();
                }
            }

            String pkg = safe(root.getPackageName());
            if (isImeVisible(service)) {
                return state("WAITING_RESULTS", "IME_VISIBLE")
                        .put("package", pkg);
            }

            int screenWidth = service.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = service.getResources().getDisplayMetrics().heightPixels;
            float density = service.getResources().getDisplayMetrics().density;

            LinkedHashMap<String, Row> grouped = new LinkedHashMap<String, Row>();
            collect(root, -1, 0, query, screenWidth, screenHeight, density, grouped);

            ArrayList<Row> raw = new ArrayList<Row>(grouped.values());
            ArrayList<Row> filtered = new ArrayList<Row>();
            boolean repeatedWideRows = countWideRows(raw, screenWidth) >= 2;

            for (Row row : raw) {
                if (row.suggestionLike) continue;
                if (!row.listLike && !row.exact && !repeatedWideRows) continue;
                if (row.label.isEmpty()) continue;
                filtered.add(row);
            }

            Collections.sort(filtered, new Comparator<Row>() {
                @Override public int compare(Row a, Row b) {
                    if (a.top != b.top) return a.top - b.top;
                    return a.left - b.left;
                }
            });

            // De-duplicate visual rows with the same human title.
            ArrayList<Row> unique = new ArrayList<Row>();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (Row row : filtered) {
                String key = normalize(row.label);
                if (key.isEmpty() || !seen.add(key)) continue;
                unique.add(row);
                if (unique.size() >= MAX_OPTIONS) break;
            }

            if (unique.isEmpty()) {
                return state("WAITING_RESULTS", "NO_RESULT_ROWS_YET")
                        .put("package", pkg);
            }

            JSONArray options = new JSONArray();
            for (Row row : unique) {
                options.put(new JSONObject()
                        .put("elementId", row.elementId)
                        .put("label", row.label)
                        .put("rowSignature", row.rowSignature)
                        .put("exactMatch", row.exact)
                        .put("strongMatch", row.strong));
            }

            return state("READY", "")
                    .put("package", pkg)
                    .put("candidateCount", options.length())
                    .put("options", options);
        } catch (Exception error) {
            return state("WAITING_RESULTS", "ANALYZE_ERROR");
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static void collect(AccessibilityNodeInfo node,
                                int siblingIndex,
                                int depth,
                                String query,
                                int screenWidth,
                                int screenHeight,
                                float density,
                                Map<String, Row> grouped) {
        if (node == null) return;

        try {
            if (node.isVisibleToUser()
                    && node.isEnabled()
                    && !node.isEditable()
                    && !SensitiveDataGuard.isSensitiveNode(node)
                    && !hasEditableAncestor(node)) {
                String text = safe(node.getText()).trim();

                if (isHumanTitle(text)) {
                    AccessibilityNodeInfo clickable = null;
                    try {
                        clickable = SemanticElementResolver.nearestClickable(node);
                        if (clickable != null
                                && !SensitiveDataGuard.isBlockedAction(clickable)
                                && !clickable.isEditable()) {
                            Rect bounds = new Rect();
                            clickable.getBoundsInScreen(bounds);

                            int minHeight = Math.max(1, Math.round(34f * density));
                            int minWidth = Math.max(Math.round(120f * density),
                                    Math.round(screenWidth * 0.28f));
                            int topGuard = Math.round(64f * density);

                            if (bounds.height() >= minHeight
                                    && bounds.width() >= minWidth
                                    && bounds.bottom > topGuard
                                    && bounds.top < screenHeight) {
                                String rowSignature = rowSignature(clickable, bounds);
                                boolean listLike = hasListLikeAncestor(clickable);
                                boolean suggestionLike = isSuggestionLike(clickable);

                                int titleScore = titleScore(text, query);
                                Row existing = grouped.get(rowSignature);
                                if (existing == null) {
                                    existing = new Row();
                                    existing.rowSignature = rowSignature;
                                    existing.top = bounds.top;
                                    existing.left = bounds.left;
                                    existing.width = bounds.width();
                                    existing.listLike = listLike;
                                    existing.suggestionLike = suggestionLike;
                                    grouped.put(rowSignature, existing);
                                } else {
                                    existing.listLike |= listLike;
                                    existing.suggestionLike |= suggestionLike;
                                }

                                if (titleScore > existing.titleScore) {
                                    existing.titleScore = titleScore;
                                    existing.label = text;
                                    existing.elementId =
                                            SemanticScreenState.elementId(node, siblingIndex, depth);
                                    Match match = match(text, query);
                                    existing.exact = match.exact;
                                    existing.strong = match.strong;
                                }
                            }
                        }
                    } finally {
                        if (clickable != null) clickable.recycle();
                    }
                }
            }
        } catch (Exception ignored) {}

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collect(child, i, depth + 1, query,
                        screenWidth, screenHeight, density, grouped);
            } finally {
                child.recycle();
            }
        }
    }

    private static AccessibilityNodeInfo findMapsRoot(
            CrewAccessibilityService service) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null
                            || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue;
                    }
                    AccessibilityNodeInfo candidate = null;
                    try {
                        candidate = window.getRoot();
                        if (candidate != null
                                && MAPS_PACKAGE.equals(safe(candidate.getPackageName()))) {
                            return AccessibilityNodeInfo.obtain(candidate);
                        }
                    } finally {
                        if (candidate != null) candidate.recycle();
                    }
                }
            }
        } catch (Exception ignored) {}

        AccessibilityNodeInfo active = null;
        try {
            active = service.getRootInActiveWindow();
            if (active != null
                    && MAPS_PACKAGE.equals(safe(active.getPackageName()))) {
                return AccessibilityNodeInfo.obtain(active);
            }
        } catch (Exception ignored) {
        } finally {
            if (active != null) active.recycle();
        }
        return null;
    }

    private static boolean isImeVisible(CrewAccessibilityService service) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null
                        || window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }
                AccessibilityNodeInfo imeRoot = null;
                try {
                    imeRoot = window.getRoot();
                    if (imeRoot != null && imeRoot.isVisibleToUser()) return true;
                } catch (Exception ignored) {
                } finally {
                    if (imeRoot != null) imeRoot.recycle();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean hasEditableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = null;
        try {
            cursor = AccessibilityNodeInfo.obtain(node);
            for (int i = 0; i < 6 && cursor != null; i++) {
                if (cursor.isEditable()) return true;
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor.recycle();
                cursor = parent;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.recycle();
        }
    }

    private static boolean hasListLikeAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = null;
        try {
            cursor = AccessibilityNodeInfo.obtain(node);
            for (int i = 0; i < 6 && cursor != null; i++) {
                String cls = safe(cursor.getClassName()).toLowerCase(Locale.ROOT);
                if (cursor.isScrollable()
                        || cls.contains("recyclerview")
                        || cls.contains("listview")
                        || cls.contains("scrollview")
                        || cls.contains("lazycolumn")
                        || cls.contains("collection")) {
                    return true;
                }
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor.recycle();
                cursor = parent;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.recycle();
        }
        return false;
    }

    private static boolean isSuggestionLike(AccessibilityNodeInfo clickable) {
        String desc = safe(clickable.getContentDescription()).toLowerCase(Locale.ROOT);
        String id = safe(clickable.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String meta = desc + " " + id;
        return meta.contains("suggestion")
                || meta.contains("autocomplete")
                || meta.contains("search bar suggestion")
                || meta.contains("搜尋建議")
                || meta.contains("搜索建议")
                || meta.contains("在搜尋列輸入")
                || meta.contains("在搜索栏输入");
    }

    private static boolean isHumanTitle(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 2 || t.length() > 90) return false;

        String n = normalize(t);
        if (n.isEmpty()) return false;

        if (n.equals("search") || n.equals("搜尋") || n.equals("搜索")
                || n.equals("back") || n.equals("返回")
                || n.equals("clear") || n.equals("清除")
                || n.equals("filter") || n.equals("篩選") || n.equals("筛选")
                || n.equals("cancel") || n.equals("取消")
                || n.equals("directions") || n.equals("路線") || n.equals("路线")
                || n.equals("start") || n.equals("開始") || n.equals("开始")) {
            return false;
        }

        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("double tap")
                || lower.contains("tap to activate")
                || lower.contains("enable to")
                || lower.contains("search bar suggestion")
                || lower.contains("啟用即可")
                || lower.contains("启用即可")
                || lower.contains("在搜尋列輸入建議")
                || lower.contains("在搜索栏输入建议")) {
            return false;
        }

        // Ratings, distances, durations and isolated counters are supporting text.
        if (n.matches("[0-9.,★⭐]+")
                || n.matches("[0-9.,]+(km|m|mi|公里|公尺|米)")
                || n.matches("[0-9.,]+(min|mins|hr|hrs|分鐘|分钟|小時|小时)")) {
            return false;
        }
        return true;
    }

    private static int titleScore(String label, String query) {
        Match m = match(label, query);
        int score = m.exact ? 1000 : (m.strong ? 650 : 100);
        int length = label == null ? 0 : label.trim().length();
        if (length >= 3 && length <= 55) score += 40;
        return score;
    }

    private static Match match(String label, String query) {
        String l = normalize(label);
        String q = normalize(query);
        if (l.isEmpty() || q.isEmpty()) return new Match(false, false);

        boolean exact = l.equals(q);
        if (exact) return new Match(true, true);

        boolean contains = l.contains(q) || q.contains(l);
        if (!contains) return new Match(false, false);

        int min = Math.min(l.length(), q.length());
        int max = Math.max(l.length(), q.length());
        boolean strong = max > 0 && ((double) min / (double) max) >= 0.65d;
        return new Match(false, strong);
    }

    private static int countWideRows(List<Row> rows, int screenWidth) {
        int count = 0;
        for (Row row : rows) {
            if (row.width >= Math.round(screenWidth * 0.38f)) count++;
        }
        return count;
    }

    private static String rowSignature(AccessibilityNodeInfo node, Rect bounds) {
        return safe(node.getClassName()) + "|"
                + safe(node.getViewIdResourceName()) + "|"
                + bounds.left + "," + bounds.top + ","
                + bounds.right + "," + bounds.bottom;
    }

    private static JSONObject state(String value, String reason) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", true)
                    .put("state", value == null ? "" : value);
            if (reason != null && !reason.isEmpty()) out.put("reason", reason);
        } catch (Exception ignored) {}
        return out;
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\"'：:；;（）()\\-_/]+", "")
                .trim();
    }

    private static final class Match {
        final boolean exact;
        final boolean strong;
        Match(boolean exact, boolean strong) {
            this.exact = exact;
            this.strong = strong;
        }
    }

    private static final class Row {
        String elementId = "";
        String label = "";
        String rowSignature = "";
        int titleScore = Integer.MIN_VALUE;
        int top;
        int left;
        int width;
        boolean listLike;
        boolean suggestionLike;
        boolean exact;
        boolean strong;
    }
}
