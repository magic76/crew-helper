package com.crewpocket.helper;

import java.util.Locale;

/**
 * Client-side admission gate used only before Gemini Server VAD.
 *
 * It protects both startup calibration and sustained noisy listening without
 * replacing Server VAD. A short pre-roll is retained so confirming speech does
 * not clip the user's first word.
 */
final class ActiveNoiseAdmissionGate {
    enum Action { BYPASS, SUPPRESS, FLUSH_PREROLL, SEND }

    private static final int PRE_ROLL_FRAMES = 6; // ~240 ms
    private static final int STARTUP_REQUIRED_SPEECH_FRAMES = 2; // ~80 ms
    private static final int BASE_REQUIRED_SPEECH_FRAMES = 3; // ~120 ms
    private static final int VERY_NOISY_REQUIRED_SPEECH_FRAMES = 4; // ~160 ms
    // Keep admitted trailing audio longer than Gemini's configured 450 ms end-silence.
    private static final int RELEASE_NON_SPEECH_FRAMES = 16; // ~640 ms

    private final byte[][] preRoll;
    private final int[] preRollLengths;
    private int preRollStart;
    private int preRollCount;
    private int consecutiveSpeechFrames;
    private int consecutiveNonSpeechFrames;
    private boolean gateOpen;
    private double lastThreshold;
    private double lastRms;
    private double lastZcr;
    private double lastNoiseFloor;
    private boolean lastSpeechLike;
    private String lastReason = "INIT";
    private long suppressCount;
    private long admitCount;
    private long bypassCount;
    private long sendCount;

    ActiveNoiseAdmissionGate(int frameBytes) {
        int safeBytes = Math.max(2, frameBytes);
        preRoll = new byte[PRE_ROLL_FRAMES][safeBytes];
        preRollLengths = new int[PRE_ROLL_FRAMES];
    }

    Action accept(byte[] pcm,
                  int count,
                  double rms,
                  double zcr,
                  double noiseFloor,
                  String noiseMode,
                  int suppression,
                  boolean calibrated) {
        lastRms = Math.max(0d, rms);
        lastZcr = Math.max(0d, zcr);
        lastNoiseFloor = Math.max(0.008d, noiseFloor);

        if (!shouldGuard(noiseFloor, noiseMode, calibrated)) {
            resetGateState();
            clearBufferedFrames();
            lastThreshold = 0d;
            lastSpeechLike = false;
            lastReason = "QUIET_BYPASS";
            bypassCount++;
            return Action.BYPASS;
        }

        lastThreshold = speechThreshold(noiseFloor, noiseMode, suppression, calibrated);
        boolean speechLike = looksLikeSpeech(rms, zcr, noiseFloor, lastThreshold);
        lastSpeechLike = speechLike;

        if (!gateOpen) {
            buffer(pcm, count);
            if (speechLike) consecutiveSpeechFrames++;
            else consecutiveSpeechFrames = 0;

            int required = calibrated
                    ? requiredSpeechFrames(noiseFloor)
                    : STARTUP_REQUIRED_SPEECH_FRAMES;
            if (consecutiveSpeechFrames >= required) {
                gateOpen = true;
                consecutiveNonSpeechFrames = 0;
                consecutiveSpeechFrames = 0;
                admitCount++;
                lastReason = calibrated ? "PERSISTENT_SPEECH" : "STARTUP_SPEECH";
                return Action.FLUSH_PREROLL;
            }
            suppressCount++;
            lastReason = speechLike
                    ? (calibrated ? "SPEECH_CONFIRMING" : "STARTUP_CONFIRMING")
                    : (calibrated ? "NOISE_LIKE" : "STARTUP_NOISE");
            return Action.SUPPRESS;
        }

        if (speechLike) consecutiveNonSpeechFrames = 0;
        else consecutiveNonSpeechFrames++;

        if (consecutiveNonSpeechFrames > RELEASE_NON_SPEECH_FRAMES) {
            resetGateState();
            buffer(pcm, count);
            suppressCount++;
            lastReason = "TRAILING_RELEASE";
            return Action.SUPPRESS;
        }
        sendCount++;
        lastReason = speechLike ? "OPEN_SPEECH" : "OPEN_TRAILING";
        return Action.SEND;
    }

