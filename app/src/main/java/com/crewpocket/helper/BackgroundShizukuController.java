package com.crewpocket.helper;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import rikka.shizuku.Shizuku;

/**
 * Explicit, UI-initiated Shizuku authorization and narrow UserService binding.
 * No ADB pairing, Wi-Fi toggle, root, or arbitrary shell commands are invoked.
 */
final class BackgroundShizukuController {
    interface Feedback { void onResult(String message, boolean finished); }
    private static final int PERMISSION_REQUEST = 6197;
    private final Activity activity;
    private final ExecutorService worker;
    private final Feedback feedback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Shizuku.UserServiceArgs args;
    private volatile boolean destroyed;
    private volatile boolean pending;
    private volatile boolean bound;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, result) -> {
                if (requestCode != PERMISSION_REQUEST || !pending || destroyed) return;
                if (result == PackageManager.PERMISSION_GRANTED) bind();
                else finish("SHIZUKU_PERMISSION_DENIED：請到 Shizuku 授權 Crew Helper。");
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed || !pending) return;
            IBackgroundShizukuService remote = IBackgroundShizukuService.Stub.asInterface(binder);
            worker.execute(() -> startRemote(remote));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (!destroyed && pending) finish("SHIZUKU_SERVICE_DISCONNECTED");
        }
    };

    BackgroundShizukuController(Activity activity, ExecutorService worker,
                                Feedback feedback) {
        this.activity = activity;
        this.worker = worker;
        this.feedback = feedback;
        int version = 1;
        try {
            version = activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {}
        args = new Shizuku.UserServiceArgs(new ComponentName(
                activity.getPackageName(), BackgroundShizukuService.class.getName()))
                .daemon(true).processNameSuffix("background")
                .version(version);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    void start() {
        if (destroyed || pending) return;
        pending = true;
        report("正在檢查背景 Helper 與 Shizuku…", false);
        main.postDelayed(() -> {
            if (pending && !destroyed)
                finish("SHIZUKU_START_TIMEOUT：請確認 Shizuku 正在執行，再按一次啟動。");
        }, 15_000L);
        worker.execute(() -> {
            org.json.JSONObject status = BackgroundDisplayClient.command("status");
            main.post(() -> {
                if (destroyed || !pending) return;
                if (status.optBoolean("success", false)) {
                    finish("背景 Helper 已運作 · protocol "
                            + status.optInt("protocolVersion", -1)
                            + " · Display " + status.optInt("displayId", -1));
                } else if ("HELPER_RESTART_REQUIRED".equals(status.optString("error"))) {
                    finish("舊版 Helper 正在執行。請先用原本 ADB 方法停止該程序，"
                            + "再以 Shizuku 啟動；不會強制關閉他人的程序。");
                } else {
                    checkPermission();
                }
            });
        });
    }

    String status() {
        try {
            if (!Shizuku.pingBinder())
                return "SHIZUKU_NOT_RUNNING（先開 Shizuku App 啟動服務）";
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13)
                return "SHIZUKU_V13_REQUIRED";
            if (Shizuku.getUid() != 2000)
                return "SHIZUKU_SHELL_UID_REQUIRED（此版本只支援 ADB Shell）";
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? "SHIZUKU_READY：已授權" : "SHIZUKU_PERMISSION_REQUIRED";
        } catch (Exception error) {
            return "SHIZUKU_STATUS_UNAVAILABLE";
        }
    }

    private void checkPermission() {
        String state = status();
        if ("SHIZUKU_READY：已授權".equals(state)) {
            bind();
        } else if ("SHIZUKU_PERMISSION_REQUIRED".equals(state)) {
            try {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    finish("Shizuku 已拒絕授權。請到 Shizuku App 的授權管理手動允許。");
                } else {
                    report("請在 Shizuku 授權視窗按允許…", false);
                    Shizuku.requestPermission(PERMISSION_REQUEST);
                }
            } catch (Exception error) {
                finish("SHIZUKU_PERMISSION_REQUEST_FAILED");
            }
        } else {
            finish(state);
        }
    }

    private void bind() {
        if (destroyed || !pending) return;
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                finish("SHIZUKU_AUTHORIZATION_LOST");
                return;
            }
            report("Shizuku 已授權，正在連接 Shell UserService…", false);
            Shizuku.bindUserService(args, connection);
            bound = true;
        } catch (Exception error) {
            finish("SHIZUKU_BIND_FAILED_" + error.getClass().getSimpleName());
        }
    }

    private void startRemote(IBackgroundShizukuService remote) {
        String result;
        try {
            if (remote == null || remote.shellUid() != 2000) {
                result = "SHIZUKU_SHELL_IDENTITY_MISMATCH";
            } else {
                String launch = remote.startHelper();
                if (!"LAUNCH_REQUESTED".equals(launch)) {
                    result = launch;
                } else {
                    result = "SHIZUKU_HELPER_SOCKET_NOT_READY";
                    for (int attempt = 0; attempt < 12 && pending && !destroyed; attempt++) {
                        org.json.JSONObject state = BackgroundDisplayClient.command("status");
                        if (state.optBoolean("success", false)) {
                            result = "SHIZUKU_HELPER_CONNECTED · protocol "
                                    + state.optInt("protocolVersion", -1)
                                    + " · Display " + state.optInt("displayId", -1);
                            break;
                        }
                        String error = state.optString("error", "");
                        if ("HELPER_RESTART_REQUIRED".equals(error)) {
                            result = error;
                            break;
                        }
                        android.os.SystemClock.sleep(300);
                    }
                }
            }
        } catch (Exception error) {
            result = "SHIZUKU_USER_SERVICE_FAILED_" + error.getClass().getSimpleName();
        }
        final String finishedResult = result;
        finish(finishedResult);
    }

    private void report(String message, boolean finished) {
        main.post(() -> {
            if (!destroyed) feedback.onResult(message, finished);
        });
    }

    private void finish(String message) {
        pending = false;
        report(message, true);
    }

    void destroy() {
        destroyed = true;
        pending = false;
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        if (bound) {
            try {
                // Detach callbacks only. Do not stop the daemon or background App.
                Shizuku.unbindUserService(args, connection, false);
            } catch (Exception ignored) {}
        }
    }
}
