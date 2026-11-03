package com.crewpocket.helper;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.content.pm.ProviderInfo;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in virtual-display POC. Launch through adb shell/app_process or
 * a Shizuku shell-UID UserService that starts the same app_process binary.
 * Portions of the Shell Context and virtual-display approach are adapted
 * from Mika v0.1.33 (wangzan101) and scrcpy (Genymobile / Romain Vimont),
 * licensed under the Apache License, Version 2.0.
 * https://github.com/wangzan101/Mika/tree/v0.1.33
 * https://www.apache.org/licenses/LICENSE-2.0
 * The protocol is explicitly NOT connected to Gemini Live production tools.
 */
public final class BackgroundDisplayShell {
    static final String SOCKET = "com.crewpocket.helper.background.v1";
    static final int PROTOCOL_VERSION = 5;
    private static final String PACKAGE = "com.crewpocket.helper";
    private final Context context;
    private final int appUid;
    private final HandlerThread frameThread = new HandlerThread("crew-bg-frame");
    private final Object frameLock = new Object();
    private ImageReader reader;
    private VirtualDisplay display;
    private Bitmap lastFrame;
    private int displayId = Display.INVALID_DISPLAY;
    private String openedPackage = "";
    private boolean focusIsolated = false;
    private int width, height;
    private long frameAt;
    private final IBackgroundDirectShell.Stub directEndpoint = new IBackgroundDirectShell.Stub() {
        @Override public int[] identity() {
            return new int[]{Process.myUid(), Process.myPid()};
        }
        @Override public ParcelFileDescriptor executeCommand(String json) {
            if (Process.myUid() != 2000
                    || Binder.getCallingUid() != appUid) return null;
            final ParcelFileDescriptor[] ends;
            try { ends = ParcelFileDescriptor.createPipe(); }
            catch (Exception failed) { return null; }
            final ParcelFileDescriptor sink = ends[1];
            Thread worker = new Thread(() -> {
                String response;
                try {
                    JSONObject req = new JSONObject(json == null ? "" : json);
                    if (json == null || json.length() > 8192)
                        throw new IllegalArgumentException("INVALID_BINDER_COMMAND");
                    synchronized (BackgroundDisplayShell.this) {
                        String denied = validateBinderCommand(req);
                        if (!denied.isEmpty()) {
                            response = fail(denied).put("protocolVersion", PROTOCOL_VERSION)
                                    .toString();
                        } else {
                            String op = req.optString("op", "");
                            // Bind UI-originated commands to THIS display. Live
                            // sends its own expectedDisplayId / expectedPackage.
                            if (BackgroundShizukuCommandPolicy.requiresDisplay(op)
                                    && (!req.has("expectedDisplayId")
                                    || !req.has("expectedPackage"))) {
                                if (displayId <= Display.DEFAULT_DISPLAY || openedPackage.isEmpty())
                                    throw new IllegalStateException("NO_BACKGROUND_DISPLAY");
                                req.put("expectedDisplayId", displayId);
                                req.put("expectedPackage", openedPackage);
                            }
                            JSONObject output = execute(req);
                            output.put("protocolVersion", PROTOCOL_VERSION);
                            response = output.toString();
                        }
                    }
                } catch (Throwable error) {
                    try {
                        response = fail("DIRECT_SHELL_EXECUTION_REJECTED")
                                .put("protocolVersion", PROTOCOL_VERSION).toString();
                    } catch (Exception ignored) { response = "{}"; }
                }
                try (FileOutputStream out =
                             new ParcelFileDescriptor.AutoCloseOutputStream(sink)) {
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    try { sink.close(); } catch (Exception ignoredClose) {}
                }
            }, "crew-bg-direct-binder");
            worker.setDaemon(true);
            worker.start();
            return ends[0];
        }
    };

