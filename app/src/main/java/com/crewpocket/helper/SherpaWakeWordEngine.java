package com.crewpocket.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

final class SherpaWakeWordEngine {
    interface Listener {
        void onDetected();
        void onStatus(String status);
        void onError(String error);
    }

    private static final String TAG = "SherpaWakeWord";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SAMPLES = 1600;

    private static final String MODEL_DIR = "sherpa-kws";
    private static final String ENCODER =
            MODEL_DIR + "/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
    private static final String DECODER =
            MODEL_DIR + "/decoder-epoch-13-avg-2-chunk-16-left-64.onnx";
    private static final String JOINER =
            MODEL_DIR + "/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
    private static final String TOKENS = MODEL_DIR + "/tokens.txt";
    private static final String KEYWORDS = MODEL_DIR + "/keywords.txt";

    private static final String SUPPORTED_PHRASE = "小酷小酷";
    private static final String KEYWORD_TOKENS =
            "x iǎo k ù x iǎo k ù @小酷小酷";

    private final Context context;
    private final String phrase;
    private final float sensitivity;
    private final Listener listener;

    private volatile boolean running;
    private volatile boolean detectionLatched;
    private volatile AudioRecord audioRecord;
    private volatile Thread worker;
    private KeywordSpotter spotter;
    private OnlineStream stream;

    private volatile String phase = "CREATED";
    private volatile String assetStatus = "UNCHECKED";
    private volatile String lastError = "";
    private volatile String lastKeyword = "";
    private volatile int lastReadResult;
    private volatile long audioReadCount;
    private volatile long audioSampleCount;
    private volatile long decodeCount;
    private volatile long lastAudioAtMs;
    private volatile long lastDecodeAtMs;
    private volatile long startedAtMs;

    SherpaWakeWordEngine(Context context, String phrase, float sensitivity, Listener listener) {
        this.context = context.getApplicationContext();
        this.phrase = phrase == null || phrase.trim().isEmpty()
                ? SUPPORTED_PHRASE : phrase.trim();
        this.sensitivity = clamp(sensitivity, 0.05f, 0.95f);
        this.listener = listener;
    }

