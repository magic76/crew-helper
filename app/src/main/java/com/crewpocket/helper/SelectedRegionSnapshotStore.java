package com.crewpocket.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Freezes the exact screen pixels that existed when the user selected a region. */
final class SelectedRegionSnapshotStore {
    interface Callback {
        void onResult(SelectedRegionContext selected, String error);
    }

    private static final long CLEANUP_AGE_MS = SelectedRegionContext.TTL_MS * 5L;

    private SelectedRegionSnapshotStore() {}

    static void capture(
            Context context,
            SelectedRegionContext selected,
            Callback callback) {
        if (context == null || selected == null || callback == null) return;
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override public void run() {
                SelectedRegionContext frozen = null;
                String error = "";
                try {
                    if (!selected.isFresh() || selected.hardSensitive) {
                        throw new Exception("選取內容已失效，請重新選取");
                    }
                    JSONObject capture = requestScreenshot(app);
                    if (!capture.optBoolean("success", false)) {
                        throw new Exception(capture.optString("error", "無法擷取目前畫面"));
                    }
                    String path = capture.optString(
                            "path",
                            capture.optString("latestPath", ""));
                    if (path == null || path.trim().isEmpty()) {
                        throw new Exception("背景截圖沒有檔案路徑");
                    }
                    File copy = freeze(app, new File(path.trim()), selected.createdAt);
                    frozen = selected.withSnapshotPath(copy.getAbsolutePath());
                } catch (Exception failure) {
                    error = failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage();
                }

                final SelectedRegionContext result = frozen;
                final String detail = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        callback.onResult(result, detail);
                    }
                });
            }
        }, "crew-selected-region-freeze").start();
    }

    static void deleteQuietly(SelectedRegionContext selected) {
        if (selected == null || selected.snapshotPath == null
                || selected.snapshotPath.trim().isEmpty()) return;
        try {
            File file = new File(selected.snapshotPath.trim());
            if (file.isFile()) file.delete();
        } catch (Exception ignored) {}
    }

    private static JSONObject requestScreenshot(Context app) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:8766/screenshot").openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8");
            String token = AppConfig.getLocalBridgeToken(app);
            if (token == null || token.isEmpty()) {
                throw new Exception("本機畫面橋接尚未就緒");
            }
            connection.setRequestProperty("X-Crew-Bridge-Token", token);
            connection.setDoOutput(true);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(8000);

            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (stream == null) {
                throw new Exception("背景截圖服務沒有回應");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
            JSONObject response = text.length() == 0
                    ? new JSONObject()
                    : new JSONObject(text.toString());
            if (!response.has("success")) {
                response.put("success", code >= 200 && code < 300);
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static File freeze(Context app, File source, long createdAt) throws Exception {
        if (source == null || !source.isFile()) {
            throw new Exception("背景截圖檔案不存在");
        }
        File dir = new File(app.getFilesDir(), "selected_regions");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("無法建立選取暫存目錄");
        }
        cleanup(dir);

        File destination = new File(
                dir,
                "region_" + createdAt + "_" + System.nanoTime() + ".snapshot");
        FileInputStream in = new FileInputStream(source);
        FileOutputStream out = new FileOutputStream(destination);
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.getFD().sync();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
        return destination;
    }

    private static void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            long age = now - file.lastModified();
            if (age > CLEANUP_AGE_MS || age < 0L) {
                try { file.delete(); } catch (Exception ignored) {}
            }
        }
    }
}
