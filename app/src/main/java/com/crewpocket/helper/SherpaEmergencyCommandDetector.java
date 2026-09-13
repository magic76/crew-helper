package com.crewpocket.helper;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 0107 local emergency command detector for ACTIVE Gemini Live sessions.
 *
 * This class NEVER opens AudioRecord. The existing Gemini mic remains the
 * single owner and feeds PCM16 frames here as a sidecar. Only the explicit
 * phrase "小酷停止" is recognized. No transcript/audio is persisted.
 */
final class SherpaEmergencyCommandDetector {
    interface Listener {
        void onEmergencyStop();
    }

    private static final String TAG = "SherpaEmergencyKws";
    private static final int SAMPLE_RATE = 16000;
    private static final String MODEL_DIR = "sherpa-kws";
    private static final String ENCODER =
            MODEL_DIR + "/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
    private static final String DECODER =
            MODEL_DIR + "/decoder-epoch-13-avg-2-chunk-16-left-64.onnx";
    private static final String JOINER =
            MODEL_DIR + "/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
    private static final String TOKENS = MODEL_DIR + "/tokens.txt";
    private static final String KEYWORDS = MODEL_DIR + "/keywords.txt";
    private static final String STOP_KEYWORD_TOKENS =
            "x iǎo k ù t íng zh ǐ @小酷停止";

    // Emergency stop should prefer false-negative over false-positive.
    // Current normal wake threshold is sensitivity-dependent (~0.28 at 55).
    private static final float STOP_THRESHOLD = 0.30f;
    private static final long DETECTION_COOLDOWN_MS = 2200L;
    private static final int QUEUE_CAPACITY = 16; // <= 640 ms at 40 ms/frame

    private final Context context;
    private final Listener listener;
    private final ArrayBlockingQueue<byte[]> pcmQueue =
            new ArrayBlockingQueue<byte[]>(QUEUE_CAPACITY);

    private volatile boolean running;
    private volatile boolean ready;
    private volatile Thread worker;
    private volatile long lastDetectedAtMs;
    private KeywordSpotter spotter;
    private OnlineStream stream;

    SherpaEmergencyCommandDetector(Context context, Listener listener) {
        this.context = context == null ? null : context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start() {
        if (running || context == null) return;
        running = true;
        ready = false;
        pcmQueue.clear();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                runLoop();
            }
        }, "crew-sherpa-emergency-kws");
        worker = t;
        t.start();
    }

    void offerPcm16(byte[] pcm, int count) {
        if (!running || !ready || pcm == null || count < 2) return;
        int safeCount = Math.min(count, pcm.length);
        byte[] copy = new byte[safeCount];
        System.arraycopy(pcm, 0, copy, 0, safeCount);
        if (!pcmQueue.offer(copy)) {
            // Never back-pressure Gemini's microphone loop. Keep newest audio.
            pcmQueue.poll();
            pcmQueue.offer(copy);
        }
    }

    synchronized void stop() {
        running = false;
        ready = false;
        pcmQueue.clear();
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        if (t == null || !t.isAlive()) releaseModels();
    }

    private void runLoop() {
        try {
            initSpotter();
            ready = true;
            Log.i(TAG, "Local emergency KWS ready: 小酷停止");

            while (running) {
                byte[] pcm = pcmQueue.poll(250L, TimeUnit.MILLISECONDS);
                if (pcm == null || pcm.length < 2) continue;

                int sampleCount = pcm.length / 2;
                float[] samples = new float[sampleCount];
                for (int i = 0, p = 0; i < sampleCount; i++, p += 2) {
                    short value = (short) ((pcm[p] & 0xFF) | (pcm[p + 1] << 8));
                    samples[i] = value / 32768.0f;
                }

                OnlineStream localStream = stream;
                KeywordSpotter localSpotter = spotter;
                if (localStream == null || localSpotter == null) break;
                localStream.acceptWaveform(samples, SAMPLE_RATE);

                while (running && localSpotter.isReady(localStream)) {
                    localSpotter.decode(localStream);
                    KeywordSpotterResult result = localSpotter.getResult(localStream);
                    String keyword = result == null ? "" : result.getKeyword();
                    if (keyword == null || keyword.trim().isEmpty()) continue;

                    localSpotter.reset(localStream);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastDetectedAtMs < DETECTION_COOLDOWN_MS) break;
                    lastDetectedAtMs = now;
                    pcmQueue.clear();
                    Log.i(TAG, "Local emergency command detected");
                    Listener callback = listener;
                    if (callback != null) callback.onEmergencyStop();
                    break;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (running) {
                Log.w(TAG, "Emergency KWS disabled for this Live session: "
                        + safeMessage(error));
            }
        } finally {
            ready = false;
            running = false;
            releaseModels();
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
        config.setKeywordsThreshold(STOP_THRESHOLD);
        config.setNumTrailingBlanks(1);

        spotter = new KeywordSpotter(context.getAssets(), config);
        stream = spotter.createStream(STOP_KEYWORD_TOKENS);
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

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }
}
