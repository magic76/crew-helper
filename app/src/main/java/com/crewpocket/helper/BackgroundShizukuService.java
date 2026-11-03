package com.crewpocket.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import android.os.Process;
import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku v13 UserService, running outside the App as shell UID 2000.
 * This Binder exposes NO general command execution or arbitrary file paths.
 * It launches exactly the same app_process helper as the existing ADB path.
 */
public final class BackgroundShizukuService extends IBackgroundShizukuService.Stub {
    private static final String PACKAGE = "com.crewpocket.helper";
    private static final String LOG = "/data/local/tmp/crew-bg-helper.log";
    private final Context serviceContext;
    private final Semaphore relaySlots = new Semaphore(2);
    private volatile int allowedAppUid = -1;

    public BackgroundShizukuService() {
        serviceContext = null;
    }

    public BackgroundShizukuService(Context context) {
        serviceContext = context;
    }

    @Override public int shellUid() {
        return Process.myUid();
    }

    @Override public synchronized String startHelper() {
        if (!authorizedCaller()) return "SHIZUKU_CALLER_NOT_AUTHORIZED";
        if (Process.myUid() != 2000) return "SHELL_UID_2000_REQUIRED";
        if (android.os.Build.VERSION.SDK_INT < 31) return "ANDROID_12_REQUIRED";
        final String apk = resolveOwnApk();
        if (apk.isEmpty()) return "APP_APK_NOT_FOUND";
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/app_process", "/system/bin",
                    "--nice-name=crew-bg-helper",
                    BackgroundDisplayShell.class.getName());
            builder.environment().put("CLASSPATH", apk);
            builder.redirectInput(new File("/dev/null"));
            builder.redirectErrorStream(true);
            builder.redirectOutput(new File(LOG));
            java.lang.Process child = builder.start();
            // A successful fork does NOT prove the helper is available.
            // The normal App polls the private UID-checked socket afterwards.
            if (!child.isAlive() && child.exitValue() != 0)
                return "APP_PROCESS_EXIT_" + child.exitValue();
            return "LAUNCH_REQUESTED";
        } catch (Exception error) {
            return "APP_PROCESS_START_FAILED_" + error.getClass().getSimpleName();
        }
    }

    /**
     * Run the existing, narrow Shell Socket protocol in the Shizuku shell UID.
     * The App never opens the Shell LocalSocket. The response is a stream FD,
     * so virtual-display JPEG previews cannot overflow Binder's 1 MiB buffer.
     * The Binder does NOT expose command execution or general file reads.
     */
    @Override public ParcelFileDescriptor relay(String requestJson) {
        final int callerUid = Binder.getCallingUid();
        final boolean authorized = Process.myUid() == 2000
                && callerUid == appUid();
        final String request = requestJson;
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Exception error) { return null; }
        final ParcelFileDescriptor sink = pipe[1];
        Thread worker = new Thread(() -> {
            String response;
            boolean slot = false;
            try {
                if (!authorized) {
                    response = error("SHIZUKU_CALLER_NOT_AUTHORIZED");
                } else if (!relaySlots.tryAcquire()) {
                    response = error("SHIZUKU_RELAY_BUSY");
                } else {
                    slot = true;
                    response = executeRelay(request);
                }
            } catch (Throwable failure) {
                response = error("SHIZUKU_RELAY_FAILED");
            } finally {
                if (slot) relaySlots.release();
            }
            try (FileOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(sink)) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                try { sink.close(); } catch (Exception ignoredAgain) {}
            }
        }, "crew-bg-shizuku-relay");
        worker.setDaemon(true);
        worker.start();
        return pipe[0];
    }

    private boolean authorizedCaller() {
        return Process.myUid() == 2000 && Binder.getCallingUid() == appUid();
    }

    private int appUid() {
        int cached = allowedAppUid;
        if (cached >= 10000) return cached;
        try {
            if (serviceContext != null) {
                int value = serviceContext.getPackageManager()
                        .getApplicationInfo(PACKAGE, 0).uid;
                if (value >= 10000) {
                    allowedAppUid = value;
                    return value;
                }
            }
        } catch (Exception ignored) {}
        // Shizuku's UserService constructor may not receive a Context.
        // Ask Android's package manager using constant arguments only.
        java.lang.Process proc = null;
        try {
            proc = new ProcessBuilder("pm", "list", "packages", "-U", PACKAGE)
                    .redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String prefix = "package:" + PACKAGE + " uid:";
                    if (!line.startsWith(prefix)) continue;
                    String uid = line.substring(prefix.length()).trim();
                    if (!uid.matches("[0-9]{5,7}")) continue;
                    int result = Integer.parseInt(uid);
                    if (result >= 10000) {
                        allowedAppUid = result;
                        return result;
                    }
                }
            }
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception ignored) {
        } finally {
            if (proc != null) proc.destroy();
        }
        return -1;
    }

    private String executeRelay(String raw) {
        if (raw == null || raw.length() > 8192) return error("INVALID_RELAY_REQUEST");
        try {
            JSONObject command = new JSONObject(raw);
            String check = validateCommand(command);
            if (!check.isEmpty()) return error(check);
            // A manual UI call may omit an expected virtual-display identity.
            // Bind those calls to a fresh Shell STATUS before sending them.
            String op = command.optString("op", "");
            if (requiresDisplay(op)
                    && (!command.has("expectedDisplayId")
                    || !command.has("expectedPackage"))) {
                JSONObject state = sendToShell(new JSONObject().put("op", "status"));
                if (!state.optBoolean("success", false)) return state.toString();
                if (!state.optBoolean("active", false)) return error("NO_BACKGROUND_DISPLAY");
                command.put("expectedDisplayId", state.optInt("displayId", -1));
                command.put("expectedPackage", state.optString("package", ""));
            }
            return sendToShell(command).toString();
        } catch (Exception error) {
            return error("SHIZUKU_RELAY_REQUEST_FAILED");
        }
    }

    private static String validateCommand(JSONObject c) {
        String op = c.optString("op", "");
        if (!("status".equals(op) || "open".equals(op)
                || "preview".equals(op) || "tap".equals(op)
                || "swipe".equals(op) || "front".equals(op)
                || "close".equals(op))) return "SHIZUKU_OPERATION_DENIED";
        java.util.HashSet<String> allowed = new java.util.HashSet<>();
        allowed.add("op");
        if ("open".equals(op)) {
            allowed.add("package");
            if (!validPackage(c.optString("package", "")))
                return "SHIZUKU_PACKAGE_INVALID";
        }
        if (requiresDisplay(op)) {
            allowed.add("expectedDisplayId");
            allowed.add("expectedPackage");
            if (c.has("expectedDisplayId")
                    && c.optInt("expectedDisplayId", -1) <= 0)
                return "SHIZUKU_DISPLAY_INVALID";
            if (c.has("expectedPackage")
                    && !validPackage(c.optString("expectedPackage", "")))
                return "SHIZUKU_EXPECTED_PACKAGE_INVALID";
        }
        if ("tap".equals(op)) {
            allowed.add("x"); allowed.add("y");
            if (!validCoordinate(c.optInt("x", -1))
                    || !validCoordinate(c.optInt("y", -1)))
                return "SHIZUKU_COORDINATE_INVALID";
        } else if ("swipe".equals(op)) {
            for (String key : new String[]{"x1", "y1", "x2", "y2"}) {
                allowed.add(key);
                if (!validCoordinate(c.optInt(key, -1)))
                    return "SHIZUKU_COORDINATE_INVALID";
            }
            allowed.add("durationMs");
            int ms = c.optInt("durationMs", 300);
            if (ms < 100 || ms > 3000) return "SHIZUKU_DURATION_INVALID";
        }
        Iterator<String> keys = c.keys();
        while (keys.hasNext())
            if (!allowed.contains(keys.next())) return "SHIZUKU_ARGUMENT_DENIED";
        return "";
    }

    private static boolean requiresDisplay(String op) {
        return "preview".equals(op) || "tap".equals(op)
                || "swipe".equals(op) || "front".equals(op)
                || "close".equals(op);
    }

    private static boolean validCoordinate(int n) { return n >= 0 && n <= 8192; }
    private static boolean validPackage(String p) {
        return p != null && p.length() < 180
                && p.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    private static JSONObject sendToShell(JSONObject request) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(
                    BackgroundDisplayShell.SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(8000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(request.toString());
            writer.write('\n');
            writer.flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            java.io.InputStream stream = socket.getInputStream();
            int ch;
            while ((ch = stream.read()) != -1 && ch != '\n') {
                if (reply.size() >= 2 * 1024 * 1024)
                    return new JSONObject().put("success", false)
                            .put("error", "SHIZUKU_RELAY_RESPONSE_TOO_LARGE");
                reply.write(ch);
            }
            if (reply.size() == 0) return new JSONObject()
                    .put("success", false).put("error", "SHIZUKU_EMPTY_RESPONSE");
            return new JSONObject(reply.toString(StandardCharsets.UTF_8));
        }
    }

    private static String error(String code) {
        try { return new JSONObject().put("success", false)
                    .put("error", code).toString(); }
        catch (Exception ignored) { return "{\\"success\\":false}"; }
    }

    private String resolveOwnApk() {
        try {
            if (serviceContext != null) {
                ApplicationInfo info = serviceContext.getPackageManager()
                        .getApplicationInfo(PACKAGE, 0);
                if (validApk(info.sourceDir)) return info.sourceDir;
            }
        } catch (Exception ignored) {}
        // Shizuku UserService Context is deliberately incomplete. Resolve via
        // fixed-argument 'pm path', not a user-controlled shell command.
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("pm", "path", PACKAGE)
                    .redirectErrorStream(true).start();
            String found = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("package:") && line.endsWith("/base.apk")) {
                        String candidate = line.substring("package:".length()).trim();
                        if (validApk(candidate)) found = candidate;
                        break;
                    }
                }
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            return found;
        } catch (Exception ignored) {
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static boolean validApk(String path) {
        return path != null && path.startsWith("/data/app/")
                && path.endsWith(".apk") && !path.contains("..")
                && !path.contains("\n") && !path.contains("\r")
                && new File(path).isFile();
    }

    @Override public void destroy() throws RemoteException {
        // Explicit remove=true unbind only; normal Activity destruction does
        // not call this, so the already launched helper can stay online.
        System.exit(0);
    }
}
