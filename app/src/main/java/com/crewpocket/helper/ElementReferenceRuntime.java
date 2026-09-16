package com.crewpocket.helper;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime-owned clickable-element reference mode.
 *
 * Accessibility owns WHAT is actually clickable and exposes stable element ids.
 * Gemini only forwards the user's numbered choice; coordinates are not involved.
 */
final class ElementReferenceRuntime {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SESSION_TTL_MS = 30_000L;
    private static final int MAX_OPTIONS = 24;

    static final class Decision {
        final boolean selected;
        final String elementId;

        private Decision(boolean selected, String elementId) {
            this.selected = selected;
            this.elementId = elementId == null ? "" : elementId;
        }

        static Decision none() { return new Decision(false, ""); }
        static Decision selected(String elementId) { return new Decision(true, elementId); }
    }

    static final class Option {
        final int number;
        final String elementId;
        final String label;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Option(int number, ElementReferenceLayout.Item item) {
            this.number = number;
            this.elementId = item == null ? "" : item.id;
            this.label = item == null ? "" : item.label;
            this.left = item == null ? 0 : item.left;
            this.top = item == null ? 0 : item.top;
            this.right = item == null ? 0 : item.right;
            this.bottom = item == null ? 0 : item.bottom;
        }

        String displayLabel() {
            String text = label == null ? "" : label.replaceAll("\\s+", " ").trim();
            if (text.length() > 34) text = text.substring(0, 34);
            return text.isEmpty() ? String.valueOf(number) : number + ". " + text;
        }
    }

    private static final class Session {
        final long id;
        final String packageName;
        final int width;
        final int height;
        final long expiresAt;
        final List<Option> options;

        Session(long id, String packageName, int width, int height, List<Option> options) {
            this.id = id;
            this.packageName = packageName == null ? "" : packageName;
            this.width = width;
            this.height = height;
            this.options = options == null ? new ArrayList<Option>() : options;
            this.expiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
        }

        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static Session active;

    private ElementReferenceRuntime() {}

    static JSONObject startExplicit() {
        final CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        if (service == null) {
            return failure("ELEMENT_REFERENCE_UNAVAILABLE",
                    "螢幕操作服務目前不可用，無法顯示可點擊元素。");
        }
        if (!Settings.canDrawOverlays(service)) {
            return failure("ELEMENT_REFERENCE_OVERLAY_PERMISSION_REQUIRED",
                    "需要懸浮視窗權限才能在目前 App 上標示可點擊元素。");
        }

        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) {
                return failure("ELEMENT_REFERENCE_UNAVAILABLE",
                        "目前沒有可讀取的 Accessibility 畫面。");
            }

            String packageName = root.getPackageName().toString();
            if (unsafePackage(packageName) || containsSensitiveNode(root)) {
                return failure("ELEMENT_REFERENCE_SENSITIVE_SCREEN",
                        "目前畫面可能包含敏感資訊，不顯示元素標記。");
            }

            int width = service.getResources().getDisplayMetrics().widthPixels;
            int height = service.getResources().getDisplayMetrics().heightPixels;
            if (width <= 1 || height <= 1) {
                return failure("ELEMENT_REFERENCE_UNAVAILABLE", "無法取得目前螢幕尺寸。");
            }

