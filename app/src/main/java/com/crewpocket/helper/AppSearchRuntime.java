package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Runtime-owned app search transaction. The model supplies only a query; this
 * class obtains search focus, writes verified text, then attempts the IME
 * search action. It intentionally never guesses a result row.
 */
final class AppSearchRuntime {
    private AppSearchRuntime() {}

    static JSONObject execute(CrewAccessibilityService service, String query) {
        if (service == null) return result(false, "ACCESSIBILITY_SERVICE_UNAVAILABLE");
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) return result(false, "EMPTY_QUERY");

        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return result(false, "NO_ACTIVE_WINDOW");

            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            try {
                if (isUsableEditable(focused)) {
                    return enterAndCommit(service, text);
                }
            } finally {
                if (focused != null) focused.recycle();
            }

            AccessibilityNodeInfo search = findBestSearchControl(root);
            if (search == null) return result(false, "SEARCH_CONTROL_NOT_FOUND");
            try {
                boolean clicked = search.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!clicked) return result(false, "SEARCH_CONTROL_CLICK_REJECTED");
                return new JSONObject().put("success", true)
                        .put("action", "APP_SEARCH")
                        .put("state", "WAITING_FOR_FOCUS")
                        .put("message", "搜尋入口已點擊，正在等待輸入框焦點。");
            } finally {
                search.recycle();
            }
        } catch (Exception error) {
            return result(false, "SEARCH_RUNTIME_EXCEPTION");
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static JSONObject enterAndCommit(CrewAccessibilityService service, String text) {
        if (!service.performSetTextVerified(text)) {
            JSONObject failed = result(false, "TEXT_NOT_VERIFIED");
            try { failed.put("instruction", "文字沒有出現在目前焦點輸入框；不要宣告搜尋成功。"); }
            catch (Exception ignored) {}
            return failed;
        }
        JSONObject out = new JSONObject();
        try {
            JSONObject commit = SearchCommitRuntime.commit(service);
            out.put("success", true).put("action", "APP_SEARCH")
                    .put("state", "QUERY_ENTERED")
                    .put("textVerified", true)
                    .put("committed", commit.optBoolean("success", false))
                    .put("commitMethod", commit.optString("method", "NONE"));
            if (!commit.optBoolean("success", false)) {
                out.put("message", "搜尋文字已確認輸入；此 App 尚未提供可用的搜尋提交鍵。");
            }
        } catch (Exception ignored) {
            try { out.put("success", true).put("action", "APP_SEARCH")
                    .put("state", "QUERY_ENTERED").put("textVerified", true)
                    .put("committed", false); } catch (Exception ignoredAgain) {}
        }
        return out;
    }

    private static AccessibilityNodeInfo findBestSearchControl(AccessibilityNodeInfo root) {
        Candidate best = new Candidate();
        collect(root, best);
        return best.node;
    }

    private static void collect(AccessibilityNodeInfo node, Candidate best) {
        if (node == null) return;
        try {
            if (node.isVisibleToUser() && node.isEnabled()) {
                AccessibilityNodeInfo clickable = nearestClickable(node);
                if (clickable != null) {
                    try {
                        int score = searchScore(clickable);
                        Rect bounds = new Rect();
                        clickable.getBoundsInScreen(bounds);
                        if (score > best.score && bounds.width() > 24 && bounds.height() > 24) {
                            if (best.node != null) best.node.recycle();
                            best.node = AccessibilityNodeInfo.obtain(clickable);
                            best.score = score;
                        }
                    } finally { clickable.recycle(); }
                }
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try { collect(child, best); } finally { child.recycle(); }
            }
        } catch (Exception ignored) {}
    }

    private static AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = null;
        try {
            cursor = AccessibilityNodeInfo.obtain(node);
            for (int i = 0; i < 6 && cursor != null; i++) {
                if (cursor.isClickable() && cursor.isEnabled()) return AccessibilityNodeInfo.obtain(cursor);
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor.recycle();
                cursor = parent;
            }
        } catch (Exception ignored) {
        } finally { if (cursor != null) cursor.recycle(); }
        return null;
    }

    private static boolean isUsableEditable(AccessibilityNodeInfo node) {
        return node != null && node.isVisibleToUser() && node.isEnabled()
                && (node.isEditable() || String.valueOf(node.getClassName()).toLowerCase(Locale.ROOT).contains("edittext"));
    }

    private static int searchScore(AccessibilityNodeInfo node) {
        String meta = (safe(node.getText()) + " " + safe(node.getContentDescription()) + " "
                + safe(node.getViewIdResourceName())).toLowerCase(Locale.ROOT);
        if (meta.contains("clear") || meta.contains("取消") || meta.contains("返回") || meta.contains("back")) return 0;
        if (meta.contains("搜尋") || meta.contains("搜索") || meta.contains("查找") || meta.contains("search")) return 200;
        if (meta.contains("find") || meta.contains("lookup")) return 150;
        return 0;
    }

    private static JSONObject result(boolean success, String error) {
        JSONObject out = new JSONObject();
        try { out.put("success", success).put("action", "APP_SEARCH");
            if (!success) out.put("error", error); } catch (Exception ignored) {}
        return out;
    }

    private static String safe(CharSequence value) { return value == null ? "" : value.toString(); }
    private static final class Candidate { AccessibilityNodeInfo node; int score; }
}
