package com.crewpocket.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
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
