package com.crewpocket.helper;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * App-side transport for background display operations. It never connects to
 * the shell-owned LocalSocket itself when an authorized Shizuku relay is bound.
 * Large JPEG previews stream over an AIDL ParcelFileDescriptor, not a Binder
 * String transaction (which can exceed Android's binder buffer).
 */
final class BackgroundShizukuRelay {
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final int MAX_REPLY_BYTES = 2 * 1024 * 1024;
    private static volatile IBackgroundShizukuService remote;

    private BackgroundShizukuRelay() {}

    static boolean attach(IBackgroundShizukuService service) {
        try {
            if (service == null || !service.asBinder().isBinderAlive()
                    || service.shellUid() != 2000) return false;
            remote = service;
            return true;
        } catch (Exception ignored) {
            remote = null;
            return false;
        }
    }

    static boolean connected() {
        IBackgroundShizukuService service = remote;
        return service != null && service.asBinder().isBinderAlive();
    }

    static void detach() { remote = null; }

    static JSONObject request(JSONObject command) {
        IBackgroundShizukuService service = remote;
        if (service == null || !service.asBinder().isBinderAlive()) {
            remote = null;
            return failure("SHIZUKU_RELAY_NOT_CONNECTED");
        }
        if (command == null || command.toString().getBytes(StandardCharsets.UTF_8).length
                > MAX_REQUEST_BYTES) return failure("SHIZUKU_REQUEST_TOO_LARGE");
        try {
            ParcelFileDescriptor pipe = service.relay(command.toString());
            if (pipe == null) return failure("SHIZUKU_RELAY_NO_PIPE");
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe);
                 ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (result.size() + n > MAX_REPLY_BYTES)
                        return failure("SHIZUKU_RELAY_REPLY_TOO_LARGE");
                    result.write(buffer, 0, n);
                }
                JSONObject response = new JSONObject(result.toString(StandardCharsets.UTF_8));
                response.put("transport", "SHIZUKU_BINDER");
                return response;
            }
        } catch (RemoteException error) {
            remote = null;
            return failure("SHIZUKU_RELAY_DISCONNECTED");
        } catch (Exception error) {
            return failure("SHIZUKU_RELAY_" + error.getClass().getSimpleName());
        }
    }

    private static JSONObject failure(String reason) {
        JSONObject out = new JSONObject();
        try { out.put("success", false).put("error", reason); }
        catch (Exception ignored) {}
        return out;
    }
}
