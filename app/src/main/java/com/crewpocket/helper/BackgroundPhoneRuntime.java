package com.crewpocket.helper;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Opt-in Live virtual-screen executor. Intentionally disjoint from the normal
 * foreground tools: never remaps a failed operation to display 0.
 * Semantic actions use the current display's own Accessibility window tree.
 */
final class BackgroundPhoneRuntime {
    private static final long LEASE_MS = 15_000L;
    private final Context app;
    private final AppCatalog apps;
    private int leasedDisplayId = -1;
    private String leasedPackage = "";
    private String screenToken = "";
    private String screenFingerprint = "";
    private long screenTokenAt;
    private long openedGeneration = -1L;
    private long screenTokenGeneration = -1L;
    private final BackgroundPhoneSessionPolicy session = new BackgroundPhoneSessionPolicy();

    BackgroundPhoneRuntime(Context context) {
        this.app = context == null ? null : context.getApplicationContext();
        this.apps = app == null ? null : new AppCatalog(app);
    }

    synchronized JSONObject execute(JSONObject args, String userText,
                                    long generation, BooleanSupplier stillCurrent) {
        try {
            String action = args == null ? "" : args.optString("action", "").trim().toUpperCase(java.util.Locale.ROOT);
            if (app == null || !AppConfig.isBackgroundLiveEnabled(app))
                return rejected("BACKGROUND_LIVE_DISABLED");
            if (stillCurrent == null || !stillCurrent.getAsBoolean())
                return rejected("STALE_USER_INTENT");
            JSONObject state = BackgroundDisplayClient.command("status");
            if (!state.optBoolean("success", false)) {
                invalidateScreen();
                return rejectHelper(state); // Preserve HELPER_RESTART_REQUIRED and other codes.
            }
            boolean active = state.optBoolean("active", false)
                    && state.optInt("displayId", -1) > 0
                    && !state.optString("package", "").isEmpty();
            if (!active) clearLease();
            String denied = BackgroundPhonePolicy.deny(action, userText, true,
                    active, openedGeneration == generation);
            if (!denied.isEmpty()) return rejected(denied);
            if ("STATUS".equals(action)) {
                return state.put("background", true).put("taskState", "IN_PROGRESS");
            }
            if ("OPEN_APP".equals(action)) {
                String requested = args.optString("app", "").trim();
                if (requested.isEmpty() || BackgroundPhonePolicy.isSensitiveTarget(requested))
                    return rejected("APP_TARGET_REQUIRED_OR_BLOCKED");
                AppCatalog.Resolution chosen = apps.resolve(requested);
                if (!AppCatalog.Resolution.FOUND.equals(chosen.status) || chosen.chosen == null)
                    return rejected("APP_NOT_UNAMBIGUOUS");
                if (!stillCurrent.getAsBoolean()) return rejected("STALE_USER_INTENT");
                JSONObject launch = new JSONObject().put("op", "open")
                        .put("package", chosen.chosen.packageName);
                JSONObject outcome = BackgroundDisplayClient.request(launch);
                if (!outcome.optBoolean("success", false)) return rejectHelper(outcome);
                leasedDisplayId = outcome.optInt("displayId", -1);
                leasedPackage = outcome.optString("package", "");
                if (leasedDisplayId <= 0 || !leasedPackage.equals(chosen.chosen.packageName)) {
                    clearLease();
                    return rejected("DISPLAY_IDENTITY_MISMATCH");
                }
                if (!stillCurrent.getAsBoolean()) {
                    // The user superseded this turn while Shell was starting
                    // the App. Release only the display we just created.
                    try {
                        BackgroundDisplayClient.request(new JSONObject()
                                .put("op", "close")
                                .put("expectedDisplayId", leasedDisplayId)
                                .put("expectedPackage", leasedPackage));
                    } catch (Exception ignored) {}
                    clearLease();
                    return rejected("STALE_USER_INTENT");
                }
                openedGeneration = generation;
                session.reset();
                invalidateScreen();
                return new JSONObject().put("success", true)
                        .put("background", true).put("action", action)
                        .put("displayId", leasedDisplayId).put("package", leasedPackage)
                        .put("taskState", "IN_PROGRESS")
                        .put("nextRequirement", "INSPECT_BACKGROUND")
                        .put("instruction", "App 已要求在背景開啟。先 INSPECT 確認畫面；不得把開啟回覆當成目標任務完成。");
            }

            // Live never adopts a display created manually or by a previous
            // Live session. The model must own the exact OPEN_APP result.
            int id = state.optInt("displayId", -1);
            String pkg = state.optString("package", "");
            if (id <= 0 || pkg.isEmpty()) return rejected("INVALID_BACKGROUND_DISPLAY");
            if (openedGeneration < 0 || leasedDisplayId != id
                    || !pkg.equals(leasedPackage)) {
                clearLease();
                return rejected("BACKGROUND_SESSION_NOT_OWNED_BY_LIVE");
            }
            String budgetError = session.preflight(action);
            if (!budgetError.isEmpty()) return rejected(budgetError);
            if ("INSPECT".equals(action)) {
                session.recordInspection();
                Snapshot shot = awaitSnapshot(leasedDisplayId, leasedPackage, stillCurrent);
                if (!shot.ok()) return rejected(shot.error);
                if (!stillCurrent.getAsBoolean()) {
                    shot.close();
                    return rejected("STALE_USER_INTENT");
                }
                screenToken = UUID.randomUUID().toString();
                screenFingerprint = shot.ui.optString("fingerprint", "");
                screenTokenAt = SystemClock.elapsedRealtime();
                screenTokenGeneration = generation;
                session.onObservedScreen(screenFingerprint);
                JSONObject result = new JSONObject().put("success", true)
                        .put("action", action).put("background", true)
                        .put("displayId", leasedDisplayId)
                        .put("package", leasedPackage)
                        .put("screenToken", screenToken)
                        .put("screen_token", screenToken)
                        .put("taskState", "IN_PROGRESS")
                        .put("inspectionAttempts", session.inspectionCount())
                        .put("mutationAttempts", session.mutationCount())
                        .put("screen", shot.ui);
                shot.close();
                return result;
            }
            if ("HANDOFF".equals(action) || "CLOSE".equals(action)) {
                if (!stillCurrent.getAsBoolean()) return rejected("STALE_USER_INTENT");
                invalidateScreen();
                JSONObject request = new JSONObject()
                        .put("op", "HANDOFF".equals(action) ? "front" : "close")
                        .put("expectedDisplayId", leasedDisplayId)
                        .put("expectedPackage", leasedPackage);
                JSONObject result = BackgroundDisplayClient.request(request);
                if (!result.optBoolean("success", false)) return rejectHelper(result);
                if ("HANDOFF".equals(action) && !result.optBoolean("handedOff", false))
                    return rejected("HANDOFF_NOT_CONFIRMED");
                clearLease();
                return new JSONObject().put("success", true)
                        .put("background", true).put("action", action)
                        .put("taskState", "DONE")
                        .put("completionEvidence", "HANDOFF".equals(action)
                                ? "VIRTUAL_TASK_MOVED_TO_MAIN_DISPLAY" : "VIRTUAL_DISPLAY_RELEASED");
            }
            if (!"TAP".equals(action) && !"SCROLL".equals(action) && !"TYPE".equals(action))
                return rejected("UNKNOWN_BACKGROUND_ACTION");
            String token = args.optString("screen_token", "");
            String elementId = args.optString("element_id", "");
            if (token.isEmpty() || !token.equals(screenToken)
                    || screenTokenGeneration != generation
                    || elementId.isEmpty()
                    || SystemClock.elapsedRealtime() - screenTokenAt > LEASE_MS)
                return rejected("STALE_BACKGROUND_SCREEN");
            Snapshot shot = takeSnapshot(leasedDisplayId, leasedPackage);
            if (!shot.ok()) return rejected(shot.error);
            if (!screenFingerprint.equals(shot.ui.optString("fingerprint", ""))) {
                shot.close();
                invalidateScreen();
                return rejected("BACKGROUND_SCREEN_CHANGED");
            }
            final String beforeFingerprint = screenFingerprint;
            final String actionKey = BackgroundPhoneSessionPolicy.actionKey(
                    action, elementId, args.optString("direction", ""),
                    args.optString("text", ""));
            if (session.blocksRepeat(actionKey, beforeFingerprint)) {
                shot.close();
                invalidateScreen();
                return rejected("REPEATED_UNVERIFIED_BACKGROUND_ACTION");
            }
            // Every mutation attempt consumes the observation lease, including
            // a failed or blocked tap. A subsequent attempt requires INSPECT.
            invalidateScreen();
            AccessibilityNodeInfo element = findElement(shot.root, elementId, -1, 0);
            try {
                if (element == null) return rejected("BACKGROUND_ELEMENT_NOT_FOUND");
                String label = String.valueOf(element.getText() == null ? ""
                        : element.getText()) + " " +
                        String.valueOf(element.getContentDescription() == null ? ""
                        : element.getContentDescription());
                String viewId = element.getViewIdResourceName() == null
                        ? "" : element.getViewIdResourceName();
                if (SensitiveDataGuard.isSensitiveNode(element)
                        || BackgroundPhonePolicy.isSensitiveTarget(label + " " + viewId)
                        || AccessibilityNodeRepository.isSendIntent(label, viewId))
                    return rejected("SENSITIVE_BACKGROUND_ACTION_BLOCKED");
                if (!stillCurrent.getAsBoolean()) return rejected("STALE_USER_INTENT");
                session.recordMutation(); // Counts attempts, not inferred success.
                boolean dispatched;
                if ("TAP".equals(action)) {
                    if (!element.isClickable() || !element.isEnabled()) return rejected("NOT_CLICKABLE");
                    PolicyEngine.Result policy = PolicyEngine.evaluate("click", label, viewId, false);
                    if (policy.blocked()) return rejected("POLICY_REJECTED");
                    dispatched = element.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else if ("SCROLL".equals(action)) {
                    if (!element.isScrollable()) return rejected("NOT_SCROLLABLE");
                    String dir = args.optString("direction", "").toLowerCase(java.util.Locale.ROOT);
                    if (!"forward".equals(dir) && !"backward".equals(dir))
                        return rejected("INVALID_SCROLL_DIRECTION");
                    dispatched = element.performAction("forward".equals(dir)
                            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                } else {
                    if (!element.isEditable() || !element.isEnabled())
                        return rejected("NOT_EDITABLE");
                    if (PolicyEngine.evaluate("type", label, viewId, false).blocked())
                        return rejected("TYPE_POLICY_REJECTED");
                    String value = args.optString("text", "");
                    if (value.length() > 160 || value.isEmpty()
                            || BackgroundPhonePolicy.isSensitiveTarget(value))
                        return rejected("TYPE_CONTENT_REJECTED");
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                    dispatched = element.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                }
                if (!dispatched) return rejected("BACKGROUND_ACTION_NOT_DISPATCHED");
            } finally {
                if (element != null) element.recycle();
                shot.close();
            }
            // App state change is evidence, not proof of the entire user goal.
            SystemClock.sleep(180);
            Snapshot after = takeSnapshot(leasedDisplayId, leasedPackage);
            boolean changed = after.ok() && !beforeFingerprint.equals(
                    after.ui.optString("fingerprint", ""));
            if (after.root != null) after.close();
            session.recordOutcome(actionKey, beforeFingerprint, changed);
            return new JSONObject().put("success", true)
                    .put("background", true).put("action", action)
                    .put("taskState", "IN_PROGRESS")
                    .put("stepResult", "STEP_PENDING")
                    .put("inspectionAttempts", session.inspectionCount())
                    .put("mutationAttempts", session.mutationCount())
                    .put("verificationStatus", changed ? "OBSERVED_CHANGE_UNVERIFIED" : "PENDING")
                    .put("nextRequirement", "INSPECT_BACKGROUND")
                    .put("instruction", "Android 已接收操作，不代表整個目標已完成；請重新 INSPECT 背景畫面驗證。");
        } catch (Throwable error) {
            invalidateScreen();
            return rejected("BACKGROUND_RUNTIME_ERROR_" + error.getClass().getSimpleName());
        }
    }

    private void clearLease() {
        session.reset();
        leasedDisplayId = -1;
        leasedPackage = "";
        openedGeneration = -1;
        invalidateScreen();
    }
    private void invalidateScreen() {
        screenTokenGeneration = -1L;
        screenToken = "";
        screenFingerprint = "";
        screenTokenAt = 0L;
    }

    /** Wait only for ordinary App startup; never retry a rejected mutation. */
    private Snapshot awaitSnapshot(int displayId, String pkg, BooleanSupplier stillCurrent) {
        Snapshot shot = takeSnapshot(displayId, pkg);
        for (int attempt = 0; attempt < 2 && !shot.ok(); attempt++) {
            if (!"DISPLAY_WINDOWS_UNAVAILABLE".equals(shot.error)
                    && !"TARGET_APP_WINDOW_NOT_FOUND".equals(shot.error))
                break;
            if (!stillCurrent.getAsBoolean()) break;
            SystemClock.sleep(160);
            shot = takeSnapshot(displayId, pkg);
        }
        return shot;
    }

    private Snapshot takeSnapshot(int displayId, String pkg) {
        CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        if (service == null) return new Snapshot(null, null, "ACCESSIBILITY_NOT_CONNECTED");
        try {
            SparseArray<List<AccessibilityWindowInfo>> all = service.getWindowsOnAllDisplays();
            List<AccessibilityWindowInfo> windows = all == null ? null : all.get(displayId);
            if (windows == null) return new Snapshot(null, null, "DISPLAY_WINDOWS_UNAVAILABLE");
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w == null ? null : w.getRoot();
                if (root == null) continue;
                if (root.getPackageName() == null
                        || !pkg.equals(root.getPackageName().toString())) {
                    root.recycle();
                    continue;
                }
                JSONObject ui = SemanticScreenState.capture(root);
                if (!ui.optBoolean("success", false)) {
                    root.recycle();
                    continue;
                }
                JSONArray elements = ui.optJSONArray("elements");
                if (elements != null && elements.length() > 60) {
                    JSONArray bounded = new JSONArray();
                    for (int i = 0; i < 60; i++) bounded.put(elements.opt(i));
                    ui.put("elements", bounded);
                    ui.put("truncated", true);
                }
                return new Snapshot(root, ui, "");
            }
            return new Snapshot(null, null, "TARGET_APP_WINDOW_NOT_FOUND");
        } catch (Throwable failed) {
            return new Snapshot(null, null, "ACCESSIBILITY_INSPECTION_FAILED");
        }
    }

    private static AccessibilityNodeInfo findElement(AccessibilityNodeInfo node,
                String id, int sibling, int depth) {
        if (node == null || depth > 40) return null;
        if (id.equals(SemanticScreenState.elementId(node, sibling, depth)))
            return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo found = findElement(child, id, i, depth + 1);
                if (found != null) return found;
            } finally { child.recycle(); }
        }
        return null;
    }
    private static JSONObject rejectHelper(JSONObject helper) throws Exception {
        return rejected(helper.optString("error", "BACKGROUND_HELPER_FAILED"));
    }
    private static JSONObject rejected(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false).put("blockedByRuntime", true)
                    .put("error", code).put("taskState", "NEED_USER")
                    .put("nextRequirement", "ASK_USER_OR_INSPECT")
                    .put("instruction", "背景操作已停止於安全邊界；不可改操作主螢幕，也不可猜座標。");
        } catch (Exception ignored) {}
        return out;
    }

    private static final class Snapshot {
        final AccessibilityNodeInfo root;
        final JSONObject ui;
        final String error;
        Snapshot(AccessibilityNodeInfo root, JSONObject ui, String error) {
            this.root = root; this.ui = ui; this.error = error;
        }
        boolean ok() { return root != null && ui != null; }
        void close() { if (root != null) root.recycle(); }
    }
}
