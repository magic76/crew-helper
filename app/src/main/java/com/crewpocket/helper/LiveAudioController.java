package com.crewpocket.helper;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Owns Gemini Live microphone capture, local noise/barge-in admission and audio playback.
 *
 * Conversation policy stays in NativeGeminiLiveClient. The host exposes only the
 * current turn flags/settings and transport callbacks needed by the audio engine.
 */
final class LiveAudioController {
    interface Host {
        boolean isRunning();
        boolean isAgentMuted();
        boolean isInterruptedCurrentTurn();
        boolean isAiSpeaking();
        boolean isVoiceInterruptionAllowed();
        String getNoiseMode();
        int getNoiseSuppression();
        int getInterruptionSensitivity();
        boolean canSendRealtime();
        boolean sendRealtime(String payload);
        void onStatus(String text);
        void onMicrophoneLevel(double dbfs, double gateDbfs, boolean sending);
        void onUpstreamPcm(
                byte[] pcm, int count, double rms,
                double noiseFloor, double gateThreshold);
        void onFailure(String message, Throwable error);
    }

    private static final String TAG = "CrewNativeLive";
    private static final int CALIBRATION_FRAMES = 20;
    private static final int BARGE_IN_MAX_CANDIDATE_FRAMES = 8;
    private static final double BARGE_IN_OUTPUT_MAX_ZCR = 0.26;
    private static final double BARGE_IN_VOICED_MAX_ZCR = 0.20;
    private static final int BARGE_IN_MIN_VOICED_FRAMES = 2;
    private static final double MIN_NOISE_FLOOR = 0.008;

    private final Host host;
    private final String audioOutput;
    private AudioRecord recorder;
    private AudioTrack player;
    private android.media.audiofx.AcousticEchoCanceler aecEffect;
    private android.media.audiofx.NoiseSuppressor nsEffect;
    private volatile boolean usingOboeOutput;
    private volatile String audioOutputBackend = "尚未初始化";
    private final BlockingQueue<byte[]> audioQueue =
            new LinkedBlockingQueue<byte[]>(96);
    private final Object playerLock = new Object();
    private volatile boolean audioPlaybackRunning;
    private Thread audioPlaybackThread;
    private volatile long lastPlaybackActiveAt;
    private volatile long lastMeterReportAt;
    private double noiseFloor = 0.015;
    private volatile long audioTrackWriteFailureCount;
    private volatile long audioTrackShortWriteCount;
    private volatile long oboeWriteFailureCount;
    private volatile long bargeInAdmissionCount;
    private volatile long lastBargeInAdmissionAt;
    private volatile String lastAudioOutputState = "NONE";
    private long audioPcmBytesReceived;
    private long audioPcmBytesAccepted;

    LiveAudioController(String audioOutput, Host host) {
        this.audioOutput = audioOutput == null ? "call" : audioOutput;
        if (host == null) throw new IllegalArgumentException("host required");
        this.host = host;
    }

    String getAudioOutputBackend() { return audioOutputBackend; }
    String getLastAudioOutputState() { return lastAudioOutputState; }
    long getPcmBytesReceived() { return audioPcmBytesReceived; }
    long getPcmBytesAccepted() { return audioPcmBytesAccepted; }
    long getLastPlaybackActiveAt() { return lastPlaybackActiveAt; }
    long getLastBargeInAdmissionAt() { return lastBargeInAdmissionAt; }

    void notePcmReceived(int bytes) {
        audioPcmBytesReceived += Math.max(0, bytes);
    }

    boolean enqueueAudio(byte[] pcm) {
        return enqueueAudioInternal(pcm);
    }

    void finishTurn() {
        if (usingOboeOutput) NativeOboeOutput.finishTurn();
    }

    void stopPlayback() {
        audioQueue.clear();
        if (usingOboeOutput) { NativeOboeOutput.flush(); return; }
        synchronized (playerLock) {
            try {
                if (player != null) {
                    player.pause();
                    player.flush();
                }
            } catch (Exception ignored) {}
        }
    }

