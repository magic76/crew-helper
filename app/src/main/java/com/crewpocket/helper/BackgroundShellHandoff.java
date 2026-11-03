package com.crewpocket.helper;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Base64;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Mika-style shell-to-App Binder handoff, with a short-lived one-use nonce.
 * Only the exact APK currently installed as Crew Helper may hand a Binder
 * back to this App. The bridge stays process-wide, not Activity-scoped.
 */
final class BackgroundShellHandoff {
    static final String AUTHORITY = "com.crewpocket.helper.shellhandoff";
    static final String METHOD_ATTACH = "attach";
    static final String KEY_NONCE = "nonce";
    static final String KEY_BINDER = "binder";
    static final String KEY_APK = "apk";
    static final String KEY_UID = "uid";
    static final String KEY_PID = "pid";
    static final String KEY_APP_TOKEN = "appToken";
    private static final long NONCE_TTL_MS = 60_000L;
    private static final int MAX_REPLY_BYTES = 2 * 1024 * 1024;
    private static final long PIPE_RESPONSE_TIMEOUT_MS = 15_000L;
    private static final SecureRandom RNG = new SecureRandom();
    private static final IBinder APP_TOKEN = new Binder();

    private static String pendingNonce = "";
    private static long nonceIssuedAt;
    private static volatile IBackgroundDirectShell direct;
    private static IBinder directBinder;

    private BackgroundShellHandoff() {}

    static synchronized String issueLaunchNonce() {
        byte[] data = new byte[32];
        RNG.nextBytes(data);
        pendingNonce = Base64.encodeToString(data,
                Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        nonceIssuedAt = SystemClock.elapsedRealtime();
        return pendingNonce;
    }

    static synchronized void cancelNonce() {
        pendingNonce = "";
        nonceIssuedAt = 0L;
    }

    static boolean connected() {
        IBackgroundDirectShell service = direct;
        if (service == null || !service.asBinder().isBinderAlive()) {
            direct = null;
            return false;
        }
        return true;
    }

    static synchronized Bundle offer(Context app, Bundle extras,
                                     int callingUid, int callingPid) {
        Bundle response = new Bundle();
        response.putBoolean("accepted", false);
        if (app == null || extras == null || callingUid != 2000
                || callingPid <= 0 || connected()) {
            // A currently running direct helper must not be silently replaced
            // by an unverified second process.
            if (app == null || extras == null || callingUid != 2000 || callingPid <= 0) {
                response.putString("reason", "INVALID_CALLER");
                return response;
            }
            response.putString("reason", "HELPER_ALREADY_ATTACHED");
            return response;
        }
        String expectedApk = app.getApplicationInfo().sourceDir;
        String givenApk = extras.getString(KEY_APK, "");
        String nonce = extras.getString(KEY_NONCE, "");
        if (!BackgroundShellHandoffPolicy.check(
                callingUid, callingPid, extras.getInt(KEY_UID, -1),
                extras.getInt(KEY_PID, -1), expectedApk, givenApk,
                pendingNonce, nonce, SystemClock.elapsedRealtime(),
                nonceIssuedAt, NONCE_TTL_MS)) {
            response.putString("reason", "HANDOFF_IDENTITY_OR_NONCE_MISMATCH");
            return response;
        }
        IBinder raw = extras.getBinder(KEY_BINDER);
        if (raw == null || !raw.isBinderAlive()) {
            response.putString("reason", "HANDOFF_BINDER_MISSING");
            return response;
        }
        IBackgroundDirectShell shell = IBackgroundDirectShell.Stub.asInterface(raw);
        try {
            int[] identity = shell.identity();
            if (identity == null || identity.length != 2
                    || identity[0] != callingUid || identity[1] != callingPid) {
                response.putString("reason", "HANDOFF_BINDER_WRONG_PROCESS");
                return response;
            }
            raw.linkToDeath(() -> {
                synchronized (BackgroundShellHandoff.class) {
                    if (directBinder == raw) {
                        direct = null;
                        directBinder = null;
                    }
                }
            }, 0);
        } catch (Exception invalid) {
            response.putString("reason", "HANDOFF_BINDER_IDENTITY_FAILED");
            return response;
        }
        // Consume the one-time nonce only AFTER all checks and death link.
        cancelNonce();
        directBinder = raw;
        direct = shell;
        response.putBinder(KEY_APP_TOKEN, APP_TOKEN);
        response.putBoolean("accepted", true);
        return response;
    }

    static JSONObject request(JSONObject operation) {
        IBackgroundDirectShell shell = direct;
        if (!connected() || shell == null) return fail("DIRECT_SHELL_BINDER_NOT_CONNECTED");
        String raw = operation == null ? "" : operation.toString();
        if (raw.isEmpty() || raw.getBytes(StandardCharsets.UTF_8).length > 8192)
            return fail("DIRECT_SHELL_INVALID_REQUEST");
        try {
            ParcelFileDescriptor source = shell.executeCommand(raw);
            if (source == null) return fail("DIRECT_SHELL_PIPE_FAILED");
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(source);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                StructPollfd pollFd = new StructPollfd();
                pollFd.fd = source.getFileDescriptor();
                pollFd.events = (short) (OsConstants.POLLIN
                        | OsConstants.POLLHUP | OsConstants.POLLERR);
                final long deadline = SystemClock.elapsedRealtime()
                        + PIPE_RESPONSE_TIMEOUT_MS;
                for (;;) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0L)
                        return fail("DIRECT_SHELL_RESPONSE_TIMEOUT");
                    if (Os.poll(new StructPollfd[]{pollFd}, (int) remaining) == 0)
                        return fail("DIRECT_SHELL_RESPONSE_TIMEOUT");
                    int n = in.read(buffer);
                    if (n < 0) break;
                    if (out.size() + n > MAX_REPLY_BYTES)
                        return fail("DIRECT_SHELL_RESPONSE_TOO_LARGE");
                    out.write(buffer, 0, n);
                }
                JSONObject answer = new JSONObject(out.toString(StandardCharsets.UTF_8));
                answer.put("transport", "DIRECT_SHELL_BINDER");
                return answer;
            }
        } catch (android.os.DeadObjectException died) {
            direct = null;
            return fail("DIRECT_SHELL_DIED");
        } catch (Exception problem) {
            return fail("DIRECT_SHELL_" + problem.getClass().getSimpleName());
        }
    }

    private static JSONObject fail(String reason) {
        JSONObject obj = new JSONObject();
        try { obj.put("success", false).put("error", reason); }
        catch (Exception ignored) {}
        return obj;
    }
}
