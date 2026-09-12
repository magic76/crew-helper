package com.crewpocket.helper;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Runtime-owned generic app SEARCH transaction.
 *
 * Contract:
 * 1. prove the search field;
 * 2. type and verify the query;
 * 3. observe whether the app live-filters its result surface;
 * 4. dispatch IME Search/Enter when available;
 * 5. only claim results when the non-editor search surface actually changes.
 *
 * The query text changing by itself is never result evidence.
 */
final class AppSearchRuntime {
    private static final long SEARCH_FOCUS_TIMEOUT_MS = 1300L;
    private static final long SEARCH_FOCUS_POLL_MS = 120L;
    private static final long LIVE_RESULT_WAIT_MS = 360L;
    private static final long LIVE_RESULT_POLL_MS = 120L;

    private AppSearchRuntime() {}

    static JSONObject execute(CrewAccessibilityService service, String query) {
        if (service == null) return result(false, "ACCESSIBILITY_SERVICE_UNAVAILABLE");
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) return result(false, "EMPTY_QUERY");

        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo focused = null;
        AccessibilityNodeInfo search = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return result(false, "NO_ACTIVE_WINDOW");

            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            String beforeFocusedKey = editableKey(focused);

            if (isUsableEditable(focused) && isTrustedSearchEditable(focused)) {
                return enterAndSearch(service, text, beforeFocusedKey,
                        "EXISTING_SEARCH_FIELD");
            }

            search = findBestSearchControl(root);
            if (search == null) {
                return result(false, isUsableEditable(focused)
                        ? "FOCUSED_EDITABLE_NOT_PROVEN_SEARCH"
                        : "SEARCH_CONTROL_NOT_FOUND");
            }