    void start() {
    if (!host.isRunning() || recorder != null) return;
    int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    int bufferBytes = Math.max(min * 4, 8192);
    int selectedAudioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    try {
        recorder = new AudioRecord(selectedAudioSource, 16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("VOICE_COMMUNICATION_NOT_INITIALIZED");
        }
    } catch (Exception voiceError) {
        Log.w(TAG, "0120 AudioRecord VOICE_COMMUNICATION unavailable: "
                + voiceError.getClass().getSimpleName() + "; fallback=MIC");
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        selectedAudioSource = MediaRecorder.AudioSource.MIC;
        recorder = new AudioRecord(selectedAudioSource, 16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
    }

    if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
        host.onFailure("麥克風初始化失敗", null);
        return;
    }
    Log.i(TAG, "0120 AudioRecord source="
            + (selectedAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ? "VOICE_COMMUNICATION" : "MIC")
            + " state=" + recorder.getState()
            + " session=" + recorder.getAudioSessionId()
            + " bufferBytes=" + bufferBytes);

    boolean aecAvailable = android.media.audiofx.AcousticEchoCanceler.isAvailable();
    boolean aecCreated = false;
    int aecEnableStatus = Integer.MIN_VALUE;
    boolean aecEnabled = false;
    try {
        if (aecAvailable) {
            aecEffect = android.media.audiofx.AcousticEchoCanceler.create(
                    recorder.getAudioSessionId());
            aecCreated = aecEffect != null;
            if (aecEffect != null) {
                aecEnableStatus = aecEffect.setEnabled(true);
                aecEnabled = aecEffect.getEnabled();
            }
        }
    } catch (Exception effectError) {
        Log.w(TAG, "0120 AEC setup exception="
                + effectError.getClass().getSimpleName());
    }
    Log.i(TAG, "0120 AEC available=" + aecAvailable
            + " created=" + aecCreated
            + " enableStatus=" + aecEnableStatus
            + " enabled=" + aecEnabled);

    boolean nsAvailable = android.media.audiofx.NoiseSuppressor.isAvailable();
    boolean nsCreated = false;
    int nsEnableStatus = Integer.MIN_VALUE;
    boolean nsEnabled = false;
    try {
        if (nsAvailable) {
            nsEffect = android.media.audiofx.NoiseSuppressor.create(
                    recorder.getAudioSessionId());
            nsCreated = nsEffect != null;
            if (nsEffect != null) {
                nsEnableStatus = nsEffect.setEnabled(true);
                nsEnabled = nsEffect.getEnabled();
            }
        }
    } catch (Exception effectError) {
        Log.w(TAG, "0120 NoiseSuppressor setup exception="
                + effectError.getClass().getSimpleName());
    }
    Log.i(TAG, "0120 NoiseSuppressor available=" + nsAvailable
            + " created=" + nsCreated
            + " enableStatus=" + nsEnableStatus
            + " enabled=" + nsEnabled);

    createAudioPlayer();
    startPlaybackWorker();
    try {
        recorder.startRecording();
        Log.i(TAG, "0120 AudioRecord recordingState="
                + recorder.getRecordingState());
    } catch (Exception startError) {
        host.onFailure("麥克風啟動失敗：" + startError.getClass().getSimpleName(), startError);
        return;
    }
    new Thread(new Runnable() { @Override public void run() { sendMic(); } }, "crew-native-live-mic").start();
}

    private void createAudioPlayer() {
        usingOboeOutput = NativeOboeOutput.start(audioOutput);
        if (usingOboeOutput) {
            String info = NativeOboeOutput.getInfo();
            audioOutputBackend = info == null ? "Oboe／AAudio 低延遲" : info;
            Log.i(TAG, "Oboe low-latency output enabled");
            return;
        }
        audioOutputBackend = "Android AudioTrack 備援";
        synchronized (playerLock) {
            try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
            int outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // One second leaves room for GC, image upload, and transient Wi-Fi jitter.
            int bufferBytes = Math.max(outMin * 8, 48000);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage("media".equals(audioOutput) ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFormat format = new AudioFormat.Builder().setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            player = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM).build();
        }
    }

