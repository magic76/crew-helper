package com.crewpocket.helper;

import android.content.Context;

import java.io.File;
import java.io.InputStream;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineManager;

/**
 * Low-power, on-device wake-word owner for 0025.
 *
 * The Mandarin parameter model is shipped as an app asset. The phrase-specific
 * Android .ppn is generated once via Picovoice's model API and then persisted in
 * app-private storage. Runtime detection after setup is local/offline.
 */
final class PorcupineWakeWordEngine {
    interface Listener {
        void onDetected();
        void onStatus(String status);
        void onError(String error);
    }

    private static final String MODEL_ASSET = "porcupine/porcupine_params_zh.pv";
    private static final String LANGUAGE = "zh";

    private final Context context;
    private final String accessKey;
    private final String phrase;
    private final float sensitivity;
    private final Listener listener;
    private PorcupineManager manager;
    private volatile boolean running;

    PorcupineWakeWordEngine(Context context, String accessKey, String phrase,
                            float sensitivity, Listener listener) {
        this.context = context.getApplicationContext();
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.phrase = phrase == null || phrase.trim().isEmpty() ? "小酷小酷" : phrase.trim();
        this.sensitivity = Math.max(0.05f, Math.min(0.95f, sensitivity));
        this.listener = listener;
    }

    synchronized boolean start() {
        if (running) return true;
        if (accessKey.isEmpty()) {
            emitError("Wake Word AccessKey 尚未設定");
            return false;
        }
        if (!hasMandarinModelAsset()) {
            emitError("缺少中文模型檔：app/src/main/assets/" + MODEL_ASSET);
            return false;
        }

        try {
            File keyword = ensureKeywordModel();
            listener.onStatus("正在啟動本機喚醒詞「" + phrase + "」");

            manager = new PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(keyword.getAbsolutePath())
                    .setModelPath(MODEL_ASSET)
                    .setSensitivity(sensitivity)
                    .setErrorCallback(error -> emitError("Wake Word 執行錯誤：" + safeMessage(error)))
                    .build(context, keywordIndex -> {
                        Listener callback = listener;
                        if (callback != null) callback.onDetected();
                    });
            manager.start();
            running = true;
            return true;
        } catch (Exception error) {
            release();
            emitError("Wake Word 啟動失敗：" + safeMessage(error));
            return false;
        }
    }

    private File ensureKeywordModel() throws PorcupineException {
        File dir = new File(context.getFilesDir(), "wakeword");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("無法建立 wakeword 私有目錄");
        }

        String safeId = Integer.toHexString((LANGUAGE + "|" + phrase).hashCode());
        File finalFile = new File(dir, "keyword_" + safeId + ".ppn");
        if (finalFile.isFile() && finalFile.length() > 0) return finalFile;

        listener.onStatus("首次建立喚醒詞模型「" + phrase + "」…");
        File temp = new File(dir, "keyword_" + safeId + ".tmp.ppn");
        if (temp.exists()) temp.delete();

        Porcupine.trainWakeWordFromPhrase(
                accessKey,
                temp.getAbsolutePath(),
                LANGUAGE,
                phrase);

        if (!temp.isFile() || temp.length() == 0) {
            throw new IllegalStateException("喚醒詞模型建立後檔案為空");
        }
        if (finalFile.exists()) finalFile.delete();
        if (!temp.renameTo(finalFile)) {
            throw new IllegalStateException("無法保存喚醒詞模型");
        }
        return finalFile;
    }

    private boolean hasMandarinModelAsset() {
        InputStream input = null;
        try {
            input = context.getAssets().open(MODEL_ASSET);
            return input.read() >= 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
        }
    }

    synchronized void release() {
        running = false;
        PorcupineManager closing = manager;
        manager = null;
        if (closing != null) {
            try { closing.stop(); } catch (Exception ignored) {}
            try { closing.delete(); } catch (Exception ignored) {}
        }
    }

    boolean isRunning() { return running; }

    private void emitError(String text) {
        Listener callback = listener;
        if (callback != null) callback.onError(text);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }
}