    synchronized boolean start() {
        if (running) return true;

        phase = "VALIDATING";
        lastError = "";
        lastKeyword = "";
        lastReadResult = 0;
        audioReadCount = 0;
        audioSampleCount = 0;
        decodeCount = 0;
        lastAudioAtMs = 0;
        lastDecodeAtMs = 0;
        startedAtMs = SystemClock.elapsedRealtime();

        if (!SUPPORTED_PHRASE.equals(phrase)) {
            phase = "FAILED_PHRASE";
            emitError("0025-hotfix 目前只支援喚醒詞「" + SUPPORTED_PHRASE + "」");
            return false;
        }
        if (!hasRecordAudioPermission()) {
            phase = "FAILED_PERMISSION";
            emitError("Wake Word 缺少麥克風權限");
            return false;
        }

        phase = "CHECKING_ASSETS";
        String missing = firstMissingAsset();
        if (missing != null) {
            assetStatus = "MISSING " + missing;
            phase = "FAILED_ASSET";
            emitError("缺少 sherpa Wake Word 模型資源：" + missing);
            return false;
        }
        assetStatus = "OK";

        try {
            phase = "LOADING_MODEL";
            emitStatus("正在載入本機喚醒詞「" + phrase + "」");
            initSpotter();

            phase = "MODEL_READY";
            phase = "OPENING_MIC";
            AudioRecord record = createAudioRecord();

            phase = "STARTING_MIC";
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                try { record.release(); } catch (Exception ignored) {}
                throw new IllegalStateException("AudioRecord 無法開始錄音");
            }

            audioRecord = record;
            detectionLatched = false;
            running = true;
            phase = "MIC_RECORDING";

            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    runLoop();
                }
            }, "crew-sherpa-wake");
            worker = t;
            t.start();

            phase = "LISTENING";
            emitStatus("本機喚醒詞已啟動：「" + phrase + "」");
            return true;
        } catch (Throwable error) {
            running = false;
            phase = "START_FAILED";
            stopAndReleaseAudioRecord();
            releaseModels();
            emitError("sherpa Wake Word 啟動失敗：" + safeMessage(error));
            return false;
        }
    }

    private void initSpotter() {
        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(SAMPLE_RATE);
        feature.setFeatureDim(80);
        feature.setDither(0.0f);

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(ENCODER);
        transducer.setDecoder(DECODER);
        transducer.setJoiner(JOINER);

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(TOKENS);
        model.setNumThreads(1);
        model.setDebug(false);
        model.setProvider("cpu");
        model.setModelType("");
        model.setModelingUnit("cjkchar");

        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setMaxActivePaths(4);
        config.setKeywordsFile(KEYWORDS);
        config.setKeywordsScore(1.0f);
        config.setKeywordsThreshold(thresholdForSensitivity(sensitivity));
        config.setNumTrailingBlanks(1);

        spotter = new KeywordSpotter(context.getAssets(), config);
        stream = spotter.createStream(KEYWORD_TOKENS);
    }

    private AudioRecord createAudioRecord() {
        int minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBytes <= 0) {
            throw new IllegalStateException("無法取得 AudioRecord buffer size：" + minBytes);
        }

        int bufferBytes = Math.max(minBytes * 2, FRAME_SAMPLES * 2 * 2);
        AudioRecord record = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferBytes);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try { record.release(); } catch (Exception ignored) {}
            throw new IllegalStateException("AudioRecord 初始化失敗");
        }
        return record;
    }

    private void runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] pcm = new short[FRAME_SAMPLES];

        try {
            phase = "LISTENING";
            while (running) {
                AudioRecord record = audioRecord;
                if (record == null) break;

                int count = record.read(pcm, 0, pcm.length);
                lastReadResult = count;
                if (!running) break;

                if (count <= 0) {
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("AudioRecord dead object");
                    }
                    continue;
                }

                audioReadCount++;
                audioSampleCount += count;
                lastAudioAtMs = SystemClock.elapsedRealtime();

                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    samples[i] = pcm[i] / 32768.0f;
                }

                OnlineStream localStream = stream;
                KeywordSpotter localSpotter = spotter;
                if (localStream == null || localSpotter == null) {
                    throw new IllegalStateException("KeywordSpotter stream/model disappeared");
                }

                localStream.acceptWaveform(samples, SAMPLE_RATE);

                while (running && localSpotter.isReady(localStream)) {
                    localSpotter.decode(localStream);
                    decodeCount++;
                    lastDecodeAtMs = SystemClock.elapsedRealtime();

                    KeywordSpotterResult result = localSpotter.getResult(localStream);
                    String keyword = result == null ? "" : result.getKeyword();

                    if (keyword != null && !keyword.trim().isEmpty()) {
                        lastKeyword = keyword.trim();
                        phase = "DETECTED";
                        localSpotter.reset(localStream);
                        if (!detectionLatched) {
                            detectionLatched = true;
                            Log.i(TAG, "Wake phrase detected: " + keyword);
                            Listener callback = listener;
                            if (callback != null) callback.onDetected();
                        }
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            if (running) {
                phase = "RUNTIME_ERROR";
                emitError("sherpa Wake Word 執行錯誤：" + safeMessage(error));
            }
        } finally {
            running = false;
            stopAndReleaseAudioRecord();
            releaseModels();
            if (!"RUNTIME_ERROR".equals(phase)
                    && !"START_FAILED".equals(phase)
                    && !"DETECTED".equals(phase)
                    && !phase.startsWith("FAILED_")) {
                phase = "STOPPED";
            }
        }
    }

    synchronized void release() {
        running = false;
        detectionLatched = true;
        if (!"DETECTED".equals(phase) && !"RUNTIME_ERROR".equals(phase)) {
            phase = "RELEASING";
        }

        stopAndReleaseAudioRecord();

        Thread t = worker;
        worker = null;
        if (t == null || !t.isAlive()) {
            releaseModels();
        }
        if (!"DETECTED".equals(phase) && !"RUNTIME_ERROR".equals(phase)) {
            phase = "RELEASED";
        }
    }

    boolean isRunning() {
        return running;
    }

    String getDiagnostics() {
        long now = SystemClock.elapsedRealtime();
        AudioRecord record = audioRecord;
        Thread t = worker;

        String audioState = "null";
        String recordingState = "null";
        if (record != null) {
            try { audioState = audioStateName(record.getState()); } catch (Throwable ignored) {}
            try { recordingState = recordingStateName(record.getRecordingState()); } catch (Throwable ignored) {}
        }

        return "engine.phase=" + phase
                + "\nengine.running=" + running
                + "\nengine.assets=" + assetStatus
                + "\nengine.spotter=" + (spotter != null ? "READY" : "null")
                + "\nengine.stream=" + (stream != null ? "READY" : "null")
                + "\nengine.workerAlive=" + (t != null && t.isAlive())
                + "\nmic.audioRecord=" + audioState
                + "\nmic.recording=" + recordingState
                + "\naudio.lastReadResult=" + lastReadResult
                + "\naudio.readCount=" + audioReadCount
                + "\naudio.sampleCount=" + audioSampleCount
                + "\naudio.lastAgoMs=" + ageMs(now, lastAudioAtMs)
                + "\ndecode.count=" + decodeCount
                + "\ndecode.lastAgoMs=" + ageMs(now, lastDecodeAtMs)
                + "\nkeyword.last=" + blankAsDash(lastKeyword)
                + "\nengine.sensitivity=" + sensitivity
                + "\nengine.threshold=" + thresholdForSensitivity(sensitivity)
                + "\nengine.uptimeMs=" + ageMs(now, startedAtMs)
                + "\nengine.lastError=" + blankAsDash(lastError);
    }

    private synchronized void stopAndReleaseAudioRecord() {
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record == null) return;
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Exception ignored) {}
        try { record.release(); } catch (Exception ignored) {}
    }

    private synchronized void releaseModels() {
        OnlineStream oldStream = stream;
        stream = null;
        if (oldStream != null) {
            try { oldStream.release(); } catch (Throwable ignored) {}
        }

        KeywordSpotter oldSpotter = spotter;
        spotter = null;
        if (oldSpotter != null) {
            try { oldSpotter.release(); } catch (Throwable ignored) {}
        }
    }

    private boolean hasRecordAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private String firstMissingAsset() {
        String[] paths = {ENCODER, DECODER, JOINER, TOKENS, KEYWORDS};
        for (String path : paths) {
            java.io.InputStream in = null;
            try {
                in = context.getAssets().open(path);
                if (in.read() < 0) return path;
            } catch (Exception error) {
                return path;
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static float thresholdForSensitivity(float value) {
        float threshold = 0.46666667f - (value / 3.0f);
        return clamp(threshold, 0.15f, 0.45f);
    }

    private void emitStatus(String text) {
        Listener callback = listener;
        if (callback != null) callback.onStatus(text);
    }

    private void emitError(String text) {
        lastError = text == null ? "" : text;
        Log.e(TAG, lastError);
        Listener callback = listener;
        if (callback != null) callback.onError(lastError);
    }

    private static String audioStateName(int state) {
        if (state == AudioRecord.STATE_INITIALIZED) return "INITIALIZED";
        if (state == AudioRecord.STATE_UNINITIALIZED) return "UNINITIALIZED";
        return String.valueOf(state);
    }

    private static String recordingStateName(int state) {
        if (state == AudioRecord.RECORDSTATE_RECORDING) return "RECORDING";
        if (state == AudioRecord.RECORDSTATE_STOPPED) return "STOPPED";
        return String.valueOf(state);
    }

    private static String ageMs(long now, long timestamp) {
        return timestamp <= 0 ? "-" : String.valueOf(Math.max(0, now - timestamp));
    }

    private static String blankAsDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message.trim();
    }
}
