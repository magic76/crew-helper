package com.crewpocket.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioManager;
import android.provider.MediaStore;
import android.database.Cursor;
import android.content.ContentUris;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.Executor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class CrewAccessibilityService extends AccessibilityService {
    private static final String TAG = "CrewAccessibilityService";
    private static final int PORT = 8766;

    private static CrewAccessibilityService instance;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private Handler mainHandler;
    private LearnedUiMappingStore learnedUiMappingStore;
    private UiTeachOverlay uiTeachOverlay;
    private AppCatalog appCatalog;
    private volatile String lastTextInputMethod = "NONE";
    private volatile String lastTextInputFailure = "";
    private volatile boolean lastTextInputVerified = false;

    public static boolean isServiceRunning() { return instance != null; }
    public static CrewAccessibilityService getInstance() {
        return instance;
    }
    public LearnedUiMappingStore getLearnedUiMappingStore() {
        if (learnedUiMappingStore == null) learnedUiMappingStore = new LearnedUiMappingStore(this);
        return learnedUiMappingStore;
    }

    /**
     * 0094 explicit selected-region context.
     *
     * Accessibility only contributes structure/text. The selection itself is
     * user-authored and must never become a direct tap coordinate.
     */
    public static SelectedRegionContext describeSelectedRegion(
            Rect region,
            int screenWidth,
            int screenHeight) {
        CrewAccessibilityService service = instance;
        if (service == null) {
            return new SelectedRegionContext(
                    region,
                    screenWidth,
                    screenHeight,
                    "",
                    "",
                    false,
                    System.currentTimeMillis());
        }
        return service.describeSelectedRegionInternal(
                region,
                screenWidth,
                screenHeight);
    }

    private SelectedRegionContext describeSelectedRegionInternal(
            Rect region,
            int screenWidth,
            int screenHeight) {
        Rect safeRegion = region == null ? new Rect() : new Rect(region);
        AccessibilityNodeInfo root = null;
        String sourcePackage = "";
        ArrayList<String> pieces = new ArrayList<String>();
        boolean[] hardSensitive = new boolean[]{false};

        try {
            root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                sourcePackage = pkg == null ? "" : pkg.toString();
                collectSelectedRegionText(
                        root,
                        safeRegion,
                        pieces,
                        hardSensitive,
                        0);
            }
        } catch (Exception ignored) {
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }

        StringBuilder semantic = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null || piece.trim().isEmpty()) continue;
            if (semantic.length() > 0) semantic.append(" · ");
            semantic.append(piece.trim());
            if (semantic.length() >= 1000) break;
        }

        String text = semantic.toString();
        if (text.length() > 1000) text = text.substring(0, 1000);

        return new SelectedRegionContext(
                safeRegion,
                screenWidth,
                screenHeight,
                sourcePackage,
                text,
                hardSensitive[0],
                System.currentTimeMillis());
    }

    private void collectSelectedRegionText(
            AccessibilityNodeInfo node,
            Rect selected,
            ArrayList<String> out,
            boolean[] hardSensitive,
            int depth) {
        if (node == null || selected == null || depth > 30 || out.size() >= 18) {
            return;
        }

        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);
        if (!Rect.intersects(nodeBounds, selected)) return;

        if (SensitiveDataGuard.isHardBlockedInput(node)) {
            hardSensitive[0] = true;
            return;
        }

        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);
        if (!sensitive && node.isVisibleToUser()) {
            appendSelectedText(out, node.getText());
            appendSelectedText(out, node.getContentDescription());
        }

        int count = node.getChildCount();
        for (int i = 0; i < count && out.size() < 18; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectSelectedRegionText(
                        child,
                        selected,
                        out,
                        hardSensitive,
                        depth + 1);
            } finally {
                try { child.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private void appendSelectedText(
            ArrayList<String> out,
            CharSequence value) {
        if (value == null) return;
        String clean = value.toString().replaceAll("\\s+", " ").trim();
        if (clean.isEmpty() || SensitiveDataGuard.REDACTED.equals(clean)) return;
        if (clean.length() > 240) clean = clean.substring(0, 240);
        if (!out.contains(clean)) out.add(clean);
    }



    public static JSONObject getFocusedInputSnapshot() {
        CrewAccessibilityService service = instance;
        if (service == null) {
            return focusedInputError("ACCESSIBILITY_UNAVAILABLE");
        }
        return service.getFocusedInputSnapshotInternal();
    }

    public static JSONObject writeFocusedInput(
            String text,
            String mode,
            String expectedTargetKey) {
        CrewAccessibilityService service = instance;
        if (service == null) {
            return focusedInputError("ACCESSIBILITY_UNAVAILABLE");
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return service.writeFocusedInputInternal(
                    text,
                    mode,
                    expectedTargetKey);
        }

        final Object lock = new Object();
        final JSONObject[] result = new JSONObject[1];
        service.mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    result[0] = service.writeFocusedInputInternal(
                            text,
                            mode,
                            expectedTargetKey);
                } finally {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            }
        });

        synchronized (lock) {
            try {
                if (result[0] == null) lock.wait(1800L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        return result[0] == null
                ? focusedInputError("FOCUSED_INPUT_TIMEOUT")
                : result[0];
    }

    private JSONObject getFocusedInputSnapshotInternal() {
        AccessibilityNodeInfo target =
                findFocusedEditableNodeForCompanion();
        if (target == null) {
            return focusedInputError("NO_FOCUSED_EDITABLE");
        }

        try {
            if (SensitiveDataGuard.isHardBlockedInput(target)) {
                JSONObject blocked =
                        focusedInputError("SENSITIVE_INPUT_BLOCKED");
                try { blocked.put("sensitive", true); }
                catch (Exception ignored) {}
                return blocked;
            }

            CharSequence pkg = target.getPackageName();
            String packageName = pkg == null ? "" : pkg.toString();

            CharSequence current = target.getText();
            String currentText =
                    current == null ? "" : current.toString();
            if (currentText.length() > 8000) {
                currentText = currentText.substring(0, 8000);
            }

            JSONObject out = new JSONObject();
            try {
                out.put("success", true);
                out.put("sensitive", false);
                out.put("package", packageName);
                out.put("targetKey", focusedInputTargetKey(target));
                out.put("text", currentText);
                out.put(
                        "selectionStart",
                        target.getTextSelectionStart());
                out.put(
                        "selectionEnd",
                        target.getTextSelectionEnd());
                out.put("textLength", currentText.length());
            } catch (Exception ignored) {}
            return out;
        } finally {
            try { target.recycle(); } catch (Exception ignored) {}
        }
    }

    private JSONObject writeFocusedInputInternal(
            String replacement,
            String mode,
            String expectedTargetKey) {
        AccessibilityNodeInfo target =
                findFocusedEditableNodeForCompanion();
        if (target == null) {
            return focusedInputError("NO_FOCUSED_EDITABLE");
        }

        try {
            if (SensitiveDataGuard.isHardBlockedInput(target)) {
                return focusedInputError("SENSITIVE_INPUT_BLOCKED");
            }

            String currentKey = focusedInputTargetKey(target);
            if (expectedTargetKey == null
                    || expectedTargetKey.isEmpty()
                    || !expectedTargetKey.equals(currentKey)) {
                return focusedInputError("FOCUSED_INPUT_TARGET_CHANGED");
            }

            String incoming =
                    replacement == null ? "" : replacement;
            CharSequence currentSequence = target.getText();
            String current = currentSequence == null
                    ? ""
                    : currentSequence.toString();

            int start = target.getTextSelectionStart();
            int end = target.getTextSelectionEnd();
            if (start < 0 || start > current.length()) {
                start = current.length();
            }
            if (end < 0 || end > current.length()) {
                end = start;
            }
            if (end < start) {
                int swap = start;
                start = end;
                end = swap;
            }

            String writeMode =
                    mode == null ? "insert" : mode;
            String desired;

            if ("replace_all".equals(writeMode)) {
                desired = incoming;
            } else {
                desired = current.substring(0, start)
                        + incoming
                        + current.substring(end);
            }

            if (desired.length() > 50_000) {
                return focusedInputError("FOCUSED_INPUT_TOO_LARGE");
            }

            target.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS);

            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    desired);

            boolean accepted = target.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args);

            String method = "ACTION_SET_TEXT";
            if (!accepted) {
                accepted = pasteIntoTarget(target, desired);
                method = "ACTION_PASTE";
            }

            if (!accepted) {
                return focusedInputError("FOCUSED_INPUT_WRITE_FAILED");
            }

            moveCursorToEnd(target, desired.length());

            JSONObject out = new JSONObject();
            try {
                out.put("success", true);
                out.put("method", method);
                out.put("mode", writeMode);
                out.put("textLength", desired.length());
                out.put(
                        "message",
                        "文字已寫入目前輸入框；未送出。");
            } catch (Exception ignored) {}
            return out;
        } finally {
            try { target.recycle(); } catch (Exception ignored) {}
        }
    }

    private AccessibilityNodeInfo
            findFocusedEditableNodeForCompanion() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo window = windows.get(i);
                    if (window == null
                            || window.getType()
                                    != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }

                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;

                    AccessibilityNodeInfo focused = null;
                    try {
                        focused = root.findFocus(
                                AccessibilityNodeInfo.FOCUS_INPUT);
                        if (focused != null
                                && focused.isEditable()
                                && focused.isVisibleToUser()) {
                            CharSequence pkg =
                                    focused.getPackageName();
                            String packageName =
                                    pkg == null ? "" : pkg.toString();

                            if (!getPackageName().equals(packageName)) {
                                return AccessibilityNodeInfo.obtain(focused);
                            }
                        }
                    } finally {
                        if (focused != null) {
                            try { focused.recycle(); }
                            catch (Exception ignored) {}
                        }
                        try { root.recycle(); }
                        catch (Exception ignored) {}
                    }
                }
            }

            // Some IMEs/apps expose the focused editor only through the active
            // application root, not through getWindows().
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                AccessibilityNodeInfo focused = null;
                try {
                    focused = activeRoot.findFocus(
                            AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null
                            && focused.isEditable()
                            && focused.isVisibleToUser()) {
                        CharSequence pkg = focused.getPackageName();
                        String packageName = pkg == null ? "" : pkg.toString();
                        if (!getPackageName().equals(packageName)) {
                            return AccessibilityNodeInfo.obtain(focused);
                        }
                    }
                } finally {
                    if (focused != null) {
                        try { focused.recycle(); } catch (Exception ignored) {}
                    }
                    try { activeRoot.recycle(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String focusedInputTargetKey(
            AccessibilityNodeInfo node) {
        if (node == null) return "";

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        CharSequence pkg = node.getPackageName();
        CharSequence clazz = node.getClassName();
        String viewId = node.getViewIdResourceName();

        return (pkg == null ? "" : pkg.toString())
                + "|"
                + (viewId == null ? "" : viewId)
                + "|"
                + (clazz == null ? "" : clazz.toString())
                + "|"
                + bounds.left
                + ","
                + bounds.top
                + ","
                + bounds.right
                + ","
                + bounds.bottom;
    }

    private static JSONObject focusedInputError(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false);
            out.put("error", code);
        } catch (Exception ignored) {}
        return out;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        isRunning = true;
        learnedUiMappingStore = new LearnedUiMappingStore(this);
        uiTeachOverlay = new UiTeachOverlay(this);
        appCatalog = new AppCatalog(this);
        appCatalog.prewarm();
        startLocalServer();

        // 0025: Accessibility no longer owns microphone or Wake Word lifecycle.
        // Always-On is explicitly enabled from the app and owned by NativeLiveService.
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (learnedUiMappingStore == null) learnedUiMappingStore = new LearnedUiMappingStore(this);
        if (uiTeachOverlay == null) uiTeachOverlay = new UiTeachOverlay(this);
        if (appCatalog == null) { appCatalog = new AppCatalog(this); appCatalog.prewarm(); }
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                info = new AccessibilityServiceInfo();
            }
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        } catch (Exception ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Input-method companion was removed; accessibility remains available
        // for the normal Crew runtime only.
    }

    @Override
    public void onInterrupt() {}

    private android.os.PowerManager.WakeLock screenWakeLock = null;

    private synchronized boolean setScreenKeepAwake(boolean enable) {
        try {
            if (enable) {
                if (screenWakeLock == null) {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        screenWakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ON_AFTER_RELEASE,
                            "CrewPocket:ScreenKeepAwake"
                        );
                        screenWakeLock.setReferenceCounted(false);
                    }
                }
                if (screenWakeLock != null && !screenWakeLock.isHeld()) {
                    screenWakeLock.acquire(4 * 60 * 60 * 1000L); // Max 4h safety timeout
                }
            } else {
                if (screenWakeLock != null && screenWakeLock.isHeld()) {
                    screenWakeLock.release();
                }
            }
        } catch (SecurityException error) {
            Log.e("CrewAccessibility", "Keep Awake requires WAKE_LOCK permission", error);
        } catch (Exception error) {
            Log.e("CrewAccessibility", "Unable to change Keep Awake state", error);
        }
        return screenWakeLock != null && screenWakeLock.isHeld();
    }

    public static boolean isKeepAwakeActive() {
        CrewAccessibilityService service = instance;
        return service != null && service.screenWakeLock != null && service.screenWakeLock.isHeld();
    }

    public static boolean toggleKeepAwake() {
        CrewAccessibilityService service = instance;
        if (service != null) {
            boolean next = !isKeepAwakeActive();
            return service.setScreenKeepAwake(next);
        }
        return false;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        setScreenKeepAwake(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {}
        try {
            FloatingBubbleManager manager = FloatingBubbleManager.getInstance(this);
            manager.hideBubble();
        } catch (Exception ignored) {}
        instance = null;
        super.onDestroy();
    }

    private void startLocalServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!AppConfig.isLocalBridgeEnabled(CrewAccessibilityService.this)) {
                        Log.i(TAG, "Local bridge is disabled in AppConfig, server not started");
                        return;
                    }
                    if (serverSocket != null) {
                        try { serverSocket.close(); } catch (Exception e) {}
                    }
                    // Bind explicitly to IPv4 loopback. getLoopbackAddress() can
                    // resolve to ::1 on Samsung, while all in-app bridge clients
                    // intentionally use 127.0.0.1:8766.
                    serverSocket = new ServerSocket(PORT, 50, java.net.InetAddress.getByName("127.0.0.1"));
                    while (isRunning && !serverSocket.isClosed()) {
                        final Socket socket = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleSocketRequest(socket);
                            }
                        }).start();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    private void copyContentUri(String uriString, File dst) throws Exception {
        InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uriString));
        if (in == null) throw new Exception("無法開啟截圖內容 URI");
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        in.close();
        out.close();
    }

    // Keep AI-working captures inside this app's sandbox. Shared Pictures files
    // can survive an uninstall while their MediaStore ownership does not, which
    // leaves a reinstalled helper unable to read a stale "latest" screenshot.
    private File getCaptureDirectory() throws Exception {
        File dir = new File(getFilesDir(), "captures");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("無法建立私有截圖目錄");
        return dir;
    }

    /**
     * Android 11+ accessibility screenshot API. Unlike GLOBAL_ACTION_TAKE_SCREENSHOT,
     * it captures in the background and does not show the system screenshot flash.
     * Reflection keeps this helper buildable with the local API-24 android.jar.
     */
    private boolean requestSilentScreenshot(final Object lock, final String[] result) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            final Class<?> callbackClass = Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback");
            final Method takeScreenshot = AccessibilityService.class.getMethod(
                    "takeScreenshot", Integer.TYPE, Executor.class, callbackClass);
            final Executor executor = new Executor() {
                @Override public void execute(Runnable command) { mainHandler.post(command); }
            };
            final Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                if ("onSuccess".equals(method.getName()) && args != null && args.length > 0) {
                                    File dir = getCaptureDirectory();
                                    File latest = new File(dir, "latest_screen_photo.jpg");
                                    saveSilentScreenshotResult(args[0], latest);
                                    result[0] = "{\"success\":true,\"path\":\"" + latest.getAbsolutePath()
                                            + "\",\"latestPath\":\"" + latest.getAbsolutePath() + "\",\"silent\":true}";
                                } else if ("onFailure".equals(method.getName())) {
                                    result[0] = "{\"success\":false,\"error\":\"背景截圖失敗\"}";
                                }
                            } catch (Exception e) {
                                result[0] = "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
                            } finally {
                                synchronized (lock) { lock.notify(); }
                            }
                            return null;
                        }
                    });
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        // 0 is the default display ID.
                        takeScreenshot.invoke(CrewAccessibilityService.this, 0, executor, callback);
                    } catch (Exception e) {
                        result[0] = "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
                        synchronized (lock) { lock.notify(); }
                    }
                }
            });
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveSilentScreenshotResult(Object screenshotResult, File destination) throws Exception {
        Method getHardwareBuffer = screenshotResult.getClass().getMethod("getHardwareBuffer");
        Method getColorSpace = screenshotResult.getClass().getMethod("getColorSpace");
        Object hardwareBuffer = getHardwareBuffer.invoke(screenshotResult);
        Object colorSpace = getColorSpace.invoke(screenshotResult);
        Class<?> hardwareBufferClass = Class.forName("android.hardware.HardwareBuffer");
        Class<?> colorSpaceClass = Class.forName("android.graphics.ColorSpace");
        Method wrapHardwareBuffer = Bitmap.class.getMethod("wrapHardwareBuffer", hardwareBufferClass, colorSpaceClass);
        Bitmap bitmap = (Bitmap) wrapHardwareBuffer.invoke(null, hardwareBuffer, colorSpace);
        if (bitmap == null) throw new Exception("背景截圖影像不可用");
        FileOutputStream out = new FileOutputStream(destination);
        try {
            // High-speed JPEG encoding (quality 80) reduces latency from ~2000ms (PNG) to ~25ms and eliminates streaming backlog
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)) throw new Exception("背景截圖儲存失敗");
        } finally {
            out.close();
            bitmap.recycle();
            try { hardwareBuffer.getClass().getMethod("close").invoke(hardwareBuffer); } catch (Exception ignored) {}
        }
    }

    private void writeBridgeHttpJsonAndClose(
            Socket socket,
            int statusCode,
            String statusText,
            String responseJson) {
        if (socket == null) return;
        String body = responseJson == null ? "{}" : responseJson;
        try {
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + statusCode + " " + statusText + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/json; charset=utf-8\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Cache-Control: no-store\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Connection: close\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Length: " + responseBytes.length + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(responseBytes);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleSocketRequest(final Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                socket.close();
                return;
            }

            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String bridgeToken = "";
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lowerLine = line.toLowerCase(Locale.ROOT);
                if (lowerLine.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception e) {}
                } else if (lowerLine.startsWith("x-crew-bridge-token:")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0 && colon + 1 < line.length()) {
                        bridgeToken = line.substring(colon + 1).trim();
                    }
                }
            }

            if (!AppConfig.isLocalBridgeTokenValid(
                    CrewAccessibilityService.this, bridgeToken)) {
                writeBridgeHttpJsonAndClose(
                        socket,
                        401,
                        "Unauthorized",
                        "{\"success\":false,\"error\":\"UNAUTHORIZED_LOCAL_BRIDGE\"}");
                return;
            }

            if (path.startsWith("/notify")) {
                writeBridgeHttpJsonAndClose(
                        socket,
                        410,
                        "Gone",
                        "{\"success\":false,\"error\":\"LEGACY_NOTIFY_REMOVED\"}");
                return;
            }

            StringBuilder bodyBuilder = new StringBuilder();
            if (contentLength > 0) {
                // HTTP Content-Length is measured in bytes, not Java UTF-16 chars.
                // Reading a Chinese JSON payload by char count made the request wait forever.
                char[] buf = new char[Math.min(contentLength, 1024)];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int r = reader.read(buf, 0, Math.min(buf.length, contentLength - bytesRead));
                    if (r == -1) break;
                    String chunk = new String(buf, 0, r);
                    bodyBuilder.append(chunk);
                    bytesRead += chunk.getBytes(StandardCharsets.UTF_8).length;
                }
            }
            String body = bodyBuilder.toString();

            String responseJson = "{\"status\":\"OK\"}";
            if (path.startsWith("/status")) {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                responseJson = "{\"active\":true,\"service\":\"CrewAccessibilityService\",\"port\":8766,\"screenWidth\":" + metrics.widthPixels + ",\"screenHeight\":" + metrics.heightPixels + "}";
            } else if (path.startsWith("/volume")) {
                try {
                    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    if ("POST".equalsIgnoreCase(method) && body.contains("\"percent\":")) {
                        int start = body.indexOf("\"percent\":") + 10;
                        int requestedPercent = Integer.parseInt(body.substring(start).split("[,}]")[0].trim());
                        requestedPercent = Math.max(0, Math.min(100, requestedPercent));
                        int targetVolume = Math.round(maxVolume * requestedPercent / 100.0f);
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
                    }
                    int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int percent = maxVolume > 0 ? Math.round(currentVolume * 100.0f / maxVolume) : 0;
                    responseJson = "{\"success\":true,\"stream\":\"music\",\"current\":" + currentVolume
                            + ",\"max\":" + maxVolume + ",\"percent\":" + percent + "}";
                } catch (Exception e) {
                    responseJson = "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
                }
            } else if (path.startsWith("/screenshot")) {
                final Object lock = new Object();
                final String[] result = new String[]{"{\"success\":false,\"error\":\"Screenshot failed\"}"};
                final long captureStartedAt = System.currentTimeMillis();

                if (requestSilentScreenshot(lock, result)) {
                    synchronized (lock) {
                        try { lock.wait(3500); } catch (Exception ignored) {}
                    }
                    responseJson = result[0];
                } else {
                performGlobalAction(9);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(700); // wait for Android system screenshot write
                            File dir = getCaptureDirectory();
                            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                            String fileName = "SCREEN_" + timeStamp + ".png";
                            File destFile = new File(dir, fileName);
                            File latestFile = new File(dir, "latest_screen_photo.jpg");

                            File[] searchDirs = new File[]{
                                new File("/sdcard/DCIM/Screenshots"),
                                new File("/sdcard/Pictures/Screenshots")
                            };
                            File newest = null;
                            String newestUri = null;
                            // Android scoped-storage can report an unreliable
                            // lastModified value. The screenshot action above
                            // is synchronous from the caller's perspective;
                            // choose the newest media file after its delay.
                            long lastMod = 0;

                            // Resolve the new screenshot through MediaStore;
                            // direct File.listFiles() may be empty on newer
                            // Android releases even when the file exists.
                            Cursor media = null;
                            try {
                                String[] projection = {
                                        MediaStore.Images.Media._ID,
                                        MediaStore.Images.Media.DISPLAY_NAME,
                                        MediaStore.Images.Media.DATE_MODIFIED
                                };
                                media = getContentResolver().query(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        projection, null, null,
                                        MediaStore.Images.Media.DATE_MODIFIED + " DESC");
                                if (media != null) {
                                    int idCol = media.getColumnIndex(MediaStore.Images.Media._ID);
                                    int nameCol = media.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                                    int dateCol = media.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
                                    while (media.moveToNext()) {
                                        String name = nameCol >= 0 ? media.getString(nameCol) : "";
                                        long modified = dateCol >= 0 ? media.getLong(dateCol) * 1000L : 0L;
                                        if (name != null && name.startsWith("Screenshot_") && modified >= captureStartedAt - 5000) {
                                            if (idCol >= 0) {
                                                long id = media.getLong(idCol);
                                                newestUri = ContentUris.withAppendedId(
                                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString();
                                                newest = null;
                                                lastMod = modified;
                                                break;
                                            }
                                        }
                                    }
                                }
                            } finally {
                                if (media != null) media.close();
                            }

                            for (File d : searchDirs) {
                                if (d.exists() && d.isDirectory()) {
                                    File[] files = d.listFiles();
                                    if (files != null) {
                                        for (File f : files) {
                                            if (f.isFile() && f.lastModified() > lastMod &&
                                                    (f.getName().toLowerCase(Locale.US).endsWith(".png") ||
                                                     f.getName().toLowerCase(Locale.US).endsWith(".jpg") ||
                                                     f.getName().toLowerCase(Locale.US).endsWith(".webp"))) {
                                                lastMod = f.lastModified();
                                                newest = f;
                                            }
                                        }
                                    }
                                }
                            }

                            if (newestUri != null) {
                                copyContentUri(newestUri, destFile);
                                copyFile(destFile, latestFile);
                                result[0] = "{\"success\":true,\"path\":\"" + destFile.getAbsolutePath() + "\",\"latestPath\":\"" + latestFile.getAbsolutePath() + "\"}";
                            } else if (newest != null && newest.exists()) {
                                copyFile(newest, destFile);
                                copyFile(newest, latestFile);
                                result[0] = "{\"success\":true,\"path\":\"" + destFile.getAbsolutePath() + "\",\"latestPath\":\"" + latestFile.getAbsolutePath() + "\"}";
                            } else {
                                result[0] = "{\"success\":false,\"error\":\"未找到本次新產生的截圖檔案\"}";
                            }
                        } catch (Exception e) {
                            result[0] = "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
                        } finally {
                            synchronized (lock) {
                                lock.notify();
                            }
                        }
                    }
                }).start();

                synchronized (lock) {
                    try {
                        lock.wait(3500);
                    } catch (Exception ignored) {}
                }
                responseJson = result[0];
                }
            } else if (path.startsWith("/photo")) {
                final boolean isFront = body.toLowerCase().contains("\"front\"") || body.toLowerCase().contains("\"camera\":\"front\"");
                final Object lock = new Object();
                final String[] result = new String[]{"{\"success\":false,\"error\":\"Timeout\"}"};

                CameraCaptureManager.capturePhoto(CrewAccessibilityService.this, isFront, new CameraCaptureManager.CaptureCallback() {
                    @Override
                    public void onSuccess(String filePath) {
                        result[0] = "{\"success\":true,\"path\":\"" + filePath + "\",\"facing\":\"" + (isFront ? "front" : "back") + "\"}";
                        synchronized (lock) {
                            lock.notify();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        result[0] = "{\"success\":false,\"error\":\"" + error.replace("\"", "\\\"") + "\"}";
                        synchronized (lock) {
                            lock.notify();
                        }
                    }
                });

                synchronized (lock) {
                    try {
                        lock.wait(4500);
                    } catch (Exception ignored) {}
                }
                responseJson = result[0];
            } else if (path.startsWith("/bubble")) {
                mainHandler.post(new Runnable() {
                    @Override public void run() { FloatingBubbleManager.getInstance(CrewAccessibilityService.this).showBubble(); }
                });
                responseJson = "{\"success\":true,\"action\":\"BUBBLE_SHOWN\"}";
            } else if (path.startsWith("/teach_ui")) {
                String role = "COMPOSER_SEND";
                try {
                    String parsed = getJsonString(body, "role");
                    if (parsed != null && !parsed.isEmpty()) role = parsed;
                } catch (Exception ignored) {}
                final String fRole = role;
                mainHandler.post(new Runnable() {
                    @Override public void run() { beginTeachElement(fRole); }
                });
                responseJson = "{\"success\":true,\"message\":\"已開啟 UI 教導模式，請點選目標元件\",\"role\":\"" + role + "\"}";
            } else if (path.startsWith("/tap")) {
                float x = 0, y = 0;
                try {
                    int xIdx = body.indexOf("\"x\":");
                    int yIdx = body.indexOf("\"y\":");
                    if (xIdx != -1 && yIdx != -1) {
                        x = Float.parseFloat(body.substring(xIdx + 4).split("[,}]")[0].trim());
                        y = Float.parseFloat(body.substring(yIdx + 4).split("[,}]")[0].trim());
                    }
                } catch (Exception e) {}

                PolicyEngine.Result tapPolicy = evaluateTapPolicy(x, y);
                if (tapPolicy.blocked()) {
                    writeJsonAndClose(socket, policyBlockJson(tapPolicy));
                    return;
                }

                final float fx = x, fy = y;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        performTap(fx, fy);
                    }
                });
                responseJson = "{\"success\":true,\"action\":\"TAP\",\"x\":" + x + ",\"y\":" + y + "}";
            } else if (path.startsWith("/click_v2")) {
                final String label = getJsonString(body, "label");
                final String id = getJsonString(body, "id");
                final String hint = getJsonString(body, "semanticHint");
                final String role = getJsonString(body, "role");
                final String elementId = getJsonString(body, "elementId");
                PolicyEngine.Result policy = PolicyEngine.evaluate("click", label, id, false);
                if (policy.blocked()) {
                    writeJsonAndClose(socket, policyBlockJson(policy));
                    return;
                }
                final JSONObject[] clickResult = new JSONObject[]{new JSONObject().put("success", false).put("error", "BRIDGE_ROUTE_UNAVAILABLE")};
                final Object clickLock = new Object();
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        UiLocatorV2.Match match = null;
                        try {
                            UiTargetSpec spec = UiTargetSpec.builder()
                                    .actionKind(UiTargetSpec.ActionKind.TAP)
                                    .label(label).viewId(id).elementId(elementId)
                                    .semanticHint(hint).role(role).build();
                            match = UiLocatorV2.resolve(root, spec);
                            JSONObject out = new JSONObject().put("success", false)
                                    .put("action", "NODE_CLICK_V2")
                                    .put("decision", match.decision.name())
                                    .put("confidence", match.confidence)
                                    .put("runnerUpConfidence", match.runnerUpConfidence)
                                    .put("code", match.code)
                                    .put("source", match.source);
                            if (match.autoExecutable()) {
                                boolean clicked = false;
                                try {
                                    if (!SensitiveDataGuard.isBlockedAction(match.node)) {
                                        clicked = match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    }
                                } finally { match.recycle(); match = null; }
                                out.put("success", clicked);
                                if (!clicked) out.put("error", "NODE_CLICK_REJECTED");
                            } else {
                                out.put("error", match.code);
                            }
                            clickResult[0] = out;
                        } catch (Exception e) {
                            clickResult[0] = new JSONObject();
                            try { clickResult[0].put("success", false).put("error", "V2_LOCATOR_ERROR"); }
                            catch (Exception ignored) {}
                            if (match != null) match.recycle();
                        } finally {
                            if (root != null) root.recycle();
                            synchronized (clickLock) { clickLock.notify(); }
                        }
                    }
                });
                synchronized (clickLock) { try { clickLock.wait(1500); } catch (Exception ignored) {} }
                responseJson = clickResult[0].toString();
            } else if (path.startsWith("/click")) {
                final String label = getJsonString(body, "label");
                final String id = getJsonString(body, "id");
                PolicyEngine.Result policy = PolicyEngine.evaluate("click", label, id, false);
                if (policy.blocked()) {
                    writeJsonAndClose(socket, policyBlockJson(policy));
                    return;
                }
                final boolean[] clickSuccess = new boolean[]{false};
                final Object clickLock = new Object();
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { clickSuccess[0] = performClickByTarget(label, id); }
                        finally { synchronized (clickLock) { clickLock.notify(); } }
                    }
                });
                synchronized (clickLock) { try { clickLock.wait(1500); } catch (Exception ignored) {} }
                responseJson = "{\"success\":" + clickSuccess[0] + ",\"action\":\"NODE_CLICK\",\"label\":\"" + jsonEscape(label) + "\",\"id\":\"" + jsonEscape(id) + "\"}";
            } else if (path.startsWith("/scroll")) {
                String direction = getJsonString(body, "direction");
                final String targetId = getJsonString(body, "id");
                if (direction == null || direction.isEmpty()) direction = "up";
                final String fDir = direction.toLowerCase(Locale.ROOT);
                final boolean[] scrollSuccess = new boolean[]{false};
                final Object scrollLock = new Object();
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if ("up".equals(fDir) || "forward".equals(fDir)) {
                                scrollSuccess[0] = performScrollAction(true, targetId);
                            } else if ("down".equals(fDir) || "backward".equals(fDir)) {
                                scrollSuccess[0] = performScrollAction(false, targetId);
                            }
                            if (!scrollSuccess[0]) {
                                // Fallback to proportional gesture swipe
                                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                                int w = metrics.widthPixels, h = metrics.heightPixels;
                                float x1 = w * 0.5f, y1 = h * 0.72f, x2 = w * 0.5f, y2 = h * 0.28f;
                                if ("down".equals(fDir) || "backward".equals(fDir)) {
                                    y1 = h * 0.28f; y2 = h * 0.72f;
                                } else if ("left".equals(fDir)) {
                                    x1 = w * 0.85f; y1 = h * 0.5f; x2 = w * 0.15f; y2 = h * 0.5f;
                                } else if ("right".equals(fDir)) {
                                    x1 = w * 0.15f; y1 = h * 0.5f; x2 = w * 0.85f; y2 = h * 0.5f;
                                }
                                performSwipe(x1, y1, x2, y2, 320);
                                scrollSuccess[0] = true;
                            }
                        } finally { synchronized (scrollLock) { scrollLock.notify(); } }
                    }
                });
                synchronized (scrollLock) { try { scrollLock.wait(1500); } catch (Exception ignored) {} }
                responseJson = "{\"success\":" + scrollSuccess[0] + ",\"action\":\"SCROLL\",\"direction\":\"" + fDir + (targetId != null ? "\",\"id\":\"" + jsonEscape(targetId) : "") + "\"}";
            } else if (path.startsWith("/swipe")) {
                float x1 = 0, y1 = 0, x2 = 0, y2 = 0;
                long duration = 300;
                try {
                    if (body.contains("\"x1\":")) x1 = Float.parseFloat(body.substring(body.indexOf("\"x1\":") + 5).split("[,}]")[0].trim());
                    if (body.contains("\"y1\":")) y1 = Float.parseFloat(body.substring(body.indexOf("\"y1\":") + 5).split("[,}]")[0].trim());
                    if (body.contains("\"x2\":")) x2 = Float.parseFloat(body.substring(body.indexOf("\"x2\":") + 5).split("[,}]")[0].trim());
                    if (body.contains("\"y2\":")) y2 = Float.parseFloat(body.substring(body.indexOf("\"y2\":") + 5).split("[,}]")[0].trim());
                    if (body.contains("\"duration\":")) duration = Long.parseLong(body.substring(body.indexOf("\"duration\":") + 11).split("[,}]")[0].trim());
                } catch (Exception e) {}

                final float fx1 = x1, fy1 = y1, fx2 = x2, fy2 = y2;
                final long fDur = duration;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        performSwipe(fx1, fy1, fx2, fy2, fDur);
                    }
                });
                responseJson = "{\"success\":true,\"action\":\"SWIPE\"}";
            } else if (path.startsWith("/type")) {
                String textToType = getJsonString(body, "text");
                if (textToType == null && body.contains("\"text\":")) {
                    try {
                        int sIdx = body.indexOf("\"text\":") + 7;
                        textToType = body.substring(sIdx).split("[,}]")[0].replace("\"", "").trim();
                    } catch (Exception ignored) {}
                }
                final String fText = textToType == null ? "" : textToType;
                PolicyEngine.Result policy = PolicyEngine.evaluate("type", "", "", isActiveInputHardBlocked());
                if (policy.blocked()) {
                    writeJsonAndClose(socket, policyBlockJson(policy));
                    return;
                }
                final boolean[] typeSuccess = new boolean[]{false};
                final Object typeLock = new Object();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            typeSuccess[0] = performSetText(fText);
                        } finally {
                            synchronized (typeLock) { typeLock.notify(); }
                        }
                    }
                });
                synchronized (typeLock) {
                    try { typeLock.wait(1500); } catch (Exception ignored) {}
                }
                // Never echo user-entered text back into the model/tool result.
                responseJson = "{\"success\":" + typeSuccess[0]
                        + ",\"action\":\"TYPE\",\"textLength\":" + fText.length()
                        + ",\"method\":\"" + jsonEscape(lastTextInputMethod) + "\""
                        + ",\"verified\":" + lastTextInputVerified
                        + (typeSuccess[0] ? "" : ",\"error\":\"" + jsonEscape(lastTextInputFailure) + "\"")
                        + "}";
            } else if (path.startsWith("/commit_search")) {
                final String[] commitResult = new String[]{
                        "{\"success\":false,\"action\":\"SEARCH_COMMIT\",\"error\":\"TIMEOUT\"}"};
                final Object commitLock = new Object();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            commitResult[0] = SearchCommitRuntime
                                    .commit(CrewAccessibilityService.this).toString();
                        } catch (Exception error) {
                            commitResult[0] =
                                    "{\"success\":false,\"action\":\"SEARCH_COMMIT\",\"error\":\"RUNTIME_ERROR\"}";
                        } finally {
                            synchronized (commitLock) { commitLock.notify(); }
                        }
                    }
                });
                synchronized (commitLock) {
                    try { commitLock.wait(1800); } catch (Exception ignored) {}
                }
                responseJson = commitResult[0];
            } else if (path.startsWith("/search_in_app")) {
                final String query = getJsonString(body, "query");
                final String[] searchResult = new String[]{
                        "{\"success\":false,\"action\":\"APP_SEARCH\",\"error\":\"TIMEOUT\"}"};
                final Object searchLock = new Object();
                final Runnable[] retry = new Runnable[1];
                retry[0] = new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject result = AppSearchRuntime.execute(
                                    CrewAccessibilityService.this,
                                    query == null ? "" : query);
                            if ("WAITING_FOR_FOCUS".equals(result.optString("state", ""))) {
                                mainHandler.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            JSONObject followUp = AppSearchRuntime.execute(
                                                    CrewAccessibilityService.this,
                                                    query == null ? "" : query);
                                            if ("WAITING_FOR_FOCUS".equals(followUp.optString("state", ""))) {
                                                try {
                                                    followUp.put("success", false)
                                                            .put("error", "SEARCH_INPUT_FOCUS_TIMEOUT")
                                                            .put("instruction", "搜尋入口已點擊但沒有出現可輸入欄位；不要假裝已輸入。");
                                                } catch (Exception ignored) {}
                                            }
                                            searchResult[0] = followUp.toString();
                                        } finally {
                                            synchronized (searchLock) { searchLock.notify(); }
                                        }
                                    }
                                }, 420L);
                                return;
                            }
                            searchResult[0] = result.toString();
                        } catch (Exception error) {
                            searchResult[0] = "{\"success\":false,\"action\":\"APP_SEARCH\",\"error\":\"RUNTIME_ERROR\"}";
                        } finally {
                            synchronized (searchLock) { searchLock.notify(); }
                        }
                    }
                };
                mainHandler.post(retry[0]);
                synchronized (searchLock) {
                    try { searchLock.wait(2600); } catch (Exception ignored) {}
                }
                responseJson = searchResult[0];
            } else if (path.startsWith("/search_result_candidates")) {
                final String query = getJsonString(body, "query");
                final String[] selectionResult = new String[]{
                        "{\"success\":true,\"state\":\"WAITING_RESULTS\",\"reason\":\"TIMEOUT\"}"};
                final Object selectionLock = new Object();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            selectionResult[0] = SearchResultSelectionRuntime
                                    .analyze(CrewAccessibilityService.this,
                                            query == null ? "" : query)
                                    .toString();
                        } catch (Exception error) {
                            selectionResult[0] =
                                    "{\"success\":true,\"state\":\"WAITING_RESULTS\",\"reason\":\"RUNTIME_ERROR\"}";
                        } finally {
                            synchronized (selectionLock) { selectionLock.notify(); }
                        }
                    }
                });
                synchronized (selectionLock) {
                    try { selectionLock.wait(1800); } catch (Exception ignored) {}
                }
                responseJson = selectionResult[0];
            } else if (path.startsWith("/send_current")) {
                PolicyEngine.Result policy =
                        PolicyEngine.evaluate("type", "", "", isActiveInputHardBlocked());
                if (policy.blocked()) {
                    writeJsonAndClose(socket, policyBlockJson(policy));
                    return;
                }
                responseJson = performSendCurrentComposerSimple().toString();
            } else if (path.startsWith("/send_text")) {
                final String textToSend = getJsonString(body, "text");
                final boolean useExistingComposer =
                        body != null && body.contains("\"useExistingComposer\":true");

                PolicyEngine.Result policy =
                        PolicyEngine.evaluate("type", "", "", isActiveInputHardBlocked());
                if (policy.blocked()) {
                    writeJsonAndClose(socket, policyBlockJson(policy));
                    return;
                }

                SendTextTransaction.Result sendResult;
                if (useExistingComposer) {
                    sendResult = performSendCurrentComposerTransaction();
                } else if (textToSend == null || textToSend.length() == 0) {
                    sendResult = SendTextTransaction.Result.failure(
                            "VALIDATE", "EMPTY_TEXT");
                } else {
                    sendResult = performSendTextTransaction(textToSend);
                }
                responseJson = sendResult.toJson().toString();
            } else if (path.startsWith("/schedule/create")) {
                String type = getJsonString(body, "type");
                String label = getJsonString(body, "label");
                String message = getJsonString(body, "message");
                String condition = getJsonString(body, "condition");
                int delaySec = 0;
                int intervalSec = 60;
                int durationMin = 10;
                boolean speech = body.contains("\"speech\":true") || body.contains("\"report_speech\":true");
                try {
                    if (body.contains("\"delay_seconds\":")) delaySec = Integer.parseInt(body.substring(body.indexOf("\"delay_seconds\":") + 16).split("[,}]")[0].trim());
                    if (body.contains("\"interval_seconds\":")) intervalSec = Integer.parseInt(body.substring(body.indexOf("\"interval_seconds\":") + 19).split("[,}]")[0].trim());
                    if (body.contains("\"duration_minutes\":")) durationMin = Integer.parseInt(body.substring(body.indexOf("\"duration_minutes\":") + 19).split("[,}]")[0].trim());
                } catch (Exception ignored) {}

                ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(this);
                ScheduledTaskManager.ScheduledTask task;
                if ("screen_monitor".equalsIgnoreCase(type) || "condition_wait".equalsIgnoreCase(type) || (condition != null && !condition.trim().isEmpty())) {
                    task = mgr.startScreenMonitor(label, intervalSec, durationMin, condition, speech);
                } else {
                    task = mgr.scheduleReminder(label, delaySec > 0 ? delaySec : 60, message);
                }
                responseJson = "{\"success\":true,\"task\":" + task.toJson().toString() + "}";
            } else if (path.startsWith("/schedule/list")) {
                ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(this);
                responseJson = "{\"success\":true,\"tasks\":" + mgr.getActiveTasksJson().toString() + ",\"summary\":\"" + jsonEscape(mgr.getActiveTasksSummaryText()) + "\"}";
            } else if (path.startsWith("/schedule/cancel")) {
                String id = getJsonString(body, "id");
                boolean all = body.contains("\"all\":true") || body.contains("\"cancel_all\":true");
                ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(this);
                if (all) {
                    int count = mgr.cancelAllTasks();
                    responseJson = "{\"success\":true,\"cancelledCount\":" + count + ",\"message\":\"已取消所有排程與計時器\"}";
                } else {
                    boolean cancelled = mgr.cancelTask(id);
                    responseJson = "{\"success\":" + cancelled + ",\"id\":\"" + (id != null ? jsonEscape(id) : "") + "\",\"message\":\"" + (cancelled ? "已取消該排程" : "找不到指定計時器") + "\"}";
                }
            } else if (path.startsWith("/keep_awake")) {
                boolean enable = body.contains("\"enabled\":true") || body.contains("\"enable\":true");
                boolean active = setScreenKeepAwake(enable);
                responseJson = "{\"success\":" + (active == enable) + ",\"keepAwake\":" + active + "}";
            } else if (path.startsWith("/key")) {
                String key = "HOME";
                if (body.contains("\"BACK\"")) key = "BACK";
                else if (body.contains("\"RECENTS\"")) key = "RECENTS";
                else if (body.contains("\"NOTIFICATIONS\"")) key = "NOTIFICATIONS";
                else if (body.contains("\"QUICK_SETTINGS\"")) key = "QUICK_SETTINGS";
                else if (body.contains("\"POWER_DIALOG\"")) key = "POWER_DIALOG";
                else if (body.contains("\"SCREENSHOT\"")) key = "SCREENSHOT";
                else if (body.contains("\"HOME\"")) key = "HOME";

                final String fKey = key;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if ("HOME".equalsIgnoreCase(fKey)) performGlobalAction(GLOBAL_ACTION_HOME);
                        else if ("BACK".equalsIgnoreCase(fKey)) performGlobalAction(GLOBAL_ACTION_BACK);
                        else if ("RECENTS".equalsIgnoreCase(fKey)) performGlobalAction(GLOBAL_ACTION_RECENTS);
                        else if ("NOTIFICATIONS".equalsIgnoreCase(fKey)) performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                        else if ("QUICK_SETTINGS".equalsIgnoreCase(fKey)) performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                        else if ("POWER_DIALOG".equalsIgnoreCase(fKey)) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                        else if ("SCREENSHOT".equalsIgnoreCase(fKey)) performGlobalAction(9);
                    }
                });
                responseJson = "{\"success\":true,\"action\":\"KEY\",\"key\":\"" + key + "\"}";
            } else if (path.startsWith("/launch")) {
                final String appName = getJsonString(body, "app");
                final String packageName = getJsonString(body, "package");
                final String url = getJsonString(body, "url");
                final String target = getJsonString(body, "target");
                final long requestStartedAt = System.currentTimeMillis();

                String resolvedPackage = packageName == null ? "" : packageName.trim();
                String resolvedLabel = "";
                if ((url == null || url.trim().isEmpty()) && !"settings".equalsIgnoreCase(target) && resolvedPackage.isEmpty()) {
                    if (appName == null || appName.trim().isEmpty()) {
                        responseJson = "{\"success\":false,\"error\":\"APP_NAME_REQUIRED\"}";
                    } else {
                        if (appCatalog == null) appCatalog = new AppCatalog(this);
                        AppCatalog.Resolution resolution = appCatalog.resolve(appName);
                        if (AppCatalog.Resolution.MULTIPLE.equals(resolution.status)) {
                            StringBuilder json = new StringBuilder("{\"success\":false,\"status\":\"MULTIPLE_MATCHES\",\"resolveMs\":").append(resolution.resolveMs).append(",\"matches\":[");
                            for (int i = 0; i < resolution.matches.size(); i++) {
                                if (i > 0) json.append(',');
                                AppCatalog.Entry e = resolution.matches.get(i);
                                json.append("{\"label\":\"").append(jsonEscape(e.label)).append("\",\"package\":\"").append(jsonEscape(e.packageName)).append("\"}");
                            }
                            json.append("]}");
                            responseJson = json.toString();
                        } else if (AppCatalog.Resolution.NOT_FOUND.equals(resolution.status) || resolution.chosen == null) {
                            responseJson = "{\"success\":false,\"error\":\"APP_NOT_FOUND\",\"resolveMs\":" + resolution.resolveMs + "}";
                        } else {
                            resolvedPackage = resolution.chosen.packageName;
                            resolvedLabel = resolution.chosen.label;
                        }
                    }
                }

                if (!resolvedPackage.isEmpty() || (url != null && !url.trim().isEmpty()) || "settings".equalsIgnoreCase(target)) {
                    final String fResolvedPackage = resolvedPackage;
                    final boolean[] launchSuccess = new boolean[]{false};
                    final Object launchLock = new Object();
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            try {
                                Intent intent = null;
                                if (url != null && !url.trim().isEmpty()) intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url.trim()));
                                else if ("settings".equalsIgnoreCase(target)) intent = new Intent(Settings.ACTION_SETTINGS);
                                else if (!fResolvedPackage.isEmpty()) intent = getPackageManager().getLaunchIntentForPackage(fResolvedPackage);
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(intent);
                                    launchSuccess[0] = true;
                                }
                            } catch (Exception ignored) {}
                            finally { synchronized (launchLock) { launchLock.notify(); } }
                        }
                    });
                    synchronized (launchLock) { try { launchLock.wait(1000); } catch (Exception ignored) {} }
                    responseJson = "{\"success\":" + launchSuccess[0] + ",\"action\":\"LAUNCH\",\"package\":\"" + jsonEscape(resolvedPackage)
                            + "\",\"label\":\"" + jsonEscape(resolvedLabel) + "\",\"runtimeMs\":" + (System.currentTimeMillis() - requestStartedAt) + "}";
                }
            } else if (path.startsWith("/apps")) {
                String query = getJsonString(body, "query");
                if (appCatalog == null) appCatalog = new AppCatalog(this);
                ArrayList<AppCatalog.Entry> matches = appCatalog.search(query);
                StringBuilder apps = new StringBuilder("{\"success\":true,\"cached\":true,\"matches\":[");
                for (int i = 0; i < matches.size(); i++) {
                    if (i > 0) apps.append(',');
                    AppCatalog.Entry e = matches.get(i);
                    apps.append("{\"label\":\"").append(jsonEscape(e.label)).append("\",\"package\":\"").append(jsonEscape(e.packageName)).append("\"}");
                }
                apps.append("]}");
                responseJson = apps.toString();
            } else if (path.startsWith("/screen_state")) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        CharSequence pkg = root.getPackageName();
                        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                        JSONArray actions = ActionRegistry.build(root);
                        String fingerprint = ScreenFingerprint.create(root);
                        StringBuilder sb = new StringBuilder();
                        sb.append("{\"success\":true,\"package\":\"")
                                .append(pkg != null ? jsonEscape(pkg.toString()) : "").append("\",");
                        sb.append("\"screenWidth\":").append(metrics.widthPixels)
                                .append(",\"screenHeight\":").append(metrics.heightPixels).append(",");
                        sb.append("\"fingerprint\":\"").append(jsonEscape(fingerprint)).append("\",");
                        sb.append("\"nodes\":[");
                        dumpNodesJson(root, sb);
                        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
                        sb.append("],\"actions\":").append(actions.toString()).append("}");
                        responseJson = sb.toString();
                    } finally {
                        root.recycle();
                    }
                } else {
                    responseJson = "{\"success\":false,\"error\":\"No active window found\"}";
                }
            } else if (path.startsWith("/semantic_screen")) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                try {
                    responseJson = SemanticScreenState.capture(root).toString();
                } finally {
                    if (root != null) root.recycle();
                }
            } else if (path.startsWith("/semantic_tap")) {
                final String elementId = getJsonString(body, "elementId");
                if (elementId == null || elementId.trim().isEmpty()) {
                    responseJson = "{\"success\":false,\"error\":\"MISSING_ELEMENT_ID\"}";
                } else {
                    final Object lock = new Object();
                    final boolean[] ok = new boolean[]{false};
                    final String[] error = new String[]{""};
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            AccessibilityNodeInfo root = getRootInActiveWindow();
                            AccessibilityNodeInfo resolved = null;
                            AccessibilityNodeInfo clickable = null;
                            try {
                                resolved = SemanticElementResolver.resolve(root, elementId);
                                if (resolved == null) {
                                    error[0] = "ELEMENT_STALE_OR_NOT_FOUND";
                                    return;
                                }
                                if (SensitiveDataGuard.isHardBlockedInput(resolved)) {
                                    error[0] = "SENSITIVE_TARGET_BLOCKED";
                                    return;
                                }
                                clickable = SemanticElementResolver.nearestClickable(resolved);
                                if (clickable == null) {
                                    error[0] = "ELEMENT_NOT_CLICKABLE";
                                    return;
                                }
                                if (SensitiveDataGuard.isBlockedAction(clickable)) {
                                    error[0] = "SENSITIVE_TARGET_BLOCKED";
                                    return;
                                }
                                ok[0] = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                if (!ok[0]) error[0] = "SEMANTIC_CLICK_REJECTED";
                            } catch (Exception e) {
                                error[0] = "SEMANTIC_CLICK_FAILED";
                            } finally {
                                if (clickable != null) clickable.recycle();
                                if (resolved != null) resolved.recycle();
                                if (root != null) root.recycle();
                                synchronized (lock) { lock.notify(); }
                            }
                        }
                    });
                    synchronized (lock) {
                        try { lock.wait(2500); } catch (Exception ignored) {}
                    }
                    responseJson = "{\"success\":" + ok[0]
                            + ",\"action\":\"SEMANTIC_TAP\",\"elementId\":\""
                            + jsonEscape(elementId) + "\""
                            + (error[0].isEmpty() ? "" : ",\"error\":\"" + jsonEscape(error[0]) + "\"")
                            + "}";
                }
            } else if (path.startsWith("/nodes") || path.startsWith("/screen_info")) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    CharSequence pkg = root.getPackageName();
                    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"success\":true,\"package\":\"").append(pkg != null ? jsonEscape(pkg.toString()) : "").append("\",");
                    sb.append("\"screenWidth\":").append(metrics.widthPixels).append(",\"screenHeight\":").append(metrics.heightPixels).append(",");
                    sb.append("\"nodes\":[");
                    dumpNodesJson(root, sb);
                    if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
                    sb.append("]}");
                    responseJson = sb.toString();
                    root.recycle();
                } else {
                    responseJson = "{\"success\":false,\"error\":\"No active window found\"}";
                }
            }

            byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Cache-Control: no-store\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Length: " + responseBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(responseBytes);
            out.flush();
            socket.close();
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /** Minimal JSON string reader for the helper's tiny local-only API. */
    private String getJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) return null;
        int colon = json.indexOf(':', keyIndex + marker.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) {
                switch (ch) {
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try { value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); i += 4; }
                            catch (Exception ignored) { value.append('u'); }
                        } else value.append('u');
                        break;
                    default: value.append(ch); break;
                }
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return value.toString();
            } else {
                value.append(ch);
            }
        }
        return null;
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** Matches localized labels and technical package names token by token.
     * For example, spoken "Google Map" matches com.google.android.apps.maps
     * even when the launcher label is the localized "地圖". */
    private boolean matchesAppQuery(String label, String packageName, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String haystack = ((label == null ? "" : label) + " " + (packageName == null ? "" : packageName)).toLowerCase(Locale.ROOT);
        String[] tokens = query.trim().split("[^\\p{L}\\p{N}]+");
        for (String token : tokens) {
            if (token.length() == 0) continue;
            if (!haystack.contains(token)) return false;
        }
        return true;
    }

    private void performTap(float x, float y) {
        if (evaluateTapPolicy(x, y).blocked()) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(builder.build(), null, null);
    }

    private boolean performClickByTarget(String label, String id) {
        if ((label == null || label.trim().isEmpty()) && (id == null || id.trim().isEmpty())) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo target = null;
            if (id != null && !id.trim().isEmpty()) {
                target = findMatchingNodeById(root, id.trim());
            }
            if (target == null && label != null && !label.trim().isEmpty()) {
                target = findMatchingClickableNode(root, label.trim(), true);
                if (target == null) target = findMatchingClickableNode(root, label.trim(), false);
                // ChatGPT and many modern composers expose only an icon.  When
                // the model clearly asks to send, rank the composer-side icons
                // instead of giving up because there is no visible text.
                if (target == null && isSendIntent(label, id)) target = findLikelySendButton(root);
            }
            if (target == null) return false;
            try {
                if (SensitiveDataGuard.isBlockedAction(target)) return false;
                Rect bounds = new Rect();
                target.getBoundsInScreen(bounds);
                boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                // Only fall back when semantic click was rejected.  Tapping
                // unconditionally after ACTION_CLICK can double-send a message.
                if (!clicked && bounds.width() > 0 && bounds.height() > 0) {
                    performTap(bounds.centerX(), bounds.centerY());
                    return true;
                }
                return clicked;
            } finally { target.recycle(); }
        } catch (Exception ignored) {
            return false;
        } finally {
            root.recycle();
        }
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> list) {
        if (node == null) return;
        if (node.isClickable()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            // Ignore giant full-screen containers
            if (b.width() > 0 && b.height() > 0 && (b.width() < 600 || b.height() < 400)) {
                list.add(AccessibilityNodeInfo.obtain(node));
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectClickableNodes(child, list);
                child.recycle();
            }
        }
    }

    AccessibilityNodeInfo findActiveEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> editList = new ArrayList<AccessibilityNodeInfo>();
        collectEditableNodes(root, editList);
        AccessibilityNodeInfo lowest = null;
        int maxBottom = -1;
        for (AccessibilityNodeInfo e : editList) {
            Rect b = new Rect();
            e.getBoundsInScreen(b);
            if (b.bottom > maxBottom && b.height() > 10) {
                maxBottom = b.bottom;
                if (lowest != null) lowest.recycle();
                lowest = AccessibilityNodeInfo.obtain(e);
            }
            e.recycle();
        }
        return lowest;
    }

    private void collectEditableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> list) {
        if (node == null) return;
        if (node.isEditable() || (node.getClassName() != null && node.getClassName().toString().toLowerCase(Locale.ROOT).contains("edittext"))) {
            list.add(AccessibilityNodeInfo.obtain(node));
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectEditableNodes(child, list);
                child.recycle();
            }
        }
    }

    private AccessibilityNodeInfo findMatchingNodeById(AccessibilityNodeInfo node, String id) {
        if (node == null) return null;
        CharSequence viewId = node.getViewIdResourceName();
        if (viewId != null && viewId.toString().toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT))) {
            AccessibilityNodeInfo clickable = findClickableAncestor(node);
            if (clickable != null) return clickable;
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo res = findMatchingNodeById(child, id);
                if (res != null) return res;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findMatchingClickableNode(AccessibilityNodeInfo node, String label, boolean exact) {
        if (node == null) return null;
        String query = label.toLowerCase(Locale.ROOT).trim();
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        String viewId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toString().trim();

        boolean matched = exact
                ? (text.equalsIgnoreCase(label) || desc.equalsIgnoreCase(label) || viewId.equalsIgnoreCase(label))
                : (text.toLowerCase(Locale.ROOT).contains(query) || desc.toLowerCase(Locale.ROOT).contains(query) || viewId.toLowerCase(Locale.ROOT).contains(query));

        if (!matched && !exact) {
            boolean isSendQuery = isSendIntent(label, "");
            if (isSendQuery) {
                String combined = (text + " " + desc + " " + viewId).toLowerCase(Locale.ROOT);
                if (hasSendMarker(combined)) {
                    matched = true;
                }
            }
        }

        if (matched) {
            AccessibilityNodeInfo clickable = findClickableAncestor(node);
            if (clickable != null) return clickable;
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo result = findMatchingClickableNode(child, label, exact);
                if (result != null) return result;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private boolean isSendIntent(String label, String id) {
        String query = ((label == null ? "" : label) + " " + (id == null ? "" : id)).toLowerCase(Locale.ROOT);
        return query.contains("發送") || query.contains("送出") || query.contains("傳送") || query.contains("send")
                || query.contains("提交") || query.contains("傳訊") || query.contains("reply");
    }

    private boolean hasSendMarker(String value) {
        return value.contains("發送") || value.contains("送出") || value.contains("傳送") || value.contains("提交")
                || value.contains("send") || value.contains("send-btn") || value.contains("send_button")
                || value.contains("composer_send") || value.contains("message_send") || value.contains("action_send")
                || value.contains("reply") || value.contains("arrow_upward") || value.contains("up_arrow");
    }

    private LearnedUiResolver.Match lastLearnedSendMatch = null;

    /**
     * Resolves a manually taught composer-send control before generic semantics.
     * A rule is scoped to the current app/screen and the composer-with-text state;
     * it is only clicked once by SendTextTransaction and must still verify.
     */
    private AccessibilityNodeInfo findLikelySendButton(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (lastLearnedSendMatch != null && lastLearnedSendMatch.node != null) {
            try { lastLearnedSendMatch.node.recycle(); } catch (Exception ignored) {}
        }
        lastLearnedSendMatch = null;
        try {
            AccessibilityNodeInfo composer = findActiveEditText(root);
            if (composer != null
                    && !"HAS_TEXT".equals(LearnedUiMappingStore.composerState(composer))) {
                composer.recycle();
                composer = null;
            }
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            String sig = ScreenFingerprint.create(root);
            java.util.List<LearnedUiMappingStore.Rule> rules = getLearnedUiMappingStore()
                    .findRules(pkg, sig, "COMPOSER_SEND");
            LearnedUiResolver.Match match = LearnedUiResolver.resolveAnchored(root, rules);
            if (match == null && composer != null) {
                match = LearnedUiResolver.resolve(root, rules, composer);
            }
            if (composer != null) composer.recycle();
            if (match != null && match.node != null) {
                lastLearnedSendMatch = match;
                return AccessibilityNodeInfo.obtain(match.node);
            }
        } catch (Exception ignored) {}
        return ComposerSendResolver.find(root);
    }

    void recordLastLearnedSendResult(boolean success) {
        LearnedUiResolver.Match match = lastLearnedSendMatch;
        lastLearnedSendMatch = null;
        if (match == null) return;
        try {
            if (match.rule != null) getLearnedUiMappingStore().recordResultByIdentity(match.rule, success);
        } catch (Exception ignored) {
        } finally {
            if (match.node != null) {
                try { match.node.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private AccessibilityNodeInfo getUnderlyingAppRootForTeach() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isUsableTeachRoot(active)) return active;
        if (active != null) active.recycle();

        // Fallback: inspect accessibility windows and choose the top-most
        // non-Crew-Helper application window.
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo w = windows.get(i);
                    if (w == null) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = w.getRoot();
                        if (isUsableTeachRoot(root)) return root;
                    } catch (Exception ignored) {}
                    if (root != null) root.recycle();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isUsableTeachRoot(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (pkg.isEmpty()) return false;
        // Never learn against our own teaching/control UI.
        return !getPackageName().equals(pkg);
    }

    private AccessibilityNodeInfo pendingTeachAnchor;

    private void clearPendingTeachAnchor() {
        if (pendingTeachAnchor != null) {
            try { pendingTeachAnchor.recycle(); } catch (Exception ignored) {}
            pendingTeachAnchor = null;
        }
    }

    private boolean beginTeachAnchoredElement(final String role) {
        if (uiTeachOverlay == null) uiTeachOverlay = new UiTeachOverlay(this);
        FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
        if (fb != null) {
            fb.showCompactStatus("第 1 步：請點基準點（建議點輸入框）", "");
        }
        return uiTeachOverlay.show(
            "第 1 步：請點基準點（建議點輸入框）",
            new UiTeachOverlay.Callback() {
                @Override
                public void onPicked(int screenX, int screenY) {
                    AccessibilityNodeInfo root = getUnderlyingAppRootForTeach();
                    if (root == null) {
                        FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                        if (fb != null) fb.showCompactStatus("找不到底層 App 畫面，請再試一次", "");
                        clearPendingTeachAnchor();
                        return;
                    }
                    AccessibilityNodeInfo hit = UiNodeHitTester.findBest(root, screenX, screenY);
                    if (hit == null) {
                        FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                        if (fb != null) fb.showCompactStatus("找不到基準點，請重試", "");
                        root.recycle();
                        clearPendingTeachAnchor();
                        return;
                    }
                    clearPendingTeachAnchor();
                    pendingTeachAnchor = AccessibilityNodeInfo.obtain(hit);
                    hit.recycle();
                    root.recycle();
                    FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                    if (fb != null) fb.showCompactStatus("第 2 步：請點真正要執行的送出按鈕", "");
                    beginTeachAnchoredTarget(role);
                }

                @Override
                public void onCancelled() {
                    clearPendingTeachAnchor();
                    FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                    if (fb != null) fb.showCompactStatus("已取消教學", "");
                }
            }
        );
    }

    private boolean beginTeachAnchoredTarget(final String role) {
        if (uiTeachOverlay == null) uiTeachOverlay = new UiTeachOverlay(this);
        return uiTeachOverlay.show(
            "第 2 步：請點真正要執行的送出按鈕",
            new UiTeachOverlay.Callback() {
                @Override
                public void onPicked(int screenX, int screenY) {
                    AccessibilityNodeInfo root = getUnderlyingAppRootForTeach();
                    AccessibilityNodeInfo hit = root != null ? UiNodeHitTester.findBest(root, screenX, screenY) : null;
                    if (root == null || hit == null || pendingTeachAnchor == null) {
                        FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                        if (fb != null) fb.showCompactStatus("找不到目標點，請重新教學", "");
                        if (hit != null) hit.recycle();
                        if (root != null) root.recycle();
                        clearPendingTeachAnchor();
                        return;
                    }
                    try {
                        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
                        String sig = ScreenFingerprint.create(root);
                        if (learnedUiMappingStore == null) learnedUiMappingStore = new LearnedUiMappingStore(CrewAccessibilityService.this);
                        learnedUiMappingStore.learnAnchored(
                            pkg, sig, role, pendingTeachAnchor, hit
                        );
                        FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                        if (fb != null) fb.showCompactStatus("已記住：基準點 → 送出按鈕", pkg);
                    } finally {
                        hit.recycle();
                        root.recycle();
                        clearPendingTeachAnchor();
                    }
                }

                @Override
                public void onCancelled() {
                    clearPendingTeachAnchor();
                    FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                    if (fb != null) fb.showCompactStatus("已取消教學", "");
                }
            }
        );
    }

    public boolean beginTeachElement(String role) {
        final String requestedRole = role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo activeComposer = root != null ? findActiveEditText(root) : null;
        if ("COMPOSER_SEND".equalsIgnoreCase(requestedRole)
                && activeComposer != null
                && "EMPTY".equals(LearnedUiMappingStore.composerState(activeComposer))) {
            if (root != null) root.recycle();
            activeComposer.recycle();
            FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
            if (fb != null) {
                fb.showCompactStatus("請先在輸入框輸入任意文字，讓真正的送出按鈕出現，再點教學。", "");
            }
            return false;
        }
        if (activeComposer != null) activeComposer.recycle();
        if (root != null) root.recycle();

        if ("COMPOSER_SEND".equalsIgnoreCase(requestedRole)) {
            return beginTeachAnchoredElement(requestedRole);
        }

        if (uiTeachOverlay == null) uiTeachOverlay = new UiTeachOverlay(this);

        return uiTeachOverlay.show(
            "請點一下「" + requestedRole + "」按鈕",
            new UiTeachOverlay.Callback() {
                @Override
                public void onPicked(int screenX, int screenY) {
                    AccessibilityNodeInfo root = getUnderlyingAppRootForTeach();
                    if (root == null) return;
                    AccessibilityNodeInfo picked = null;
                    AccessibilityNodeInfo composer = null;
                    try {
                        picked = UiNodeHitTester.findBest(root, screenX, screenY);
                        if (picked == null) return;

                        String packageName = root.getPackageName() == null
                            ? ""
                            : root.getPackageName().toString();

                        String screenSignature = ScreenFingerprint.create(root);

                        if ("COMPOSER_SEND".equals(requestedRole)) {
                            composer = findActiveEditText(root);
                        }

                        if (learnedUiMappingStore == null) learnedUiMappingStore = new LearnedUiMappingStore(CrewAccessibilityService.this);
                        LearnedUiMappingStore.Rule rule = learnedUiMappingStore.learn(
                            packageName,
                            screenSignature,
                            requestedRole,
                            picked,
                            composer
                        );

                        FloatingBubbleManager fb = FloatingBubbleManager.getInstance();
                        if (fb != null) {
                            if ("COMPOSER_SEND".equalsIgnoreCase(requestedRole)) {
                                fb.showCompactStatus("已記住送出按鈕（有文字狀態）", packageName);
                            } else {
                                fb.showCompactStatus("已記住 " + requestedRole, packageName);
                            }
                        }
                    } finally {
                        if (composer != null) composer.recycle();
                        if (picked != null) picked.recycle();
                        root.recycle();
                    }
                }

                @Override
                public void onCancelled() {}
            }
        );
    }

    // ── 0025 compatibility aliases ──
    // Keep old callers source-compatible, but Accessibility never opens the mic.
    @Deprecated
    public void startNativeWakeWordListener() {
        NativeLiveService.resumeIdleWakeIfRunning();
    }

    @Deprecated
    public void stopNativeWakeWordListener() {
        NativeLiveService.suspendIdleWakeIfRunning();
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        while (current != null) {
            if (current.isClickable()) return current;
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        return null;
    }

    /**
     * 0052 simple SEND_CURRENT primitive.
     *
     * Current-screen only:
     * composer with text -> learned/semantic Send -> IME fallback -> one verify.
     * No recipient routing, no second click, no retry loop.
     */
    private JSONObject performSendCurrentComposerSimple() {
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo composer = null;
        AccessibilityNodeInfo send = null;
        AccessibilityNodeInfo freshRoot = null;
        AccessibilityNodeInfo freshComposer = null;

        String submittedText = "";
        String submitMethod = "NONE";
        boolean submitted = false;

        try {
            root = getRootInActiveWindow();
            if (root == null) {
                return sendCurrentFailure("RESOLVE_COMPOSER", "NO_ACTIVE_WINDOW");
            }

            composer = findActiveEditText(root);
            if (composer == null) {
                return sendCurrentFailure("RESOLVE_COMPOSER", "COMPOSER_NOT_FOUND");
            }
            if (SensitiveDataGuard.isHardBlockedInput(composer)) {
                return sendCurrentFailure("RESOLVE_COMPOSER", "SENSITIVE_INPUT_BLOCKED");
            }

            CharSequence current = composer.getText();
            submittedText = current == null ? "" : current.toString();
            if (submittedText.trim().isEmpty()
                    || !"HAS_TEXT".equals(LearnedUiMappingStore.composerState(composer))) {
                return sendCurrentFailure("RESOLVE_COMPOSER", "COMPOSER_EMPTY");
            }

            SendVerification.Snapshot before =
                    SendVerification.capture(root, composer);

            send = findLikelySendButton(root);
            if (send != null) {
                if (SensitiveDataGuard.isBlockedAction(send)) {
                    return sendCurrentFailure("SUBMIT", "SENSITIVE_TARGET_BLOCKED");
                }

                boolean learned = lastLearnedSendMatch != null;
                try {
                    // Execute once. Some Accessibility targets return false even
                    // when the UI accepted the click, so verification is authoritative.
                    send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    submitted = true;
                    submitMethod = learned ? "LEARNED_SEND" : "SEMANTIC_SEND";
                } catch (Exception ignored) {}
            } else if (tryImeSendCurrent(composer)) {
                submitted = true;
                submitMethod = "IME_ENTER";
            }

            if (!submitted) {
                recordLastLearnedSendResult(false);
                return sendCurrentFailure("SUBMIT", "SUBMIT_TARGET_NOT_FOUND");
            }

            try { Thread.sleep(420L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }

            freshRoot = getRootInActiveWindow();
            if (freshRoot == null) {
                recordLastLearnedSendResult(false);
                return new JSONObject()
                        .put("success", false)
                        .put("action", "SEND_CURRENT")
                        .put("submitted", true)
                        .put("submitMethod", submitMethod)
                        .put("stage", "VERIFY")
                        .put("error", "SEND_UNCERTAIN");
            }

            freshComposer = findActiveEditText(freshRoot);
            SendVerification.Snapshot after =
                    SendVerification.capture(freshRoot, freshComposer);
            SendVerification.Result verification =
                    SendVerification.verify(before, after, submittedText);

            boolean success =
                    verification.state == SendVerification.State.VERIFIED
                    || verification.state == SendVerification.State.LIKELY;
            recordLastLearnedSendResult(success);

            JSONObject out = new JSONObject()
                    .put("success", success)
                    .put("action", "SEND_CURRENT")
                    .put("submitted", true)
                    .put("verified",
                            verification.state == SendVerification.State.VERIFIED)
                    .put("submitMethod", submitMethod)
                    .put("stage", "DONE")
                    .put("textLength", submittedText.length())
                    .put("verification", verification.toJson());

            if (!success) {
                out.put("error", "SEND_UNCERTAIN");
            }
            return out;
        } catch (Exception error) {
            recordLastLearnedSendResult(false);
            try {
                return sendCurrentFailure(
                        "RUNTIME",
                        error.getMessage() == null
                                ? "SEND_CURRENT_FAILED"
                                : error.getMessage());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        } finally {
            if (send != null) try { send.recycle(); } catch (Exception ignored) {}
            if (freshComposer != null) try { freshComposer.recycle(); } catch (Exception ignored) {}
            if (freshRoot != null) try { freshRoot.recycle(); } catch (Exception ignored) {}
            if (composer != null) try { composer.recycle(); } catch (Exception ignored) {}
            if (root != null) try { root.recycle(); } catch (Exception ignored) {}
        }
    }

    private JSONObject sendCurrentFailure(String stage, String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false)
                    .put("action", "SEND_CURRENT")
                    .put("submitted", false)
                    .put("stage", stage)
                    .put("error", error);
        } catch (Exception ignored) {}
        return out;
    }

    private boolean tryImeSendCurrent(AccessibilityNodeInfo composer) {
        if (composer == null || Build.VERSION.SDK_INT < 30) return false;
        try {
            int imeEnterId = 16908372;
            try {
                Object actionObj = AccessibilityNodeInfo.AccessibilityAction.class
                        .getField("ACTION_IME_ENTER")
                        .get(null);
                if (actionObj instanceof AccessibilityNodeInfo.AccessibilityAction) {
                    imeEnterId =
                            ((AccessibilityNodeInfo.AccessibilityAction) actionObj)
                                    .getId();
                }
            } catch (Throwable ignored) {}

            for (AccessibilityNodeInfo.AccessibilityAction action
                    : composer.getActionList()) {
                if (action != null && action.getId() == imeEnterId) {
                    return composer.performAction(action.getId());
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private SendTextTransaction newSendTextTransaction() {
        return new SendTextTransaction(
                new SendTextTransaction.Environment() {
                    @Override
                    public AccessibilityNodeInfo currentRoot() {
                        return getRootInActiveWindow();
                    }

                    @Override
                    public AccessibilityNodeInfo resolveComposer(AccessibilityNodeInfo root) {
                        return findActiveEditText(root);
                    }

                    @Override
                    public AccessibilityNodeInfo resolveSendButton(AccessibilityNodeInfo root) {
                        // Current implementation already resolves learned
                        // COMPOSER_SEND first, then strict semantic resolver.
                        return findLikelySendButton(root);
                    }

                    @Override
                    public void recordSendResolutionResult(boolean success) {
                        recordLastLearnedSendResult(success);
                    }
                });
    }

    private SendTextTransaction.Result performSendTextTransaction(String text) {
        return newSendTextTransaction().execute(text);
    }

    private SendTextTransaction.Result performSendCurrentComposerTransaction() {
        return newSendTextTransaction().executeExisting();
    }

    private boolean performSetText(String text) {
        lastTextInputMethod = "NONE";
        lastTextInputFailure = "";
        lastTextInputVerified = false;
        if (text == null) {
            lastTextInputFailure = "EMPTY_TEXT";
            return false;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            lastTextInputFailure = "NO_ACTIVE_WINDOW";
            return false;
        }
        AccessibilityNodeInfo target = null;
        try {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null || !isEditableCandidate(target)) {
                if (target != null) { target.recycle(); target = null; }
                target = findFocusedEditableNode(root);
            }
            if (target == null) target = findEditableNode(root);
            if (target == null) {
                lastTextInputFailure = "NO_EDITABLE_TARGET";
                return false;
            }
            if (SensitiveDataGuard.isHardBlockedInput(target)) {
                lastTextInputFailure = "SENSITIVE_INPUT_BLOCKED";
                return false;
            }

            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean setAccepted = target.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (setAccepted) {
                lastTextInputMethod = "ACTION_SET_TEXT";
                lastTextInputVerified = refreshAndMatches(target, text);
                moveCursorToEnd(target, text.length());
                return true;
            }

            // Jetpack Compose / custom editors such as some Google Keep builds
            // can expose an editable node while rejecting ACTION_SET_TEXT.
            // Paste is a standard Accessibility fallback and keeps typing local.
            boolean pasteAccepted = pasteIntoTarget(target, text);
            if (pasteAccepted) {
                lastTextInputMethod = "ACTION_PASTE";
                lastTextInputVerified = refreshAndContains(target, text);
                moveCursorToEnd(target, safeTextLength(target));
                return true;
            }

            lastTextInputFailure = "SET_TEXT_AND_PASTE_REJECTED";
            return false;
        } catch (Exception error) {
            lastTextInputFailure = "TEXT_INPUT_EXCEPTION_"
                    + error.getClass().getSimpleName();
            return false;
        } finally {
            if (target != null) try { target.recycle(); } catch (Exception ignored) {}
            root.recycle();
        }
    }

    /** Runtime search must never report success unless the text is observable. */
    boolean performSetTextVerified(String text) {
        return performSetText(text) && lastTextInputVerified;
    }

    private boolean isEditableCandidate(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        return node.isEditable() || (cls != null
                && cls.toString().toLowerCase(Locale.ROOT).contains("edittext"));
    }

    private AccessibilityNodeInfo findFocusedEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isEditableCandidate(node) && node.isFocused()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo hit = findFocusedEditableNode(child);
                if (hit != null) return hit;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private boolean pasteIntoTarget(AccessibilityNodeInfo target, String text) {
        android.content.ClipboardManager clipboard = null;
        android.content.ClipData previous = null;
        boolean hadPrevious = false;
        try {
            clipboard = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return false;
            try {
                hadPrevious = clipboard.hasPrimaryClip();
                if (hadPrevious) previous = clipboard.getPrimaryClip();
            } catch (Exception ignored) {}

            clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Crew Helper input", text));
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (clipboard != null) {
                try {
                    if (hadPrevious && previous != null) clipboard.setPrimaryClip(previous);
                    else clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("", ""));
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean refreshAndMatches(AccessibilityNodeInfo target, String expected) {
        try {
            target.refresh();
            CharSequence value = target.getText();
            return value != null && expected.equals(value.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean refreshAndContains(AccessibilityNodeInfo target, String expected) {
        try {
            target.refresh();
            CharSequence value = target.getText();
            return value != null && value.toString().contains(expected);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int safeTextLength(AccessibilityNodeInfo target) {
        try {
            target.refresh();
            CharSequence value = target.getText();
            return value == null ? 0 : value.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void moveCursorToEnd(AccessibilityNodeInfo target, int end) {
        try {
            android.os.Bundle selArgs = new android.os.Bundle();
            selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    Math.max(0, end));
            selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    Math.max(0, end));
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs);
        } catch (Exception ignored) {}
    }

    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() || (node.getClassName() != null && node.getClassName().toString().contains("EditText"))) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo res = findEditableNode(child);
                child.recycle();
                if (res != null) return res;
            }
        }
        return null;
    }

    private boolean performScrollAction(boolean forward, String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo scrollable = null;
            if (id != null && !id.trim().isEmpty()) {
                AccessibilityNodeInfo targetNode = findMatchingNodeById(root, id.trim());
                if (targetNode != null) {
                    if (targetNode.isScrollable()) {
                        scrollable = targetNode;
                    } else {
                        scrollable = findScrollableNode(targetNode);
                        if (scrollable == null) scrollable = targetNode;
                    }
                }
            }
            if (scrollable == null) {
                scrollable = findScrollableNode(root);
            }
            if (scrollable != null) {
                int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                boolean success = scrollable.performAction(action);
                scrollable.recycle();
                return success;
            }
        } catch (Exception ignored) {}
        finally {
            root.recycle();
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo res = findScrollableNode(child);
                child.recycle();
                if (res != null) return res;
            }
        }
        return null;
    }

    private void performSwipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        
        // Construct smooth, continuous multi-point natural finger curve (easing out)
        int steps = 12;
        float dx = x2 - x1;
        float dy = y2 - y1;
        
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            // Quintic / Sine Ease-Out curve for silky smooth inertia
            float progress = (float) Math.sin(t * (Math.PI / 2.0));
            float currX = x1 + dx * progress;
            float currY = y1 + dy * progress;
            path.lineTo(currX, currY);
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        long dur = Math.max(300, Math.min(650, duration));
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, dur));
        dispatchGesture(builder.build(), null, null);
    }

    private void dumpNodesJson(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        CharSequence viewId = node.getViewIdResourceName();
        boolean clickable = node.isClickable();
        boolean scrollable = node.isScrollable();
        boolean editable = node.isEditable();
        boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);

        boolean hasContent = (text != null && text.length() > 0) || (desc != null && desc.length() > 0) || (viewId != null && viewId.length() > 0);
        if (hasContent || clickable || scrollable || editable) {
            sb.append("{");
            sb.append("\"class\":\"").append(cls != null ? cls.toString() : "").append("\",");
            sb.append("\"text\":\"").append(sensitive ? SensitiveDataGuard.REDACTED : (text != null ? jsonEscape(text.toString()) : "")).append("\",");
            sb.append("\"desc\":\"").append(sensitive ? SensitiveDataGuard.REDACTED : (desc != null ? jsonEscape(desc.toString()) : "")).append("\",");
            sb.append("\"id\":\"").append(viewId != null ? jsonEscape(viewId.toString()) : "").append("\",");
            if (sensitive) sb.append("\"sensitive\":true,");
            sb.append("\"clickable\":").append(clickable).append(",");
            sb.append("\"scrollable\":").append(scrollable).append(",");
            sb.append("\"editable\":").append(editable).append(",");
            sb.append("\"bounds\":{\"left\":").append(bounds.left).append(",\"top\":").append(bounds.top)
              .append(",\"right\":").append(bounds.right).append(",\"bottom\":").append(bounds.bottom).append("}");
            sb.append("},");
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNodesJson(child, sb);
                child.recycle();
            }
        }
    }

    private boolean isActiveInputHardBlocked() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) target = findEditableNode(root);
            if (target == null) return false;
            try {
                return SensitiveDataGuard.isHardBlockedInput(target);
            } finally {
                target.recycle();
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            root.recycle();
        }
    }

    private PolicyEngine.Result evaluateTapPolicy(float x, float y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return new PolicyEngine.Result(PolicyEngine.Decision.BLOCK, "no observable tap target");
        try {
            AccessibilityNodeInfo target = findActionNodeAtPoint(root, Math.round(x), Math.round(y));
            if (target == null) return new PolicyEngine.Result(PolicyEngine.Decision.BLOCK, "unidentified coordinate target requires manual operation");
            try {
                if (SensitiveDataGuard.isBlockedAction(target)) return new PolicyEngine.Result(PolicyEngine.Decision.BLOCK, "sensitive target");
                String text = target.getText() == null ? "" : target.getText().toString();
                String desc = target.getContentDescription() == null ? "" : target.getContentDescription().toString();
                String id = target.getViewIdResourceName() == null ? "" : target.getViewIdResourceName().toString();
                String label = !text.trim().isEmpty() ? text : desc;
                return PolicyEngine.evaluate("click", label, id, SensitiveDataGuard.isSensitiveNode(target));
            } finally {
                target.recycle();
            }
        } catch (Exception ignored) {
            return new PolicyEngine.Result(PolicyEngine.Decision.BLOCK, "target safety inspection failed");
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findActionNodeAtPoint(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo nested = findActionNodeAtPoint(child, x, y);
                if (nested != null) return nested;
            } finally {
                child.recycle();
            }
        }
        return node.isClickable() ? AccessibilityNodeInfo.obtain(node) : null;
    }

    private String policyBlockJson(PolicyEngine.Result result) {
        return "{\"success\":false,\"policy\":\"BLOCK\",\"error\":\""
                + jsonEscape(result.reason) + "\"}";
    }

    private void writeJsonAndClose(Socket socket, String responseJson) throws Exception {
        byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + responseBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(responseBytes);
        out.flush();
        socket.close();
    }
}
