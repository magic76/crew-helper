package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;
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
    // Pending frame is ephemeral, App-process memory only, consumed before
    // functionResponse; the raw JPEG must NEVER enter a tool JSON response.
    private byte[] pendingVisionJpeg;
    private String visionToken = "";
    private long visionGeneration = -1L;
    private long visionAtMs;
    private int visionWidth;
    private int visionHeight;
    private int[] visionGrid;

    BackgroundPhoneRuntime(Context context) {
        this.app = context == null ? null : context.getApplicationContext();
        this.apps = app == null ? null : new AppCatalog(app);
    }

    synchronized byte[] consumeVisionFrame() {
        byte[] bytes = pendingVisionJpeg;
        pendingVisionJpeg = null;
        return bytes;
    }

    synchronized void revokeVisionLease() { clearVision(); }

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
            if ("VISION".equals(action)) {
                session.recordInspection();
                session.recordVision();
                if (!BackgroundVisionPolicy.permittedPackage(leasedPackage))
                    return rejected("BACKGROUND_VISION_APP_NOT_ALLOWLISTED");
                if (!state.optBoolean("focusIsolated", false))
                    return rejected("BACKGROUND_VISION_REQUIRES_ISOLATED_FOCUS");
                Snapshot shot = awaitSnapshot(leasedDisplayId, leasedPackage, stillCurrent);
                if (!shot.ok()) return rejected(shot.error);
                try {
                    if (sensitiveScreen(shot.ui) || sensitiveAnywhere(shot.root))
                        return rejected("BACKGROUND_VISION_SENSITIVE_SCREEN");
                    if (!shot.ui.optBoolean("visionRecommended", false)
                            && !BackgroundVisionPolicy.explicitVisualRequest(userText))
                        return rejected("BACKGROUND_SEMANTIC_UI_SUFFICIENT");
                    if (!stillCurrent.getAsBoolean())
                        return rejected("STALE_USER_INTENT");
                    JSONObject photo = previewOnOwnedDisplay(leasedDisplayId, leasedPackage);
                    if (!photo.optBoolean("success", false)) return rejectHelper(photo);
                    long age = photo.optLong("frameAgeMs", Long.MAX_VALUE);
                    if (photo.optInt("width", 0) != state.optInt("width", 0)
                            || photo.optInt("height", 0) != state.optInt("height", 0)
                            || age < 0L || age > BackgroundVisionPolicy.MAX_FRAME_AGE_MS)
                        return rejected("BACKGROUND_FRAME_DIMENSION_OR_AGE_MISMATCH");
                    String encoded = photo.optString("jpegBase64", "");
                    if (encoded.isEmpty() || encoded.length() > 1_100_000)
                        return rejected("BACKGROUND_FRAME_SIZE_INVALID");
                    byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                    if (bytes.length == 0
                            || bytes.length > BackgroundVisionPolicy.MAX_JPEG_BYTES)
                        return rejected("BACKGROUND_FRAME_SIZE_INVALID");
                    int[] grid = sampledGrid(bytes);
                    if (grid == null) return rejected("BACKGROUND_IMAGE_DECODE_FAILED");
                    if (!stillCurrent.getAsBoolean()) return rejected("STALE_USER_INTENT");

                    invalidateScreen();
                    screenFingerprint = shot.ui.optString("fingerprint", "");
                    visionToken = UUID.randomUUID().toString();
                    visionGeneration = generation;
                    visionAtMs = SystemClock.elapsedRealtime();
                    visionWidth = photo.optInt("width");
                    visionHeight = photo.optInt("height");
                    visionGrid = grid;
                    pendingVisionJpeg = bytes;
                    session.onObservedScreen(screenFingerprint);
                    return new JSONObject().put("success", true)
                            .put("background", true).put("action", "VISION")
                            .put("visualReady", true)
                            .put("visionToken", visionToken)
                            .put("visual_token", visionToken)
                            .put("width", visionWidth).put("height", visionHeight)
                            .put("displayId", leasedDisplayId)
                            .put("package", leasedPackage)
                            .put("taskState", "IN_PROGRESS")
                            .put("verificationStatus", "PENDING")
                            .put("nextRequirement", "VISION_OR_VISUAL_TAP")
                            .put("visionCaptures", session.visionCount())
                            .put("screen", shot.ui)
                            .put("instruction", "這張影像僅是背景虛擬螢幕，不是手機主螢幕。"
                                    + "只有看清低風險按鈕且元素不完整時，才以 visual_token + "
                                    + "normalized visual_x/visual_y + target 執行一次 VISUAL_TAP。"
                                    + "不得送出、登入、支付、刪除或猜測座標。");
                } finally {
                    shot.close();
                }
            }
            if ("INSPECT".equals(action)) {
                session.recordInspection();
                clearVision();
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
            if ("VISUAL_TAP".equals(action)) {
                if (!BackgroundVisionPolicy.permittedPackage(leasedPackage)
                        || !state.optBoolean("focusIsolated", false))
                    return rejected("BACKGROUND_VISUAL_TAP_NOT_ALLOWED");
                String token = args.optString("visual_token", "");
                if (visionToken.isEmpty() || !visionToken.equals(token)
                        || visionGeneration != generation
                        || !BackgroundVisionPolicy.fresh(SystemClock.elapsedRealtime(),
                                visionAtMs, BackgroundVisionPolicy.TAP_LEASE_MS))
                    return rejected("STALE_BACKGROUND_VISION_LEASE");
                double nx = args.optDouble("visual_x", Double.NaN);
                double ny = args.optDouble("visual_y", Double.NaN);
                String target = args.optString("target", "").trim();
                if (!BackgroundVisionPolicy.lowRiskTarget(target)
                        || !BackgroundVisionPolicy.validCoordinate(nx)
                        || !BackgroundVisionPolicy.validCoordinate(ny))
                    return rejected("BACKGROUND_VISUAL_TARGET_REJECTED");
                int x = BackgroundVisionPolicy.coordinate(nx, visionWidth);
                int y = BackgroundVisionPolicy.coordinate(ny, visionHeight);
                String before = screenFingerprint;
                String key = BackgroundPhoneSessionPolicy.actionKey(
                        "VISUAL_TAP", target + ":" + x + ":" + y, "", "");
                // Capture the frozen visual signature before the lease is consumed.
                int[] lastVisionGrid = visionGrid;
                // Consume lease before inspecting / tapping, even on failure.
                invalidateScreen();
                if (session.blocksRepeat(key, before))
                    return rejected("REPEATED_UNVERIFIED_BACKGROUND_ACTION");
                Snapshot shot = takeSnapshot(leasedDisplayId, leasedPackage);
                if (!shot.ok()) return rejected(shot.error);
                try {
                    if (!before.equals(shot.ui.optString("fingerprint", "")))
                        return rejected("BACKGROUND_SCREEN_CHANGED");
                    if (sensitiveScreen(shot.ui) || sensitiveAnywhere(shot.root)
                            || sensitiveAt(shot.root, x, y))
                        return rejected("BACKGROUND_VISION_SENSITIVE_TARGET");
                    if (PolicyEngine.evaluate("click", target, "", false).blocked())
                        return rejected("BACKGROUND_VISUAL_POLICY_REJECTED");
                    if (!stillCurrent.getAsBoolean())
                        return rejected("STALE_USER_INTENT");
                    JSONObject fresh = previewOnOwnedDisplay(leasedDisplayId, leasedPackage);
                    if (!fresh.optBoolean("success", false)) return rejectHelper(fresh);
                    byte[] latest = Base64.decode(fresh.optString("jpegBase64", ""),
                            Base64.DEFAULT);
                    if (latest.length == 0
                            || latest.length > BackgroundVisionPolicy.MAX_JPEG_BYTES)
                        return rejected("BACKGROUND_FRAME_SIZE_INVALID");
                    int[] currentGrid = sampledGrid(latest);
                    if (!BackgroundVisionPolicy.similarFrames(lastVisionGrid, currentGrid))
                        return rejected("BACKGROUND_VISUAL_FRAME_CHANGED");
                    if (!stillCurrent.getAsBoolean())
                        return rejected("STALE_USER_INTENT");
                    session.recordMutation();
                    JSONObject command = new JSONObject().put("op", "tap")
                            .put("expectedDisplayId", leasedDisplayId)
                            .put("expectedPackage", leasedPackage)
                            .put("x", x).put("y", y);
                    JSONObject tap = BackgroundDisplayClient.request(command);
                    if (!tap.optBoolean("success", false)) return rejectHelper(tap);
                } finally {
                    shot.close();
                }
                SystemClock.sleep(180);
                Snapshot after = takeSnapshot(leasedDisplayId, leasedPackage);
                boolean changed = after.ok() && !before.equals(
                        after.ui.optString("fingerprint", ""));
                if (after.ok()) after.close();
                session.recordOutcome(key, before, changed);
                return new JSONObject().put("success", true)
                        .put("background", true).put("action", "VISUAL_TAP")
                        .put("taskState", "IN_PROGRESS")
                        .put("stepResult", "STEP_PENDING")
                        .put("verificationStatus",
                                changed ? "OBSERVED_CHANGE_UNVERIFIED" : "PENDING")
                        .put("nextRequirement", "INSPECT_BACKGROUND")
                        .put("instruction", "只完成虛擬螢幕的低風險點擊，尚未驗證使用者整體目標；請重新 INSPECT。");
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
        clearVision();
        screenTokenGeneration = -1L;
        screenToken = "";
        screenFingerprint = "";
        screenTokenAt = 0L;
    }

    private void clearVision() {
        // Clear the pending image and one-shot coordinate authority together.
        pendingVisionJpeg = null;
        visionToken = "";
        visionGeneration = -1L;
        visionAtMs = 0L;
        visionWidth = 0;
        visionHeight = 0;
        visionGrid = null;
    }

    private JSONObject previewOnOwnedDisplay(int id, String pkg) throws Exception {
        JSONObject request = new JSONObject().put("op", "preview")
                .put("expectedDisplayId", id)
                .put("expectedPackage", pkg);
        JSONObject image = BackgroundDisplayClient.request(request);
        if (image.optBoolean("success", false)
                && (image.optInt("displayId", -1) != id
                || !pkg.equals(image.optString("package", ""))))
            return new JSONObject().put("success", false)
                    .put("error", "BACKGROUND_PREVIEW_IDENTITY_MISMATCH");
        return image;
    }

    private static boolean sensitiveScreen(JSONObject screen) {
        if (screen == null) return true;
        JSONArray elements = screen.optJSONArray("elements");
        if (elements == null) return true;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) continue;
            if (el.optBoolean("sensitive", false)
                    || BackgroundPhonePolicy.isSensitiveTarget(
                            el.optString("label", "") + " "
                            + el.optString("viewId", "")))
                return true;
        }
        return false;
    }

    private static boolean sensitiveAnywhere(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (SensitiveDataGuard.isSensitiveNode(node)) return true;
        String label = String.valueOf(node.getText() == null ? "" : node.getText())
                + " " + String.valueOf(node.getContentDescription() == null
                    ? "" : node.getContentDescription());
        String id = node.getViewIdResourceName() == null
                ? "" : node.getViewIdResourceName();
        if (BackgroundPhonePolicy.isSensitiveTarget(label + " " + id)
                || AccessibilityNodeRepository.isSendIntent(label, id))
            return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (sensitiveAnywhere(child)) return true;
            } finally { child.recycle(); }
        }
        return false;
    }

    private static boolean sensitiveAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return false;
        if (SensitiveDataGuard.isSensitiveNode(node)) return true;
        String label = String.valueOf(node.getText() == null ? "" : node.getText())
                + " " + String.valueOf(node.getContentDescription() == null
                    ? "" : node.getContentDescription());
        String viewId = node.getViewIdResourceName() == null
                ? "" : node.getViewIdResourceName();
        if (BackgroundPhonePolicy.isSensitiveTarget(label + " " + viewId)
                || AccessibilityNodeRepository.isSendIntent(label, viewId))
            return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (sensitiveAt(child, x, y)) return true;
            } finally { child.recycle(); }
        }
        return false;
    }

    private static int[] sampledGrid(byte[] jpeg) {
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (source == null || source.getWidth() < 40 || source.getHeight() < 40)
            return null;
        try {
            int[] grid = new int[64];
            for (int row = 0; row < 8; row++)
                for (int col = 0; col < 8; col++) {
                    int x = (int) ((col + 0.5) * source.getWidth() / 8.0);
                    int y = (int) ((row + 0.5) * source.getHeight() / 8.0);
                    grid[row * 8 + col] = source.getPixel(x, y);
                }
            return grid;
        } finally {
            source.recycle();
        }
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
