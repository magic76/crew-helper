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
 * Opt-in virtual-display POC. Only launch through adb shell/app_process.
 * Portions of the Shell Context and virtual-display approach are adapted
 * from Mika v0.1.33 (wangzan101) and scrcpy (Genymobile / Romain Vimont),
 * licensed under the Apache License, Version 2.0.
 * https://github.com/wangzan101/Mika/tree/v0.1.33
 * https://www.apache.org/licenses/LICENSE-2.0
 * The protocol is explicitly NOT connected to Gemini Live production tools.
 */
public final class BackgroundDisplayShell {
    static final String SOCKET = "com.crewpocket.helper.background.v1";
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
    private int width, height;
    private long frameAt;

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
        try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
            for (;;) {
                LocalSocket client = server.accept();
                try {
                    if (client.getPeerCredentials() == null
                            || client.getPeerCredentials().getUid() != helper.appUid) {
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
            out.write(response.toString());
            out.write('\n');
            out.flush();
        }
    }

    private JSONObject execute(JSONObject req) throws Exception {
        String op = req.optString("op", "");
        if ("status".equals(op)) return status();
        if ("close".equals(op)) {
            closeDisplay();
            return ok().put("active", false);
        }
        if ("open".equals(op)) return open(req.optString("package", ""));
        if (display == null) return fail("NO_BACKGROUND_DISPLAY");
        if ("preview".equals(op)) return preview();
        if ("tap".equals(op)) {
            int x = req.optInt("x", -1), y = req.optInt("y", -1);
            if (!valid(x, y)) return fail("INVALID_COORDINATES");
            return command("input", "-d", String.valueOf(displayId), "tap",
                    String.valueOf(x), String.valueOf(y))
                    ? ok() : fail("INPUT_REJECTED");
        }
        if ("swipe".equals(op)) {
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
        if ("front".equals(op)) return handoff();
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
            try { display = createDisplay(reader.getSurface(), dpi, true); }
            catch (Exception trustedFailure) {
                display = createDisplay(reader.getSurface(), dpi, false);
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
        synchronized (frameLock) {
            shot = lastFrame == null ? null : lastFrame.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (shot == null) return fail("FRAME_NOT_READY");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            shot.compress(Bitmap.CompressFormat.JPEG, 70, bytes);
            if (bytes.size() > 1024 * 1024) return fail("FRAME_TOO_LARGE");
            return ok().put("width", width).put("height", height)
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
        closeDisplay();
        return ok().put("handedOff", true).put("taskId", taskId);
    }

    /**
     * TaskInfo.displayId is hidden from SDK stubs even on modern Android.
     * When unavailable, never assume DEFAULT_DISPLAY; refuse unsafe moves.
     */
    private static int displayOf(ActivityManager.RunningTaskInfo task) {
        try {
            Field field = task.getClass().getField("displayId");
            return field.getInt(task);
        } catch (Exception error) {
            return Display.INVALID_DISPLAY;
        }
    }

    private boolean valid(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
    private JSONObject status() throws Exception {
        return ok().put("active", display != null).put("displayId", displayId)
                .put("width", width).put("height", height).put("package", openedPackage);
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