    private void startPlaybackWorker() {
        audioQueue.clear();
        if (usingOboeOutput) { audioPlaybackRunning = true; return; }
        audioPlaybackRunning = true;
        audioPlaybackThread = new Thread(new Runnable() {
            @Override public void run() { runPlaybackLoop(); }
        }, "crew-native-live-playback");
        audioPlaybackThread.start();
    }

    private void runPlaybackLoop() {
        boolean started = false;
        while (audioPlaybackRunning) {
            try {
                byte[] first = audioQueue.poll(300, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                if (!started) {
                    // Start with about 200 ms buffered. It avoids the initial
                    // AudioTrack underrun that previously disabled the track.
                    ArrayList<byte[]> initial = new ArrayList<byte[]>();
                    initial.add(first);
                    int bytes = first.length;
                    long deadline = System.currentTimeMillis() + 180;
                    while (bytes < 9600 && System.currentTimeMillis() < deadline) {
                        byte[] next = audioQueue.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                        if (next == null) break;
                        initial.add(next); bytes += next.length;
                    }
                    synchronized (playerLock) { if (player != null) player.play(); }
                    started = true;
                    for (byte[] chunk : initial) writeAudioChunk(chunk);
                } else {
                    writeAudioChunk(first);
                }
            } catch (InterruptedException ignored) {
                // stopAudio interrupts this worker; the loop condition decides exit.
            } catch (Exception error) {
                Log.w(TAG, "音訊播放工作執行失敗：" + error.getMessage());
                recoverAudioPlayer();
                started = false;
            }
        }
    }

    private void writeAudioChunk(byte[] pcm) {
    if (pcm == null || pcm.length == 0 || host.isInterruptedCurrentTurn() || host.isAgentMuted()) return;
    int totalWritten = 0;
    int writeError = 0;
    synchronized (playerLock) {
        if (player != null && player.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                player.play();
            } catch (Exception playError) {
                audioTrackWriteFailureCount++;
                lastAudioOutputState = "AUDIOTRACK_PLAY_FAILED";
                Log.w(TAG, "0120 AudioTrack play failed #"
                        + audioTrackWriteFailureCount + " exception="
                        + playError.getClass().getSimpleName());
            }
        }
        if (player == null) {
            writeError = AudioTrack.ERROR_INVALID_OPERATION;
        } else {
            while (totalWritten < pcm.length) {
                int remaining = pcm.length - totalWritten;
                int written = player.write(pcm, totalWritten, remaining);
                if (written < 0) {
                    writeError = written;
                    break;
                }
                if (written == 0) break;
                if (written < remaining) audioTrackShortWriteCount++;
                totalWritten += written;
            }
        }
    }
    if (writeError < 0) {
        audioTrackWriteFailureCount++;
        lastAudioOutputState = "AUDIOTRACK_WRITE_FAILED:" + writeError;
        Log.w(TAG, "0120 AudioTrack write failed #"
                + audioTrackWriteFailureCount + " code=" + writeError
                + " shortWrites=" + audioTrackShortWriteCount
                + "; rebuilding track");
        recoverAudioPlayer();
    } else if (totalWritten < pcm.length) {
        audioTrackShortWriteCount++;
        lastAudioOutputState = "AUDIOTRACK_SHORT_WRITE:"
                + totalWritten + "/" + pcm.length;
        Log.w(TAG, "0120 AudioTrack short write #"
                + audioTrackShortWriteCount + " bytes="
                + totalWritten + "/" + pcm.length);
    } else if (totalWritten > 0) {
        lastAudioOutputState = "AUDIOTRACK_PLAYING";
    }
}

