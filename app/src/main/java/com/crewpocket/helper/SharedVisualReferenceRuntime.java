package com.crewpocket.helper;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Runtime-owned Shared Visual Reference session.
 *
 * The grid is explicitly user initiated. Runtime may remember the latest failed
 * semantic TAP briefly so an explicit "open grid" request can keep useful
 * context, but UI_TARGET_NOT_FOUND never opens the grid by itself.
 * Coordinates remain Runtime-only.
 */
final class SharedVisualReferenceRuntime {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long TARGET_NOTE_TTL_MS = 30_000L;
    private static final long SESSION_TTL_MS = 30_000L;

    enum Kind { NONE, REFINE, TAP }

    static final class Decision {
        final Kind kind;
        final JSONObject response;
        final int x;
        final int y;

        private Decision(Kind kind, JSONObject response, int x, int y) {
            this.kind = kind;
            this.response = response == null ? new JSONObject() : response;
            this.x = x;
            this.y = y;
        }

        static Decision none() { return new Decision(Kind.NONE, null, -1, -1); }
        static Decision refine(JSONObject response) {
            return new Decision(Kind.REFINE, response, -1, -1);
        }
        static Decision tap(int x, int y) {
            return new Decision(Kind.TAP, null, x, y);
        }
    }

    private static final class TargetNote {
        final String target;
        final String semanticTarget;
        final long at;

        TargetNote(String target, String semanticTarget) {
            this.target = safeTarget(target);
            this.semanticTarget = safeTarget(semanticTarget);
            this.at = System.currentTimeMillis();
        }

        boolean fresh() { return System.currentTimeMillis() - at <= TARGET_NOTE_TTL_MS; }
    }

    private static final class Session {
        final long id;
        final String packageName;
        final String target;
        final String semanticTarget;
        final int width;
        final int height;
        final long expiresAt;
        int stage;
        List<VisualReferenceGrid.Cell> cells;

        Session(long id,
                String packageName,
                String target,
                String semanticTarget,
                int width,
                int height,
                List<VisualReferenceGrid.Cell> cells) {
            this.id = id;
            this.packageName = packageName == null ? "" : packageName;
            this.target = safeTarget(target);
            this.semanticTarget = safeTarget(semanticTarget);
            this.width = width;
            this.height = height;
            this.cells = cells;
            this.stage = 1;
            this.expiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
        }

        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static TargetNote lastTarget;
    private static Session active;

    private SharedVisualReferenceRuntime() {}

    /** Remember only sanitized semantic context; this does not open the grid. */
    static void noteTapTarget(String target, String semanticTarget) {
        synchronized (LOCK) {
            lastTarget = new TargetNote(target, semanticTarget);
        }
    }

    /** A successful normal TAP means the remembered unresolved target is stale. */
    static void clearRememberedTarget() {
        synchronized (LOCK) {
            if (active == null) lastTarget = null;
        }
    }