            JSONObject screen = SemanticScreenState.capture(root);
            JSONArray elements = screen.optJSONArray("elements");
            ArrayList<ElementReferenceLayout.Item> raw = new ArrayList<ElementReferenceLayout.Item>();
            if (elements != null) {
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i);
                    if (element == null
                            || !element.optBoolean("clickable", false)
                            || !element.optBoolean("enabled", true)
                            || element.optBoolean("sensitive", false)) {
                        continue;
                    }
                    JSONObject bounds = element.optJSONObject("bounds");
                    if (bounds == null) continue;
                    String id = element.optString("id", "").trim();
                    String label = firstNonEmpty(
                            element.optString("label", ""),
                            element.optString("semanticHint", ""),
                            roleLabel(element.optString("role", "")));
                    raw.add(new ElementReferenceLayout.Item(
                            id,
                            label,
                            bounds.optInt("left", 0),
                            bounds.optInt("top", 0),
                            bounds.optInt("right", 0),
                            bounds.optInt("bottom", 0),
                            element.optInt("depth", 0)));
                }
            }

            List<ElementReferenceLayout.Item> selected =
                    ElementReferenceLayout.select(raw, width, height, MAX_OPTIONS);
            if (selected.isEmpty()) {
                return failure("NO_CLICKABLE_ELEMENTS",
                        "目前 Accessibility 沒有找到可安全標示的可點擊元素；可以改說「顯示方格」。");
            }

            ArrayList<Option> options = new ArrayList<Option>();
            for (int i = 0; i < selected.size(); i++) {
                options.add(new Option(i + 1, selected.get(i)));
            }

            final Session session = new Session(
                    System.nanoTime(), packageName, width, height, options);
            synchronized (LOCK) {
                clearLocked();
                active = session;
            }

            ElementReferenceOverlay.show(
                    service,
                    options,
                    options.size() >= MAX_OPTIONS
                            ? "可點擊元素 · 已顯示前 " + MAX_OPTIONS + " 個 · 說編號"
                            : "可點擊元素 · 說編號");
            scheduleExpiry(session.id);

            JSONObject response = new JSONObject();
            response.put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("blockedByRuntime", true)
                    .put("error", "ELEMENT_REFERENCE_WAITING")
                    .put("taskState", "WAITING_USER")
                    .put("visualReference", "ELEMENTS")
                    .put("choices", choices(options))
                    .put("instruction",
                            "Runtime 已依 Accessibility 真實可點擊元素標號。只請使用者說畫面上的元素編號；"
                                    + "收到回答後用 phone_action(TAP,target=使用者原樣回答) 交還 Runtime。"
                                    + "不要猜座標，也不要把編號當成文字標籤。");
            return response;
        } catch (Exception ignored) {
            cancel();
            return failure("ELEMENT_REFERENCE_UNAVAILABLE", "無法建立可點擊元素標記。");
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    static Decision resolveChoice(String raw) {
        final Session session;
        synchronized (LOCK) {
            session = active;
            if (session == null) return Decision.none();
            if (session.expired()) {
                clearLocked();
                ElementReferenceOverlay.dismiss();
                return Decision.none();
            }
        }

        if (!sameScreenContext(session)) {
            cancel();
            return Decision.none();
        }

        int index = ElementReferenceChoice.parseIndex(raw, session.options.size());
        if (index < 0 || index >= session.options.size()) return Decision.none();
        Option chosen = session.options.get(index);

        synchronized (LOCK) {
            if (active == session) clearLocked();
        }
        ElementReferenceOverlay.dismiss();
        return Decision.selected(chosen.elementId);
    }

    static boolean isActive() {
        synchronized (LOCK) {
            if (active == null) return false;
            if (active.expired()) {
                clearLocked();
                ElementReferenceOverlay.dismiss();
                return false;
            }
            return true;
        }
    }

    static void cancel() {
        synchronized (LOCK) { clearLocked(); }
        ElementReferenceOverlay.dismiss();
    }

    private static boolean sameScreenContext(Session session) {
        CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        if (service == null) return false;
        if (service.getResources().getDisplayMetrics().widthPixels != session.width
                || service.getResources().getDisplayMetrics().heightPixels != session.height) {
            return false;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return false;
            if (!session.packageName.equals(root.getPackageName().toString())) return false;
            return !containsSensitiveNode(root);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private static void scheduleExpiry(final long sessionId) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (active == null || active.id != sessionId || !active.expired()) return;
                    clearLocked();
                }
                ElementReferenceOverlay.dismiss();
            }
        }, SESSION_TTL_MS + 250L);
    }

    private static JSONArray choices(List<Option> options) {
        JSONArray out = new JSONArray();
        if (options == null) return out;
        for (Option option : options) if (option != null) out.put(option.displayLabel());
        return out;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            if (!text.isEmpty() && !SensitiveDataGuard.REDACTED.equals(text)) return text;
        }
        return "";
    }

    private static String roleLabel(String role) {
        String value = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if ("icon_button".equals(value)) return "圖示按鈕";
        if ("button".equals(value)) return "按鈕";
        if ("checkbox".equals(value)) return "核取方塊";
        if ("switch".equals(value)) return "開關";
        if ("radio".equals(value)) return "單選按鈕";
        return "可點擊元素";
    }

    private static JSONObject failure(String code, String instruction) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("blockedByRuntime", true)
                    .put("error", code == null ? "ELEMENT_REFERENCE_UNAVAILABLE" : code)
                    .put("instruction", instruction == null ? "" : instruction);
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean unsafePackage(String packageName) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        if (pkg.isEmpty() || "com.crewpocket.helper".equals(pkg)) return true;
        String[] tokens = new String[] {
                "bank", "wallet", "payment", "finance", "crypto", "binance",
                "authenticator", "password", "keychain", "keystore",
                "inputmethod", "systemui", "packageinstaller"
        };
        for (String token : tokens) if (pkg.contains(token)) return true;
        return false;
    }

    private static boolean containsSensitiveNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (SensitiveDataGuard.isSensitiveNode(node)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (containsSensitiveNode(child)) return true;
            } finally {
                try { child.recycle(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static void clearLocked() {
        active = null;
    }
}