    double lastThreshold() { return lastThreshold; }
    double lastRms() { return lastRms; }
    double lastZcr() { return lastZcr; }
    double lastNoiseFloor() { return lastNoiseFloor; }
    boolean lastSpeechLike() { return lastSpeechLike; }
    long suppressCount() { return suppressCount; }
    long admitCount() { return admitCount; }
    long bypassCount() { return bypassCount; }
    long sendCount() { return sendCount; }
    String lastReason() { return lastReason; }

    String diagnosticSummary() {
        return String.format(Locale.US,
                "rms=%.4f floor=%.4f zcr=%.4f threshold=%.4f speechLike=%s reason=%s suppress=%d admit=%d bypass=%d send=%d",
                lastRms, lastNoiseFloor, lastZcr, lastThreshold,
                Boolean.toString(lastSpeechLike), lastReason,
                suppressCount, admitCount, bypassCount, sendCount);
    }

    int bufferedFrameCount() { return preRollCount; }

    byte[] bufferedFrame(int index) {
        if (index < 0 || index >= preRollCount) return null;
        return preRoll[(preRollStart + index) % PRE_ROLL_FRAMES];
    }

    int bufferedLength(int index) {
        if (index < 0 || index >= preRollCount) return 0;
        return preRollLengths[(preRollStart + index) % PRE_ROLL_FRAMES];
    }

    void clearBufferedFrames() {
        preRollStart = 0;
        preRollCount = 0;
    }

    void reset() {
        resetGateState();
        clearBufferedFrames();
        lastThreshold = 0d;
        lastSpeechLike = false;
        lastReason = "RESET";
    }

    private void resetGateState() {
        gateOpen = false;
        consecutiveSpeechFrames = 0;
        consecutiveNonSpeechFrames = 0;
    }

    private void buffer(byte[] pcm, int count) {
        if (pcm == null || count <= 0) return;
        int slot;
        if (preRollCount < PRE_ROLL_FRAMES) {
            slot = (preRollStart + preRollCount) % PRE_ROLL_FRAMES;
            preRollCount++;
        } else {
            slot = preRollStart;
            preRollStart = (preRollStart + 1) % PRE_ROLL_FRAMES;
        }
        int copy = Math.min(Math.min(count, pcm.length), preRoll[slot].length);
        System.arraycopy(pcm, 0, preRoll[slot], 0, copy);
        preRollLengths[slot] = copy;
    }

    private static boolean shouldGuard(double noiseFloor, String mode, boolean calibrated) {
        String safeMode = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if (!calibrated) return true;
        if ("noisy".equals(safeMode)) return true;
        double activationFloor = "quiet".equals(safeMode) ? 0.040 : 0.024;
        return Math.max(0.008, noiseFloor) >= activationFloor;
    }

    private static int requiredSpeechFrames(double noiseFloor) {
        return Math.max(0.008, noiseFloor) >= 0.060
                ? VERY_NOISY_REQUIRED_SPEECH_FRAMES
                : BASE_REQUIRED_SPEECH_FRAMES;
    }

    private static double speechThreshold(double noiseFloor,
                                          String mode,
                                          int suppression,
                                          boolean calibrated) {
        String safeMode = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        int strength = Math.max(0, Math.min(100, suppression));
        double floor = Math.max(0.008, noiseFloor);
        if (!calibrated) {
            double startupMin = "quiet".equals(safeMode) ? 0.014
                    : ("noisy".equals(safeMode) ? 0.028 : 0.020);
            double startupMultiplier = "quiet".equals(safeMode) ? 1.08
                    : ("noisy".equals(safeMode) ? 1.32 : 1.18);
            return Math.max(startupMin + strength * 0.00003,
                    floor * startupMultiplier);
        }
        double multiplier = "noisy".equals(safeMode) ? 1.16
                : ("quiet".equals(safeMode) ? 1.30 : 1.22);
        double minimum = "noisy".equals(safeMode) ? 0.030
                : ("quiet".equals(safeMode) ? 0.038 : 0.032);
        minimum += strength * 0.00004;
        return Math.max(minimum, floor * multiplier);
    }

    private static boolean looksLikeSpeech(double rms,
                                           double zcr,
                                           double noiseFloor,
                                           double threshold) {
        if (rms < threshold) return false;
        // Broadband hiss / sharp road texture should not start a turn.
        if (zcr > 0.36) return false;
        // Wind / engine rumble needs clearly near-field energy before admission.
        if (zcr < 0.008
                && rms < Math.max(threshold * 1.45,
                        Math.max(0.008, noiseFloor) * 1.55)) return false;
        return true;
    }
}