    /**
     * Explicit entry point. Called only after the user asks to choose/point the
     * position. It can use a recent failed TAP label for the banner, but the
     * grid also works without any previous failure.
     */
    static JSONObject startExplicit() {
        JSONObject result = new JSONObject();
        final CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        try {
            if (service == null) {
                return failure("VISUAL_REFERENCE_UNAVAILABLE",
                        "螢幕操作服務尚未啟用，無法開啟位置方格。");
            }
            if (!Settings.canDrawOverlays(service)) {
                return failure("VISUAL_REFERENCE_OVERLAY_PERMISSION_REQUIRED",
                        "需要懸浮視窗權限才能顯示位置方格。");
            }

            TargetNote note;
            synchronized (LOCK) {
                if (active != null) active = null;
                note = lastTarget;
                if (note == null || !note.fresh()) note = new TargetNote("", "");
            }

            AccessibilityNodeInfo root = null;
            try {
                root = service.getRootInActiveWindow();
                if (root == null || root.getPackageName() == null) {
                    return failure("VISUAL_REFERENCE_NO_ACTIVE_WINDOW",
                            "目前沒有可選位置的 App 畫面。");
                }
                String packageName = root.getPackageName().toString();
                if (unsafePackage(packageName) || containsSensitiveNode(root)) {
                    return failure("VISUAL_REFERENCE_SENSITIVE_SCREEN",
                            "目前畫面不允許使用位置方格，請手動操作。");
                }

                int width = service.getResources().getDisplayMetrics().widthPixels;
                int height = service.getResources().getDisplayMetrics().heightPixels;
                if (width <= 1 || height <= 1) {
                    return failure("VISUAL_REFERENCE_BAD_DISPLAY",
                            "目前螢幕尺寸不可用，無法開啟位置方格。");
                }

                List<VisualReferenceGrid.Cell> cells = VisualReferenceGrid.primary(width, height);
                if (cells.isEmpty()) {
                    return failure("VISUAL_REFERENCE_GRID_UNAVAILABLE",
                            "無法建立位置方格。");
                }

                final Session session = new Session(
                        System.nanoTime(), packageName, note.target, note.semanticTarget,
                        width, height, cells);
                synchronized (LOCK) {
                    active = session;
                }

                String target = displayTarget(note);
                String title = target.isEmpty()
                        ? "位置選擇 — 說 1–12 或位置"
                        : "指定「" + target + "」— 說 1–12 或位置";
                SharedVisualReferenceOverlay.show(service, cells, title, false);
                scheduleExpiry(session.id);

                result.put("success", false)
                        .put("stepResult", "STEP_FAILED")
                        .put("blockedByRuntime", true)
                        .put("error", "VISUAL_REFERENCE_WAITING")
                        .put("taskState", "WAITING_USER")
                        .put("visualReference", "PRIMARY")
                        .put("visualReferenceStage", 1)
                        .put("choices", choices(cells))
                        .put("instruction",
                                "Runtime 已依使用者明確要求開啟 1–12 位置方格。只請使用者說編號或位置，例如「12」或「右下角」；"
                                        + "收到回答後，用 phone_action(TAP,target=使用者原樣回答) 交還 Runtime。不要猜座標。");
                return result;
            } finally {
                if (root != null) {
                    try { root.recycle(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
            cancel();
            return failure("VISUAL_REFERENCE_FAILED", "位置方格開啟失敗。");
        }
    }

    static Decision resolveChoice(String raw) {
        final Session session;
        synchronized (LOCK) {
            session = active;
            if (session == null) return Decision.none();
            if (session.expired()) {
                clearLocked();
                SharedVisualReferenceOverlay.dismiss();
                return Decision.none();
            }
        }

        if (!sameScreenContext(session)) {
            cancel();
            return Decision.none();
        }

        VisualReferenceGrid.Cell chosen = VisualReferenceGrid.resolve(session.cells, raw);
        if (chosen == null) return Decision.none();

        if (session.stage == 1) {
            List<VisualReferenceGrid.Cell> refined = VisualReferenceGrid.refine(chosen);
            if (refined.isEmpty()) return Decision.none();
            synchronized (LOCK) {
                if (active != session) return Decision.none();
                session.stage = 2;
                session.cells = refined;
            }
            CrewAccessibilityService service = CrewAccessibilityService.getInstance();
            if (service != null) {
                SharedVisualReferenceOverlay.show(
                        service,
                        refined,
                        "已放大這一區 — 再說 1–9 或位置",
                        true);
            }
            JSONObject response = new JSONObject();
            try {
                response.put("success", false)
                        .put("stepResult", "STEP_FAILED")
                        .put("blockedByRuntime", true)
                        .put("error", "VISUAL_REFERENCE_REFINED")
                        .put("taskState", "WAITING_USER")
                        .put("visualReference", "REFINED")
                        .put("visualReferenceStage", 2)
                        .put("choices", choices(refined))
                        .put("instruction",
                                "Runtime 已把使用者選的區域放大成 1–9。只請使用者再說一次編號或位置；"
                                        + "收到回答後呼叫 phone_action(TAP,target=使用者原樣回答)。不要自行產生座標。");
            } catch (Exception ignored) {}
            return Decision.refine(response);
        }

        int x = clamp(chosen.centerX(), 0, Math.max(0, session.width - 1));
        int y = clamp(chosen.centerY(), 0, Math.max(0, session.height - 1));
        synchronized (LOCK) {
            if (active == session) clearLocked();
        }
        SharedVisualReferenceOverlay.dismiss();
        return Decision.tap(x, y);
    }

    static boolean isActive() {
        synchronized (LOCK) {
            if (active == null) return false;
            if (active.expired()) {
                clearLocked();
                SharedVisualReferenceOverlay.dismiss();
                return false;
            }
            return true;
        }
    }

    static void cancel() {
        synchronized (LOCK) { clearLocked(); }
        SharedVisualReferenceOverlay.dismiss();
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
                SharedVisualReferenceOverlay.dismiss();
            }
        }, SESSION_TTL_MS + 250L);
    }

    private static JSONArray choices(List<VisualReferenceGrid.Cell> cells) {
        JSONArray out = new JSONArray();
        if (cells == null) return out;
        for (VisualReferenceGrid.Cell cell : cells) {
            if (cell != null) out.put(cell.displayLabel());
        }
        return out;
    }

    private static JSONObject failure(String error, String instruction) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("error", error)
                    .put("instruction", instruction)
                    .put("retryable", false);
        } catch (Exception ignored) {}
        return out;
    }

    private static String displayTarget(TargetNote note) {
        if (note == null) return "";
        String value = note.semanticTarget.isEmpty() ? note.target : note.semanticTarget;
        if (value.length() > 32) value = value.substring(0, 32);
        return value;
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
        lastTarget = null;
    }

    private static String safeTarget(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.length() > 80) text = text.substring(0, 80);
        if (text.contains("@") || text.contains("http://") || text.contains("https://")) return "";
        if (text.matches(".*\\b\\d{7,}\\b.*")) return "";
        return text;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
