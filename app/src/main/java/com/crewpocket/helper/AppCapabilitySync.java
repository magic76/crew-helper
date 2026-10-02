package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** HTTPS-only remote capability manifest sync. */
final class AppCapabilitySync {
    interface Callback {
        void onComplete(JSONObject result);
    }

    private static final int MAX_BYTES = 512 * 1024;
    private static final long AUTO_SYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private AppCapabilitySync() {}

    static void maybeSync(
            Context context,
            AppCapabilityStore store) {
        if (store == null) return;
        String url = store.remoteUrl();
        if (url.isEmpty()) return;
        long last = store.lastSyncAt();
        if (last > 0L
                && System.currentTimeMillis() - last
                        < AUTO_SYNC_INTERVAL_MS) {
            return;
        }
        sync(context, store, null);
    }

    static void sync(
            Context context,
            final AppCapabilityStore store,
            final Callback callback) {
        final Context app = context == null
                ? null
                : context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject result = syncNow(store);
                if (callback == null) return;
                if (app == null) {
                    callback.onComplete(result);
                    return;
                }
                new android.os.Handler(
                        android.os.Looper.getMainLooper())
                        .post(new Runnable() {
                            @Override public void run() {
                                callback.onComplete(result);
                            }
                        });
            }
        }, "crew-app-capability-sync").start();
    }

    private static JSONObject syncNow(AppCapabilityStore store) {
        if (store == null) return failure("STORE_UNAVAILABLE");
        String remote = store.remoteUrl();
        if (remote.isEmpty()) return failure("REMOTE_URL_NOT_SET");
        if (!remote.toLowerCase(java.util.Locale.ROOT)
                .startsWith("https://")) {
            return failure("REMOTE_URL_HTTPS_REQUIRED");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)
                    new URL(remote).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty(
                    "Accept",
                    "application/json");
            connection.setRequestProperty(
                    "User-Agent",
                    "CrewHelper-AppCapabilityRegistry/1");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return failure("REMOTE_HTTP_" + code);
            }

            int declared = connection.getContentLength();
            if (declared > MAX_BYTES) {
                return failure("REMOTE_MANIFEST_TOO_LARGE");
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            connection.getInputStream(),
                            "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (body.length() + line.length() > MAX_BYTES) {
                    reader.close();
                    return failure("REMOTE_MANIFEST_TOO_LARGE");
                }
                body.append(line);
            }
            reader.close();
            return store.importRemoteManifest(body.toString());
        } catch (Exception error) {
            JSONObject out = failure("REMOTE_SYNC_FAILED");
            try {
                out.put(
                        "detail",
                        error.getMessage() == null
                                ? ""
                                : error.getMessage());
            } catch (Exception ignored) {}
            return out;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject failure(String error) {
        try {
            return new JSONObject()
                    .put("success", false)
                    .put("error", error);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