            boolean clicked = search.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) return result(false, "SEARCH_CONTROL_CLICK_REJECTED");

            recycle(search);
            search = null;
            recycle(focused);
            focused = null;
            recycle(root);
            root = null;

            return awaitProvenSearchFocus(service, text, beforeFocusedKey);
        } catch (Exception error) {
            return result(false, "SEARCH_RUNTIME_EXCEPTION");
        } finally {
            recycle(search);
            recycle(focused);
            recycle(root);
        }
    }

    private static JSONObject awaitProvenSearchFocus(
            CrewAccessibilityService service,
            String text,
            String beforeFocusedKey) {
        long deadline = System.currentTimeMillis() + SEARCH_FOCUS_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline
                && !Thread.currentThread().isInterrupted()) {
            sleep(SEARCH_FOCUS_POLL_MS);

            AccessibilityNodeInfo root = null;
            AccessibilityNodeInfo focused = null;
            try {
                root = service.getRootInActiveWindow();
                if (root == null) continue;
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (!isUsableEditable(focused)) continue;

                String key = editableKey(focused);
                boolean trustedByMetadata = isTrustedSearchEditable(focused);
                boolean newlyFocused = !key.isEmpty()
                        && (beforeFocusedKey.isEmpty() || !key.equals(beforeFocusedKey));

                if (trustedByMetadata || newlyFocused) {
                    return enterAndSearch(
                            service,
                            text,
                            key,
                            trustedByMetadata
                                    ? "SEARCH_FIELD_METADATA"
                                    : "NEW_FOCUS_AFTER_SEARCH_CONTROL");
                }
            } finally {
                recycle(focused);
                recycle(root);
            }
        }

        JSONObject out = result(false, "SEARCH_FOCUS_NOT_PROVEN");
        try {
            out.put("state", "WAITING_FOR_PROVEN_SEARCH_FOCUS")
                    .put("instruction",
                            "Runtime 沒有取得可信的搜尋輸入框焦點，因此沒有輸入查詢文字。");
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject enterAndSearch(
            CrewAccessibilityService service,
            String text,
            String expectedFocusedKey,
            String proof) {
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo focused = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return result(false, "NO_ACTIVE_WINDOW_BEFORE_TYPE");
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (!isUsableEditable(focused)) {
                return result(false, "SEARCH_FOCUS_LOST_BEFORE_TYPE");
            }
            String currentKey = editableKey(focused);
            if (expectedFocusedKey != null && !expectedFocusedKey.isEmpty()
                    && !expectedFocusedKey.equals(currentKey)) {
                return result(false, "SEARCH_FOCUS_CHANGED_BEFORE_TYPE");
            }
        } finally {
            recycle(focused);
            recycle(root);
        }

        String beforeSurface = SearchSurfaceFingerprint.capture(service);

        if (!service.performSetTextVerified(text)) {
            JSONObject failed = result(false, "TEXT_NOT_VERIFIED");
            try {
                failed.put("instruction",
                        "文字沒有出現在已證明的搜尋輸入框；不要宣告搜尋成功。");
            } catch (Exception ignored) {}
            return failed;
        }

        SearchSurfaceFingerprint.Observation liveObservation =
                SearchSurfaceFingerprint.awaitChange(
                        service,
                        beforeSurface,
                        LIVE_RESULT_WAIT_MS,
                        LIVE_RESULT_POLL_MS);

        JSONObject commit;
        try {
            commit = SearchCommitRuntime.commit(service);
        } catch (Exception ignored) {
            commit = new JSONObject();
            try {
                commit.put("success", false)
                        .put("method", "NONE")
                        .put("error", "SEARCH_COMMIT_EXCEPTION")
                        .put("resultsObserved", false);
            } catch (Exception ignoredAgain) {}
        }

        boolean commitDispatched = commit.optBoolean("success", false);
        boolean postCommitSurfaceChanged =
                commit.optBoolean("resultsObserved", false);

        SearchTransactionPolicy.Decision decision =
                SearchTransactionPolicy.decide(
                        true,
                        liveObservation.changed,
                        commitDispatched,
                        postCommitSurfaceChanged);

        JSONObject out = new JSONObject();
        try {
            out.put("success", true)
                    .put("action", "APP_SEARCH")
                    .put("state", decision.state)
                    .put("focusProof", proof)
                    .put("textVerified", true)
                    .put("liveSurfaceChanged", liveObservation.changed)
                    .put("commitDispatched", commitDispatched)
                    .put("commitMethod", commit.optString("method", "NONE"))
                    .put("postCommitSurfaceChanged", postCommitSurfaceChanged)
                    .put("resultsObserved", decision.resultsObserved)
                    // From 0077 this means evidence-backed SEARCH completion.
                    .put("committed", decision.resultsObserved);

            if (!commitDispatched) {
                out.put("commitError",
                        commit.optString("error", "NO_SEARCH_COMMIT_ACTION"));
            }

            if (SearchTransactionPolicy.RESULTS_OBSERVED.equals(decision.state)) {
                out.put("resultEvidence", "SEARCH_SURFACE_CHANGED_AFTER_COMMIT");
            } else if (SearchTransactionPolicy.LIVE_RESULTS_OBSERVED.equals(decision.state)) {
                out.put("resultEvidence", "SEARCH_SURFACE_CHANGED_AFTER_TYPE");
            } else if (SearchTransactionPolicy.PENDING_RESULTS.equals(decision.state)) {
                out.put("resultEvidence", "COMMIT_DISPATCHED_RESULTS_NOT_YET_OBSERVED")
                        .put("message",
                                "搜尋提交已送出，但 Runtime 尚未看到結果畫面變化。");
            } else if (SearchTransactionPolicy.QUERY_ENTERED.equals(decision.state)) {
                out.put("resultEvidence", "QUERY_TYPED_NO_RESULT_EVIDENCE")
                        .put("message",
                                "搜尋文字已確認輸入，但尚未看到結果，且沒有可用的搜尋提交鍵。");
            }
        } catch (Exception ignored) {}
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
                    } finally {
                        clickable.recycle();
                    }
                }
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try { collect(child, best); }
                finally { child.recycle(); }
            }
        } catch (Exception ignored) {}
    }

    private static AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = null;
        try {
            cursor = AccessibilityNodeInfo.obtain(node);
            for (int i = 0; i < 6 && cursor != null; i++) {
                if (cursor.isClickable() && cursor.isEnabled()) {
                    return AccessibilityNodeInfo.obtain(cursor);
                }
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor.recycle();
                cursor = parent;
            }
        } catch (Exception ignored) {
        } finally {
            recycle(cursor);
        }
        return null;
    }

    private static boolean isUsableEditable(AccessibilityNodeInfo node) {
        return node != null
                && node.isVisibleToUser()
                && node.isEnabled()
                && (node.isEditable()
                || safe(node.getClassName()).toLowerCase(Locale.ROOT).contains("edittext"));
    }

    private static boolean isTrustedSearchEditable(AccessibilityNodeInfo node) {
        if (!isUsableEditable(node)) return false;
        if (ComposerSendResolver.isSearchInput(node)) return true;

        String hint = "";
        try {
            if (Build.VERSION.SDK_INT >= 26 && node.getHintText() != null) {
                hint = node.getHintText().toString();
            }
        } catch (Exception ignored) {}

        String meta = (safe(node.getText()) + " "
                + safe(node.getContentDescription()) + " "
                + safe(node.getViewIdResourceName()) + " "
                + hint).toLowerCase(Locale.ROOT);

        return containsAny(meta,
                "search", "query", "filter", "find", "lookup",
                "搜尋", "搜索", "查找", "找人", "聯絡人", "联系人");
    }

    private static String editableKey(AccessibilityNodeInfo node) {
        if (!isUsableEditable(node)) return "";
        Rect b = new Rect();
        try {
            node.getBoundsInScreen(b);
            return safe(node.getPackageName()) + "|"
                    + safe(node.getViewIdResourceName()) + "|"
                    + safe(node.getClassName()) + "|"
                    + b.left + "," + b.top + "," + b.right + "," + b.bottom;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int searchScore(AccessibilityNodeInfo node) {
        String meta = (safe(node.getText()) + " "
                + safe(node.getContentDescription()) + " "
                + safe(node.getViewIdResourceName())).toLowerCase(Locale.ROOT);
        if (containsAny(meta, "clear", "取消", "返回", "back", "close", "關閉", "关闭")) return 0;
        if (containsAny(meta, "搜尋", "搜索", "查找", "search")) return 200;
        if (containsAny(meta, "find", "lookup", "找人")) return 150;
        return 0;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && value.contains(needle)) return true;
        }
        return false;
    }

    private static JSONObject result(boolean success, String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", success)
                    .put("action", "APP_SEARCH")
                    .put("state", success
                            ? SearchTransactionPolicy.QUERY_ENTERED
                            : SearchTransactionPolicy.FAILED)
                    .put("resultsObserved", false)
                    .put("commitDispatched", false)
                    .put("committed", false);
            if (!success) out.put("error", error);
        } catch (Exception ignored) {}
        return out;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void recycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try { node.recycle(); } catch (Exception ignored) {}
        }
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        int score;
    }
}