    private void recoverAudioPlayer() {
        if (!audioPlaybackRunning || !host.isRunning()) return;
        createAudioPlayer();
    }

    private double calculateRms(byte[] pcm, int count) {
        if (count < 2) return 0;
        long sum = 0;
        int samples = count / 2;
        for (int i = 0; i < count - 1; i += 2) {
            short val = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) val * val;
        }
        return Math.sqrt((double) sum / samples) / 32768.0;
    }

    private void sendMic() {
        byte[] pcm = new byte[1280]; // 40ms @ 16kHz 16-bit mono
        int calibrationFrames = 0;
        double[] calibrationSamples = new double[CALIBRATION_FRAMES];

        // Fixed buffers avoid re-introducing the 25 Hz allocation churn removed
        // by 0099. Only candidate speech during AI playback is copied here.
        byte[][] bargeInCandidatePcm =
                new byte[BARGE_IN_MAX_CANDIDATE_FRAMES][pcm.length];
        int[] bargeInCandidateLengths = new int[BARGE_IN_MAX_CANDIDATE_FRAMES];
        int bargeInCandidateCount = 0;
        int consecutiveBargeInFrames = 0;
        int bargeInVoicedFrames = 0;
        double bargeInCandidateMinRms = Double.MAX_VALUE;
        double bargeInCandidatePeakRms = 0.0;
        double bargeInPreviousRms = 0.0;
        boolean bargeInEnergyRising = false;
        boolean bargeInGateOpen = false;

        // 0108: normal-listening noise guard is stateful per Live mic thread.
        // It bypasses quiet environments and never owns AudioRecord.
        ActiveNoiseAdmissionGate activeNoiseGate =
                new ActiveNoiseAdmissionGate(pcm.length);
        long lastNoiseGateDiagnosticAt = 0L;

        while (host.isRunning() && recorder != null && host.canSendRealtime()) {
            int count = recorder.read(pcm, 0, pcm.length); if (count <= 0) continue;
            if (host.isAgentMuted()) continue;

            double rms = calculateRms(pcm, count);
            String mode = host.getNoiseMode();
            int suppression = host.getNoiseSuppression();

            // Keep the first 0.8 s as an acoustic baseline. The local
            // ActiveNoiseAdmissionGate now protects these uncalibrated frames too;
            // its 240 ms pre-roll preserves the first word once speech is confirmed.
            if (calibrationFrames < CALIBRATION_FRAMES) {
                calibrationSamples[calibrationFrames] = rms;
                calibrationFrames++;
                if (calibrationFrames == CALIBRATION_FRAMES) {
                    Arrays.sort(calibrationSamples);
                    double baseline = 0;
                    for (int i = 0; i < 12; i++) baseline += calibrationSamples[i];
                    noiseFloor = Math.max(0.008, baseline / 12.0);
                    host.onStatus("環境音基線已校正（" + mode + "）");
                }
            }

            double modeBase = "noisy".equals(mode) ? 1.45 : ("quiet".equals(mode) ? 0.65 : 0.90);
            double gateMultiplier = modeBase + suppression * 0.008;
            double minBase = "noisy".equals(mode) ? 0.022 : ("quiet".equals(mode) ? 0.002 : 0.006);
            double minGate = minBase + suppression * 0.00010;
            double gateThreshold = Math.max(minGate, noiseFloor * gateMultiplier);

            // 0108: normal listening stays zero-added-latency in quiet environments.
            // Once the calibrated ambient floor is clearly noisy, require a short
            // run of speech-like frames before exposing audio to Gemini Server VAD.
            // A fixed pre-roll preserves the first word, and trailing audio remains
            // long enough for the server's 450 ms end-of-speech detector to close.
            if (!host.isAiSpeaking()) {
                bargeInGateOpen = false;
                consecutiveBargeInFrames = 0;
                bargeInCandidateCount = 0;
                bargeInVoicedFrames = 0;
                bargeInCandidateMinRms = Double.MAX_VALUE;
                bargeInCandidatePeakRms = 0.0;
                bargeInPreviousRms = 0.0;
                bargeInEnergyRising = false;
                if (rms < gateThreshold) {
                    // Keep the calibrated lower bound. Without this clamp the
                    // floor slowly decays toward zero during quiet idle periods,
                    // making playback echo look like a very strong interruption.
                    noiseFloor = Math.max(MIN_NOISE_FLOOR,
                            noiseFloor * 0.985 + Math.min(rms, 0.18) * 0.015);
                }

                double zcr = calculateZeroCrossingRate(pcm, count);
                ActiveNoiseAdmissionGate.Action noiseAction = activeNoiseGate.accept(
                        pcm, count, rms, zcr, noiseFloor, mode, suppression,
                        calibrationFrames >= CALIBRATION_FRAMES);
                double activeGateThreshold = Math.max(
                        gateThreshold, activeNoiseGate.lastThreshold());
                long gateNow = System.currentTimeMillis();
                if (gateNow - lastNoiseGateDiagnosticAt >= 1000L) {
                    lastNoiseGateDiagnosticAt = gateNow;
                    Log.d(TAG, "0120 normal gate calibrated="
                            + (calibrationFrames >= CALIBRATION_FRAMES)
                            + " mode=" + mode + " "
                            + activeNoiseGate.diagnosticSummary());
                }

                if (noiseAction == ActiveNoiseAdmissionGate.Action.SUPPRESS) {
                    PerformanceMetrics.recordActiveNoiseFrameSuppressed();
                    reportMicrophoneLevel(rms, activeGateThreshold, false);
                    continue;
                }

                if (noiseAction == ActiveNoiseAdmissionGate.Action.FLUSH_PREROLL) {
                    PerformanceMetrics.recordActiveNoiseSpeechAdmission();
                    int bufferedFrames = activeNoiseGate.bufferedFrameCount();
                    for (int i = 0; i < bufferedFrames; i++) {
                        byte[] buffered = activeNoiseGate.bufferedFrame(i);
                        int bufferedCount = activeNoiseGate.bufferedLength(i);
                        if (buffered == null || bufferedCount <= 0) continue;
                        double bufferedRms = calculateRms(buffered, bufferedCount);
                        if (!sendMicChunk(buffered, bufferedCount,
                                bufferedRms, activeGateThreshold)) return;
                    }
                    activeNoiseGate.clearBufferedFrames();
                    reportMicrophoneLevel(rms, activeGateThreshold, true);
                    // Current frame is already part of the pre-roll flush.
                    continue;
                }

                reportMicrophoneLevel(rms,
                        noiseAction == ActiveNoiseAdmissionGate.Action.BYPASS
                                ? gateThreshold : activeGateThreshold,
                        true);
                if (!sendMicChunk(pcm, count, rms,
                        noiseAction == ActiveNoiseAdmissionGate.Action.BYPASS
                                ? gateThreshold : activeGateThreshold)) break;
                continue;
            }

            // 0101 remains the sole local admission policy while AI output is audible.
            activeNoiseGate.reset();

            if (!host.isVoiceInterruptionAllowed()) {
                // Existing explicit protection mode: AI speech owns the channel.
                bargeInGateOpen = false;
                consecutiveBargeInFrames = 0;
                bargeInCandidateCount = 0;
                bargeInVoicedFrames = 0;
                bargeInCandidateMinRms = Double.MAX_VALUE;
                bargeInCandidatePeakRms = 0.0;
                bargeInPreviousRms = 0.0;
                bargeInEnergyRising = false;
                reportMicrophoneLevel(rms, gateThreshold, false);
                continue;
            }

            if (!bargeInGateOpen) {
                // AEC/NS remain the first line of defense. This gate is deliberately
                // active only during assistant playback to catch residual self-echo.
                boolean outputAudible = System.currentTimeMillis() < lastPlaybackActiveAt;
                double sensitivity = host.getInterruptionSensitivity() / 100.0;
                // 0119 noisy barge-in speech discriminator: auto mode becomes
                // deliberately more conservative once the calibrated ambient floor
                // is clearly outdoors/noisy. This affects assistant-playback barge-in
                // only; normal listening keeps the existing ActiveNoiseAdmissionGate.
                boolean adaptiveNoisy = "noisy".equals(mode)
                        || ("auto".equals(mode) && noiseFloor >= 0.035);
                int requiredFrames = 4
                        + (int) Math.round((1.0 - sensitivity) * 3.0)
                        + (outputAudible ? 2 : 0)
                        + (adaptiveNoisy ? 1 : 0);
                requiredFrames = Math.max(4,
                        Math.min(BARGE_IN_MAX_CANDIDATE_FRAMES, requiredFrames));

                double baseInterrupt = (adaptiveNoisy ? 0.068 : 0.050)
                        + suppression * 0.00016
                        + (outputAudible ? 0.018 : 0.0)
                        + (1.0 - sensitivity) * 0.018
                        - sensitivity * 0.006;
                double floorMultiplier = (adaptiveNoisy ? 2.35 : 1.90)
                        + suppression * 0.006
                        + (1.0 - sensitivity) * 0.40;
                double interruptThreshold = Math.max(
                        baseInterrupt, noiseFloor * floorMultiplier);

                double bargeInZcr = calculateZeroCrossingRate(pcm, count);
                double bargeInZcrLimit = outputAudible
                        ? BARGE_IN_OUTPUT_MAX_ZCR : 0.36;
                boolean speechLikeBargeIn = rms >= interruptThreshold
                        // Strong high-frequency hiss / sharp street texture is
                        // unlikely to be a nearby human interruption.
                        && bargeInZcr <= bargeInZcrLimit
                        // Very low-frequency wind/engine rumble must look clearly
                        // near-field before it can interrupt audible assistant speech.
                        && !(bargeInZcr < 0.010
                            && rms < interruptThreshold * 1.50);

                if (speechLikeBargeIn) {
                    boolean voicedFrame = bargeInZcr >= 0.015
                            && bargeInZcr <= BARGE_IN_VOICED_MAX_ZCR;
                    if (bargeInCandidateCount == 0) {
                        bargeInCandidateMinRms = rms;
                        bargeInCandidatePeakRms = rms;
                        bargeInPreviousRms = rms;
                    } else {
                        bargeInCandidateMinRms = Math.min(
                                bargeInCandidateMinRms, rms);
                        bargeInCandidatePeakRms = Math.max(
                                bargeInCandidatePeakRms, rms);
                        if (rms >= bargeInPreviousRms * 1.12) {
                            bargeInEnergyRising = true;
                        }
                        bargeInPreviousRms = rms;
                    }
                    if (voicedFrame) bargeInVoicedFrames++;
                    consecutiveBargeInFrames++;
                    if (bargeInCandidateCount < BARGE_IN_MAX_CANDIDATE_FRAMES) {
                        System.arraycopy(pcm, 0,
                                bargeInCandidatePcm[bargeInCandidateCount], 0, count);
                        bargeInCandidateLengths[bargeInCandidateCount] = count;
                        bargeInCandidateCount++;
                    }

                    boolean candidateEnergyChanged = bargeInCandidatePeakRms
                            >= bargeInCandidateMinRms
                            + Math.max(0.012, interruptThreshold * 0.12);
                    boolean playbackSpeechConfidence = !outputAudible
                            || (bargeInVoicedFrames >= Math.max(
                                    BARGE_IN_MIN_VOICED_FRAMES, requiredFrames / 4)
                                && (bargeInEnergyRising
                                    || candidateEnergyChanged
                                    || bargeInCandidatePeakRms
                                        >= interruptThreshold * 1.30));

                    if (consecutiveBargeInFrames >= requiredFrames
                            && !playbackSpeechConfidence
                            && consecutiveBargeInFrames == requiredFrames) {
                        Log.d(TAG, "0121 barge-in REJECT reason=LOW_CONFIDENCE"
                                + " frames=" + consecutiveBargeInFrames
                                + " required=" + requiredFrames
                                + " voiced=" + bargeInVoicedFrames
                                + " minRms=" + bargeInCandidateMinRms
                                + " peakRms=" + bargeInCandidatePeakRms
                                + " zcr=" + bargeInZcr
                                + " zcrLimit=" + bargeInZcrLimit
                                + " outputAudible=" + outputAudible);
                    }

                    if (consecutiveBargeInFrames >= requiredFrames
                            && playbackSpeechConfidence) {
                        bargeInGateOpen = true;
                        long admissionIndex = ++bargeInAdmissionCount;
                        lastBargeInAdmissionAt = System.currentTimeMillis();
                        Log.i(TAG, "0120 barge-in ADMIT #" + admissionIndex
                                + " reason=PERSISTENT_SPEECH frames=" + consecutiveBargeInFrames
                                + " required=" + requiredFrames
                                + " rms=" + rms
                                + " floor=" + noiseFloor
                                + " zcr=" + bargeInZcr
                                + " zcrLimit=" + bargeInZcrLimit
                                + " voiced=" + bargeInVoicedFrames
                                + " threshold=" + interruptThreshold
                                + " outputAudible=" + outputAudible
                                + " adaptiveNoisy=" + adaptiveNoisy);

                        // Flush only the candidate speech frames. We intentionally do
                        // not prepend arbitrary pre-candidate audio because that is
                        // exactly where residual speaker echo tends to live.
                        for (int i = 0; i < bargeInCandidateCount; i++) {
                            int bufferedCount = bargeInCandidateLengths[i];
                            byte[] buffered = bargeInCandidatePcm[i];
                            double bufferedRms = calculateRms(buffered, bufferedCount);
                            if (!sendMicChunk(buffered, bufferedCount,
                                    bufferedRms, interruptThreshold)) return;
                        }
                        bargeInCandidateCount = 0;
                        consecutiveBargeInFrames = 0;
                        bargeInVoicedFrames = 0;
                        bargeInCandidateMinRms = Double.MAX_VALUE;
                        bargeInCandidatePeakRms = 0.0;
                        bargeInPreviousRms = 0.0;
                        bargeInEnergyRising = false;
                        reportMicrophoneLevel(rms, interruptThreshold, true);
                        // Current frame was already included in the candidate flush.
                        continue;
                    }
                } else {
                    consecutiveBargeInFrames = 0;
                    bargeInCandidateCount = 0;
                    bargeInVoicedFrames = 0;
                    bargeInCandidateMinRms = Double.MAX_VALUE;
                    bargeInCandidatePeakRms = 0.0;
                    bargeInPreviousRms = 0.0;
                    bargeInEnergyRising = false;
                }

                reportMicrophoneLevel(rms, interruptThreshold, false);
                continue;
            }

            // The local gate has admitted likely human speech. From this point on
            // full PCM flows continuously and Gemini Server VAD remains responsible
            // for emitting serverContent.interrupted and ending the old model turn.
            reportMicrophoneLevel(rms, gateThreshold, true);
            if (!sendMicChunk(pcm, count, rms, gateThreshold)) break;
        }
    }

    private boolean sendMicChunk(byte[] pcm, int count, double rms, double gateThreshold) {
        try {
            if (!host.canSendRealtime()) return false;
            String encoded = Base64.encodeToString(
                    pcm, 0, count, Base64.NO_WRAP);
            String payload = "{\"realtimeInput\":{\"audio\":{"
                    + "\"mimeType\":\"audio/pcm;rate=16000\","
                    + "\"data\":\"" + encoded + "\"}}}";
            if (!host.sendRealtime(payload)) throw new Exception("audio send failed");
            host.onUpstreamPcm(pcm, count, rms, noiseFloor, gateThreshold);
            return true;
        } catch (Exception error) {
            host.onFailure("麥克風串流失敗：" + error.getMessage(), error);
            return false;
        }
    }

    private double calculateZeroCrossingRate(byte[] pcm, int count) {
        if (count < 4) return 0;
        int crossings = 0;
        short previous = (short) ((pcm[0] & 0xFF) | (pcm[1] << 8));
        for (int i = 2; i < count - 1; i += 2) {
            short current = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) crossings++;
            previous = current;
        }
        return (double) crossings / Math.max(1, count / 2);
    }

    private void reportMicrophoneLevel(double rms, double gate, boolean sending) {
        long now = System.currentTimeMillis();
        if (now - lastMeterReportAt < 180) return;
        lastMeterReportAt = now;
        double dbfs = rms <= 0.000001 ? -96.0 : Math.max(-96.0, 20.0 * Math.log10(rms));
        double gateDbfs = gate <= 0.000001 ? -96.0 : Math.max(-96.0, 20.0 * Math.log10(gate));
        host.onMicrophoneLevel(dbfs, gateDbfs, sending);
    }

    private boolean enqueueAudioInternal(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            lastAudioOutputState = "EMPTY_PCM";
            return false;
        }
        if (host.isAgentMuted()) {
            lastAudioOutputState = "BLOCKED_MUTED";
            Log.w(TAG, "0047 audio blocked because host.isAgentMuted()=true");
            return false;
        }
        if (host.isInterruptedCurrentTurn()) {
            lastAudioOutputState = "BLOCKED_INTERRUPTED";
            Log.w(TAG, "0047 audio blocked because host.isInterruptedCurrentTurn()=true");
            return false;
        }
        long durationMs = pcm.length * 1000L / (24000 * 2);
        lastPlaybackActiveAt = Math.max(System.currentTimeMillis(), lastPlaybackActiveAt) + durationMs;
        if (usingOboeOutput) {
            try {
                NativeOboeOutput.write(pcm);
                audioPcmBytesAccepted += pcm.length;
                lastAudioOutputState = "OBOE_ACCEPTED";
                return true;
            } catch (Throwable writeError) {
                oboeWriteFailureCount++;
                lastAudioOutputState = "OBOE_WRITE_FAILED";
                Log.w(TAG, "0120 Oboe write failed #" + oboeWriteFailureCount
                        + " exception=" + writeError.getClass().getSimpleName());
                return false;
            }
        }
        boolean accepted = audioQueue.offer(pcm);
        if (!accepted) {
            audioQueue.poll();
            accepted = audioQueue.offer(pcm);
        }
        if (accepted) {
            audioPcmBytesAccepted += pcm.length;
            lastAudioOutputState = "AUDIOTRACK_QUEUED";
        } else {
            lastAudioOutputState = "AUDIOTRACK_QUEUE_FULL";
            Log.w(TAG, "音訊佇列已滿，略過過期語音片段");
        }
        return accepted;
    }

    void stop() {
        audioPlaybackRunning = false;
        audioQueue.clear();
        if (usingOboeOutput) { NativeOboeOutput.stop(); usingOboeOutput = false; }
        try { if (audioPlaybackThread != null) audioPlaybackThread.interrupt(); } catch (Exception ignored) {}
        audioPlaybackThread = null;
        if (aecEffect != null) { try { aecEffect.release(); } catch (Exception ignored) {} aecEffect = null; }
        if (nsEffect != null) { try { nsEffect.release(); } catch (Exception ignored) {} nsEffect = null; }
        try { if (recorder != null) { recorder.stop(); recorder.release(); recorder = null; } } catch (Exception ignored) {}
        synchronized (playerLock) {
            try { if (player != null) { player.stop(); player.release(); player = null; } } catch (Exception ignored) {}
        }
    }

}