    private BackgroundDisplayShell(Context context, int uid) {
        this.context = context;
        this.appUid = uid;
        frameThread.start();
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31)
            throw new SecurityException("adb shell UID 2000 and Android 12+ required");
        Looper.prepareMainLooper();
        Context ctx = shellContext();
        int uid = ctx.getPackageManager().getApplicationInfo(PACKAGE, 0).uid;
        BackgroundDisplayShell helper = new BackgroundDisplayShell(ctx, uid);
        if (args != null && args.length == 1 && "binder".equals(args[0])) {
            String nonce;
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                nonce = input.readLine();
            }
            if (nonce == null || nonce.length() < 30 || nonce.length() > 160
                    || !helper.handoffDirectBinder(nonce)) {
                helper.closeDisplay();
                helper.frameThread.quitSafely();
                throw new SecurityException("DIRECT_BINDER_HANDOFF_REJECTED");
            }
            // Commands arrive on Binder worker threads; no Shell socket exists
            // for this process. App death revokes display ownership.
            Looper.loop();
            return;
        }
        // Legacy manual ADB startup is unchanged and kept as an explicit
        // backup only. The Shizuku path never enters this socket loop.
        try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
            for (;;) {
                LocalSocket client = server.accept();
                try {
                    // App UID may be denied cross-UID socket access by One UI.
                    // The Shizuku UserService connects as UID 2000 instead.
                    // A shell UID client has shell privileges already; the
                    // UserService Binder independently authorizes the App UID.
                    int peerUid = client.getPeerCredentials() == null
                            ? -1 : client.getPeerCredentials().getUid();
                    if (peerUid != helper.appUid && peerUid != 2000) {
                        client.close();
                        continue;
                    }
                    helper.handle(client);
                } catch (Exception error) {
                    try { client.close(); } catch (Exception ignored) {}
                }
            }
        } finally {
            helper.closeDisplay();
            helper.frameThread.quitSafely();
        }
    }

    private static String validateBinderCommand(JSONObject command) {
        String op = command.optString("op", "");
        if (!BackgroundShizukuCommandPolicy.allowedOperation(op))
            return "DIRECT_SHELL_OPERATION_DENIED";
        HashSet<String> allowed = new HashSet<>();
        allowed.add("op");
        if ("open".equals(op)) {
            allowed.add("package");
            if (!BackgroundShizukuCommandPolicy.validPackage(
                    command.optString("package", "")))
                return "DIRECT_SHELL_INVALID_PACKAGE";
        }
        if (BackgroundShizukuCommandPolicy.requiresDisplay(op)) {
            allowed.add("expectedDisplayId");
            allowed.add("expectedPackage");
            if (command.has("expectedDisplayId")
                    && command.optInt("expectedDisplayId", -1) <= 0)
                return "DIRECT_SHELL_INVALID_DISPLAY";
            if (command.has("expectedPackage")
                    && !BackgroundShizukuCommandPolicy.validPackage(
                        command.optString("expectedPackage", "")))
                return "DIRECT_SHELL_INVALID_EXPECTED_PACKAGE";
        }
        if ("tap".equals(op)) {
            allowed.add("x");
            allowed.add("y");
            if (!BackgroundShizukuCommandPolicy.validCoordinate(
                    command.optInt("x", -1))
                    || !BackgroundShizukuCommandPolicy.validCoordinate(
                        command.optInt("y", -1))) return "DIRECT_SHELL_BAD_COORDINATE";
        }
        if ("swipe".equals(op)) {
            for (String key : new String[]{"x1", "y1", "x2", "y2"}) {
                allowed.add(key);
                if (!BackgroundShizukuCommandPolicy.validCoordinate(
                        command.optInt(key, -1)))
                    return "DIRECT_SHELL_BAD_COORDINATE";
            }
            allowed.add("durationMs");
            int duration = command.optInt("durationMs", 300);
            if (duration < 100 || duration > 3000) return "DIRECT_SHELL_BAD_DURATION";
        }
        Iterator<String> keys = command.keys();
        while (keys.hasNext())
            if (!allowed.contains(keys.next())) return "DIRECT_SHELL_ARGUMENT_DENIED";
        return "";
    }

    private boolean handoffDirectBinder(String nonce) {
        Bundle parcel = new Bundle();
        parcel.putString(BackgroundShellHandoff.KEY_NONCE, nonce);
        parcel.putString(BackgroundShellHandoff.KEY_APK,
                System.getProperty("java.class.path", ""));
        parcel.putBinder(BackgroundShellHandoff.KEY_BINDER, directEndpoint);
        parcel.putInt(BackgroundShellHandoff.KEY_UID, Process.myUid());
        parcel.putInt(BackgroundShellHandoff.KEY_PID, Process.myPid());
        Bundle accepted = null;
        // Standard API first; custom Shell Context has a valid AttributionSource.
        try {
            accepted = context.getContentResolver().call(
                    Uri.parse("content://" + BackgroundShellHandoff.AUTHORITY),
                    BackgroundShellHandoff.METHOD_ATTACH, null, parcel);
        } catch (Throwable ignored) {}
        if (accepted == null || !accepted.getBoolean("accepted", false)) {
            accepted = offerUsingShellActivityManager(parcel);
        }
        if (accepted == null || !accepted.getBoolean("accepted", false)) return false;
        IBinder appToken = accepted.getBinder(BackgroundShellHandoff.KEY_APP_TOKEN);
        if (appToken == null) return false;
        try {
            // Mika pattern: if the owning App process goes away, do not leave
            // its virtual display or controls stranded in the Shell process.
            appToken.linkToDeath(() -> {
                synchronized (BackgroundDisplayShell.this) {
                    closeDisplay();
                }
                Process.killProcess(Process.myPid());
            }, 0);
        } catch (Exception gone) { return false; }
        return true;
    }

    /** Mika-style provider handoff through shell's ActivityManager service. */
    private Bundle offerUsingShellActivityManager(Bundle extras) {
        IBinder token = new Binder();
        Object am = null;
        int userId = appUid / 100000;
        try {
            am = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            Object holder = am.getClass().getMethod(
                    "getContentProviderExternal", String.class, int.class,
                    IBinder.class, String.class).invoke(
                    am, BackgroundShellHandoff.AUTHORITY, userId, token,
                    "CrewBackgroundShell");
            if (holder == null) return null;
            ProviderInfo info = (ProviderInfo) holder.getClass().getField("info").get(holder);
            if (info == null || !PACKAGE.equals(info.packageName)
                    || info.applicationInfo == null
                    || info.applicationInfo.uid != appUid) return null;
            Object target = holder.getClass().getField("provider").get(holder);
            if (target == null) return null;
            Method method = null;
            for (Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals("call")
                        && candidate.getParameterCount() == 5
                        && candidate.getParameterTypes()[0] == AttributionSource.class) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) return null;
            AttributionSource source = new AttributionSource.Builder(2000)
                    .setPackageName("com.android.shell").build();
            return (Bundle) method.invoke(target, source,
                    BackgroundShellHandoff.AUTHORITY,
                    BackgroundShellHandoff.METHOD_ATTACH, null, extras);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (am != null) {
                try {
                    am.getClass().getMethod("removeContentProviderExternalAsUser",
                            String.class, IBinder.class, int.class)
                            .invoke(am, BackgroundShellHandoff.AUTHORITY, token, userId);
                } catch (Exception ignored) {}
            }
        }
    }

    private void handle(LocalSocket socket) throws Exception {
        socket.setSoTimeout(8000);
        try (LocalSocket client = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            JSONObject response;
            if (line == null || line.length() > 8192) {
                response = fail("INVALID_REQUEST");
            } else {
                try { response = execute(new JSONObject(line)); }
                catch (Throwable failure) {
                    response = fail("OPERATION_FAILED_" + failure.getClass().getSimpleName());
                }
            }
            response.put("protocolVersion", PROTOCOL_VERSION);
            out.write(response.toString());
            out.write('\n');
            out.flush();
        }
    }

    private JSONObject execute(JSONObject req) throws Exception {
        String op = req.optString("op", "");
        if ("status".equals(op)) return status();
        if ("close".equals(op)) {
            if (!matchesExpectedDisplay(req)) return fail("DISPLAY_OWNERSHIP_CHANGED");
            closeDisplay();
            return ok().put("active", false);
        }
        if ("open".equals(op)) return open(req.optString("package", ""));
        if (display == null) return fail("NO_BACKGROUND_DISPLAY");
        if ("preview".equals(op)) {
            if (!matchesExpectedDisplay(req)) return fail("DISPLAY_OWNERSHIP_CHANGED");
            return preview();
        }
        if ("tap".equals(op)) {
            if (!matchesExpectedDisplay(req)) return fail("DISPLAY_OWNERSHIP_CHANGED");
            if (req.has("expectedPackage") && !taskPresentOnDisplay()) 
                return fail("BACKGROUND_TASK_NOT_ON_DISPLAY");
            int x = req.optInt("x", -1), y = req.optInt("y", -1);
            if (!valid(x, y)) return fail("INVALID_COORDINATES");
            return command("input", "-d", String.valueOf(displayId), "tap",
                    String.valueOf(x), String.valueOf(y))
                    ? ok() : fail("INPUT_REJECTED");
        }
        if ("swipe".equals(op)) {
            if (!matchesExpectedDisplay(req)) return fail("DISPLAY_OWNERSHIP_CHANGED");
            int x1 = req.optInt("x1", -1), y1 = req.optInt("y1", -1);
            int x2 = req.optInt("x2", -1), y2 = req.optInt("y2", -1);
            int ms = req.optInt("durationMs", 300);
            if (!valid(x1, y1) || !valid(x2, y2) || ms < 100 || ms > 3000)
                return fail("INVALID_GESTURE");
            return command("input", "-d", String.valueOf(displayId), "swipe",
                    String.valueOf(x1), String.valueOf(y1),
                    String.valueOf(x2), String.valueOf(y2), String.valueOf(ms))
                    ? ok() : fail("INPUT_REJECTED");
        }
        if ("front".equals(op)) {
            if (!matchesExpectedDisplay(req)) return fail("DISPLAY_OWNERSHIP_CHANGED");
            return handoff();
        }
        return fail("UNKNOWN_OPERATION");
    }

    private JSONObject open(String pkg) throws Exception {
        if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || PACKAGE.equals(pkg))
            return fail("INVALID_PACKAGE");
        if (display != null) return fail("DISPLAY_ALREADY_ACTIVE");
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (displayOf(task) == Display.DEFAULT_DISPLAY && task.topActivity != null
                    && pkg.equals(task.topActivity.getPackageName()))
                return fail("APP_IN_USE_ON_MAIN_DISPLAY");
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null || launch.getComponent() == null) return fail("NO_LAUNCHER");
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = Math.max(320, Math.min(720, metrics.widthPixels));
        height = Math.max(560, Math.round((float) width * metrics.heightPixels
                / Math.max(1, metrics.widthPixels)));
        int dpi = Math.max(160, Math.min(420, metrics.densityDpi));
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onFrame, new Handler(frameThread.getLooper()));
        try {
            try {
                display = createDisplay(reader.getSurface(), dpi, true);
                focusIsolated = Build.VERSION.SDK_INT >= 34;
            } catch (Exception trustedFailure) {
                display = createDisplay(reader.getSurface(), dpi, false);
                focusIsolated = false;
            }
        } catch (Exception createFailure) {
            closeDisplay(); // Failed displays must never strand an ImageReader.
            return fail("CREATE_DISPLAY_REJECTED");
        }
        if (display == null) {
            closeDisplay();
            return fail("CREATE_DISPLAY_FAILED");
        }
        displayId = display.getDisplay().getDisplayId();
        if (!command("am", "start", "--display", String.valueOf(displayId),
                "-n", launch.getComponent().flattenToShortString())) {
            closeDisplay();
            return fail("LAUNCH_FAILED");
        }
        // am's process exit code is NOT proof the App was launched on the
        // virtual display. Never claim success without task+display evidence.
        if (!awaitTaskLocation(pkg, -1, displayId)) {
            closeDisplay();
            return fail("LAUNCH_NOT_ON_VIRTUAL_DISPLAY");
        }
        openedPackage = pkg;
        return status();
    }

    private VirtualDisplay createDisplay(Surface surface, int dpi, boolean trusted) throws Exception {
        Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        DisplayManager manager = constructor.newInstance(context);
        int flags = 1 | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 8);
        if (Build.VERSION.SDK_INT >= 33) {
            flags |= (1 << 13);
            if (trusted) flags |= (1 << 10) | (1 << 11) | (1 << 12);
        }
        if (trusted && Build.VERSION.SDK_INT >= 34)
            flags |= (1 << 14) | (1 << 16);
        return manager.createVirtualDisplay("crew-bg-poc", width, height, dpi, surface, flags);
    }

    private void onFrame(ImageReader source) {
        android.media.Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - frameAt < 150) return;
            android.media.Image.Plane plane = image.getPlanes()[0];
            int strideWidth = plane.getRowStride() / plane.getPixelStride();
            Bitmap padded = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888);
            try {
                padded.copyPixelsFromBuffer(plane.getBuffer().rewind());
                Bitmap shot = Bitmap.createBitmap(padded, 0, 0, width, height);
                synchronized (frameLock) {
                    if (lastFrame != null) lastFrame.recycle();
                    lastFrame = shot;
                    frameAt = now;
                }
            } finally {
                padded.recycle();
            }
        } catch (Exception ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private JSONObject preview() throws Exception {
        Bitmap shot;
        long shotAt;
        synchronized (frameLock) {
            shot = lastFrame == null ? null : lastFrame.copy(Bitmap.Config.ARGB_8888, false);
            shotAt = frameAt;
        }
        if (shot == null) return fail("FRAME_NOT_READY");
        long age = SystemClock.uptimeMillis() - shotAt;
        if (age < 0L || age > 1500L) {
            shot.recycle();
            return fail("FRAME_STALE");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            shot.compress(Bitmap.CompressFormat.JPEG, 70, bytes);
            if (bytes.size() > 1024 * 1024) return fail("FRAME_TOO_LARGE");
            return ok().put("width", width).put("height", height)
                    .put("displayId", displayId).put("package", openedPackage)
                    .put("frameAgeMs", age)
                    .put("jpegBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        } finally {
            shot.recycle();
        }
    }

    private JSONObject handoff() throws Exception {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.RunningTaskInfo selected = null;
        for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
            if (displayOf(task) == displayId && task.topActivity != null
                    && openedPackage.equals(task.topActivity.getPackageName())) {
                selected = task;
                break;
            }
        }
        if (selected == null) return fail("BACKGROUND_TASK_NOT_FOUND");
        int taskId = selected.id;
        boolean moved;
        try {
            Object service = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            service.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class)
                    .invoke(service, taskId, Display.DEFAULT_DISPLAY);
            moved = true;
        } catch (Exception unavailable) {
            moved = command("am", "display", "move-stack",
                    String.valueOf(taskId), String.valueOf(Display.DEFAULT_DISPLAY));
        }
        if (!moved) return fail("HANDOFF_FAILED");
        if (!awaitTaskLocation(openedPackage, taskId, Display.DEFAULT_DISPLAY))
            return fail("HANDOFF_NOT_VERIFIED");
        closeDisplay();
        return ok().put("handedOff", true).put("taskId", taskId);
    }

    /**
     * TaskInfo.displayId is hidden from SDK stubs even on modern Android.
     * When unavailable, never assume DEFAULT_DISPLAY; refuse unsafe moves.
     */
    private static int displayOf(ActivityManager.RunningTaskInfo task) {
        try {
            try {
                java.lang.reflect.Method m = task.getClass().getMethod("getDisplayId");
                return (Integer) m.invoke(task);
            } catch (Exception ignored) {
                Field field = task.getClass().getField("displayId");
                return field.getInt(task);
            }
        } catch (Exception error) {
            return Display.INVALID_DISPLAY;
        }
    }

    private boolean awaitTaskLocation(String packageName, int knownTaskId, int targetDisplay) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        for (int attempt = 0; attempt < 15; attempt++) {
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
                if (knownTaskId >= 0 && task.id != knownTaskId) continue;
                if (displayOf(task) != targetDisplay) continue;
                if (knownTaskId < 0 && (task.topActivity == null
                        || !packageName.equals(task.topActivity.getPackageName()))) continue;
                return true;
            }
            SystemClock.sleep(120);
        }
        return false;
    }

    private boolean taskPresentOnDisplay() {
        if (displayId <= Display.DEFAULT_DISPLAY || openedPackage.isEmpty())
            return false;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
            if (displayOf(task) == displayId && task.topActivity != null
                    && openedPackage.equals(task.topActivity.getPackageName())) return true;
        }
        return false;
    }

    private boolean matchesExpectedDisplay(JSONObject request) {
        if (request.has("expectedDisplayId")
                && request.optInt("expectedDisplayId", Display.INVALID_DISPLAY) != displayId)
            return false;
        if (request.has("expectedPackage")
                && !request.optString("expectedPackage", "").equals(openedPackage))
            return false;
        return true;
    }

    private boolean valid(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
    private JSONObject status() throws Exception {
        return ok().put("active", display != null).put("displayId", displayId)
                .put("width", width).put("height", height)
                .put("focusIsolated", focusIsolated)
                .put("package", openedPackage);
    }
    private static JSONObject ok() throws Exception {
        return new JSONObject().put("success", true);
    }
    private static JSONObject fail(String code) throws Exception {
        return new JSONObject().put("success", false).put("error", code);
    }

    private void closeDisplay() {
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        synchronized (frameLock) {
            if (lastFrame != null) lastFrame.recycle();
            lastFrame = null;
            frameAt = 0;
        }
        displayId = Display.INVALID_DISPLAY;
        focusIsolated = false;
        openedPackage = "";
    }

    private static boolean command(String... args) {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private static Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            Constructor<?> ctor = at.getDeclaredConstructor();
            ctor.setAccessible(true);
            thread = ctor.newInstance();
            Field current = at.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, thread);
            Field system = at.getDeclaredField("mSystemThread");
            system.setAccessible(true);
            system.setBoolean(thread, true);
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    Class<?> controller = Class.forName("android.app.ConfigurationController");
                    Class<?> internal = Class.forName("android.app.ActivityThreadInternal");
                    Constructor<?> c = controller.getDeclaredConstructor(internal);
                    c.setAccessible(true);
                    Field field = at.getDeclaredField("mConfigurationController");
                    field.setAccessible(true);
                    field.set(thread, c.newInstance(thread));
                } catch (Exception ignored) {}
            }
            try {
                Class<?> data = Class.forName("android.app.ActivityThread$AppBindData");
                Constructor<?> c = data.getDeclaredConstructor();
                c.setAccessible(true);
                Object info = c.newInstance();
                ApplicationInfo ai = new ApplicationInfo();
                ai.packageName = "com.android.shell";
                Field field = data.getDeclaredField("appInfo");
                field.setAccessible(true);
                field.set(info, ai);
                Field bound = at.getDeclaredField("mBoundApplication");
                bound.setAccessible(true);
                bound.set(thread, info);
            } catch (Exception ignored) {}
        }
        java.lang.reflect.Method getSystem = at.getDeclaredMethod("getSystemContext");
        getSystem.setAccessible(true);
        Context base = (Context) getSystem.invoke(thread);
        Context wrapper = new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
        try {
            Field initial = at.getDeclaredField("mInitialApplication");
            initial.setAccessible(true);
            if (initial.get(thread) == null)
                initial.set(thread, Instrumentation.newApplication(Application.class, wrapper));
        } catch (Exception ignored) {}
        return wrapper;
    }
}
