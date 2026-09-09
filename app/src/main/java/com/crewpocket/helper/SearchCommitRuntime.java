package com.crewpocket.helper;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0036 Search Transaction commit step.
 *
 * Only submits when Runtime proves the focused editable is a search input.
 * Never clicks an app suggestion/result because search != open.
 */
final class SearchCommitRuntime {
    private SearchCommitRuntime() {}

    static JSONObject commit(CrewAccessibilityService service) {
        if (service == null) {
            return result(false, "NONE", "ACCESSIBILITY_SERVICE_UNAVAILABLE");
        }

        AccessibilityNodeInfo searchInput = findFocusedSearchInput(service);
        if (searchInput == null) {
            return result(false, "NONE", "NO_FOCUSED_SEARCH_INPUT");
        }

        try {
            if (SensitiveDataGuard.isHardBlockedInput(searchInput)) {
                return result(false, "NONE", "SENSITIVE_INPUT_BLOCKED");
            }
            if (!ComposerSendResolver.isSearchInput(searchInput)) {
                return result(false, "NONE", "FOCUSED_INPUT_IS_NOT_SEARCH");
            }

            if (performImeEnter(searchInput)) {
                return result(true, "ACTION_IME_ENTER", "");
            }
        } finally {
            searchInput.recycle();
        }

        // Fallback only to the IME's own semantic action key.
        // Never click an app suggestion/result as a substitute.
        if (clickImeCommitButton(service)) {
            return result(true, "IME_ACTION_BUTTON", "");
        }

        return result(false, "NONE", "NO_SEARCH_COMMIT_ACTION");
    }

    private static AccessibilityNodeInfo findFocusedSearchInput(
            CrewAccessibilityService service) {
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            AccessibilityNodeInfo found = findSearchInputInRoot(service, root);
            if (found != null) return found;
        } catch (Exception ignored) {
        } finally {
            if (root != null) root.recycle();
        }

        // The IME may be the active window while the app's focused search field
        // lives in another interactive window.
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null
                            || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue;
                    }
                    AccessibilityNodeInfo windowRoot = null;
                    try {
                        windowRoot = window.getRoot();
                        AccessibilityNodeInfo found =
                                findSearchInputInRoot(service, windowRoot);
                        if (found != null) return found;
                    } catch (Exception ignored) {
                    } finally {
                        if (windowRoot != null) windowRoot.recycle();
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static AccessibilityNodeInfo findSearchInputInRoot(
            CrewAccessibilityService service, AccessibilityNodeInfo root) {
        if (root == null) return null;

        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null
                    && focused.isEditable()
                    && focused.isEnabled()
                    && !SensitiveDataGuard.isHardBlockedInput(focused)
                    && ComposerSendResolver.isSearchInput(focused)) {
                return AccessibilityNodeInfo.obtain(focused);
            }
        } catch (Exception ignored) {
        } finally {
            if (focused != null) focused.recycle();
        }

        AccessibilityNodeInfo active = null;
        try {
            active = service.findActiveEditText(root);
            if (active != null
                    && active.isEditable()
                    && active.isEnabled()
                    && !SensitiveDataGuard.isHardBlockedInput(active)
                    && ComposerSendResolver.isSearchInput(active)) {
                return AccessibilityNodeInfo.obtain(active);
            }
        } catch (Exception ignored) {
        } finally {
            if (active != null) active.recycle();
        }

        return null;
    }

    /**
     * ACTION_IME_ENTER is newer than the project's API-24 compile surface.
     * Reflection keeps low compile-SDK compatibility on modern Android.
     */
    private static boolean performImeEnter(AccessibilityNodeInfo input) {
        if (input == null) return false;
        try {
            Class<?> actionClass = Class.forName(
                    "android.view.accessibility.AccessibilityNodeInfo$AccessibilityAction");
            Object action = actionClass.getField("ACTION_IME_ENTER").get(null);
            Method getId = actionClass.getMethod("getId");
            Object rawId = getId.invoke(action);
            if (!(rawId instanceof Integer)) return false;
            return input.performAction(((Integer) rawId).intValue());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean clickImeCommitButton(CrewAccessibilityService service) {
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return false;

            for (AccessibilityWindowInfo window : windows) {
                if (window == null
                        || window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }

                AccessibilityNodeInfo root = null;
                ArrayList<AccessibilityNodeInfo> clickable =
                        new ArrayList<AccessibilityNodeInfo>();
                try {
                    root = window.getRoot();
                    collectClickable(root, clickable);
                    for (AccessibilityNodeInfo node : clickable) {
                        int score = imeCommitScore(node);
                        if (score > bestScore) {
                            if (best != null) best.recycle();
                            best = AccessibilityNodeInfo.obtain(node);
                            bestScore = score;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    for (AccessibilityNodeInfo node : clickable) {
                        if (node != null) node.recycle();
                    }
                    if (root != null) root.recycle();
                }
            }

            if (best == null || bestScore < 90) return false;
            return best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (best != null) best.recycle();
        }
    }

    private static int imeCommitScore(AccessibilityNodeInfo node) {
        if (node == null || !node.isClickable() || !node.isEnabled()) return 0;

        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null
                ? "" : node.getContentDescription().toString();
        String id = node.getViewIdResourceName() == null
                ? "" : node.getViewIdResourceName().toString();

        String meta = normalize(text + " " + desc + " " + id);
        if (meta.isEmpty()) return 0;

        // Search transaction can never reuse a message-send control.
        if (containsAny(meta,
                "send", "message", "composer", "傳送", "传送",
                "發送", "发送", "送出")) {
            return 0;
        }

        if (equalsAny(meta,
                "search", "搜尋", "搜索", "查找", "搜尋鍵", "搜索键")) {
            return 220;
        }
        if (containsAny(meta, "search", "搜尋", "搜索", "查找")) {
            return 180;
        }
        if (containsAny(meta, "imeaction", "ime_action")) {
            return 120;
        }
        if (equalsAny(meta,
                "go", "enter", "done", "前往", "確定", "确定", "完成")) {
            return 100;
        }
        return 0;
    }

    private static void collectClickable(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable() && node.isEnabled()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectClickable(child, out);
            } finally {
                child.recycle();
            }
        }
    }

    private static JSONObject result(
            boolean success, String method, String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", success)
                    .put("action", "SEARCH_COMMIT")
                    .put("method", method == null ? "NONE" : method);
            if (!success && error != null && !error.isEmpty()) {
                out.put("error", error);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (value.contains(normalize(needle))) return true;
        }
        return false;
    }

    private static boolean equalsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (value.equals(normalize(needle))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[\\s，,。！？!：:；;（）()_\\-]+", "");
    }
}
