package com.crewpocket.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    /**
     * Start the direct Binder helper. No LocalSocket is created for this
     * child. The nonce is transferred via the child's private stdin, never
     * logged and never passed as a command-line argument.
     */
    @Override public synchronized String startHelperDirect(String nonce) {
        if (!authorizedCaller()) return "SHIZUKU_CALLER_NOT_AUTHORIZED";
        if (Process.myUid() != 2000) return "SHELL_UID_2000_REQUIRED";
        if (android.os.Build.VERSION.SDK_INT < 31) return "ANDROID_12_REQUIRED";
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{30,160}"))
            return "INVALID_HANDOFF_NONCE";
        String apk = resolveOwnApk();
        if (apk.isEmpty()) return "APP_APK_NOT_FOUND";
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/app_process", "/system/bin",
                    "--nice-name=crew-bg-direct",
                    BackgroundDisplayShell.class.getName(), "binder");
            builder.environment().put("CLASSPATH", apk);
            builder.redirectErrorStream(true);
            builder.redirectOutput(new File(LOG));
            java.lang.Process child = builder.start();
            try (OutputStream input = child.getOutputStream()) {
                input.write((nonce + "\n").getBytes(StandardCharsets.UTF_8));
                input.flush();
            }
            if (!child.isAlive() && child.exitValue() != 0)
                return "DIRECT_HELPER_EXIT_" + child.exitValue();
            return "LAUNCH_REQUESTED";
        } catch (Exception error) {
            return "DIRECT_HELPER_START_FAILED_" + error.getClass().getSimpleName();
        }
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

    // Direct Shell Binder now handles ALL background commands. Shizuku
    // UserService is only permitted to bootstrap the fixed helper process.
    private boolean authorizedCaller() {
        return BackgroundShizukuCommandPolicy.authorizedCaller(
                Process.myUid(), appUid(), Binder.getCallingUid());
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
