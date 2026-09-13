package com.crewpocket.helper;

/**
 * 0108 adaptive microphone admission guard for noisy ACTIVE Gemini Live sessions.
 *
 * It never owns AudioRecord and never decides semantic turns. NativeGeminiLiveClient
 * feeds the existing AEC/NS PCM through this lightweight state machine only when the
 * calibrated ambient floor is high. Quiet environments bypass it completely.
 *
 * The guard buffers a short pre-roll so requiring consecutive speech-like frames does
 * not clip the first word. Once admitted it keeps forwarding enough trailing audio for
 * Gemini Server VAD to observe natural end-of-speech silence before closing again.
 */
final class ActiveNoiseAdmissionGate {
    enum Action {
        /** Environment is quiet enough: preserve the existing zero-added-latency path. */
        BYPASS,
        /** No convincing nearby speech yet: keep this frame local. */
        SUPPRESS,
        /** Speech was confirmed: flush buffered pre-roll in chronological order. */
        FLUSH_PREROLL,
        /** A speech burst is already open: forward the current frame normally. */
        SEND
    }

    private static final int PRE_ROLL_FRAMES = 6; // ~240 ms at 40 ms/frame
    private static final int BASE_REQUIRED_SPEECH_FRAMES = 3; // ~120 ms
    private static final int VERY_NOISY_REQUIRED_SPEECH_FRAMES = 4; // ~160 ms
    private static final int RELEASE_NON_SPEECH_FRAMES = 16; // ~640 ms, > server VAD 450 ms

    private final byte[][] preRoll;
    private final int[] preRollLengths;
    private int writeIndex;
    private int bufferedCount;
    private int consecutiveSpeechFrames;
    private int trailingNonSpeechFrames;
    private boolean gateOpen;
    private double lastThreshold;

    ActiveNoiseAdmissionGate(int maxFrameBytes) {
        int safeBytes = Math.max(2, maxFrameBytes);
        preRoll = new byte[PRE_ROLL_FRAMES][safeBytes];
        preRollLengths = new int[PRE_ROLL_FRAMES];
    }

    Action accept(byte[] pcm,
                  int count,
                  double rms,
                  double zeroCrossingRate,
                  double noiseFloor,
                  String noiseMode,
                  int suppression,
                  boolean calibrated) {
        if (!shouldGuard(noiseFloor, noiseMode, calibrated)) {
            reset();
            return Action.BYPASS;
        }

        lastThreshold = speechThreshold(noiseFloor, noiseMode, suppression);
        boolean speechLike = looksLikeSpeech(
                rms, zeroCrossingRate, noiseFloor, lastThreshold);

        if (!gateOpen) {
            buffer(pcm, count);
            if (speechLike) consecutiveSpeechFrames++;
            else consecutiveSpeechFrames = 0;

            int required = noiseFloor >= 0.060
                    ? VERY_NOISY_REQUIRED_SPEECH_FRAMES
                    : BASE_REQUIRED_SPEECH_FRAMES;
            if (consecutiveSpeechFrames >= required) {
                gateOpen = true;
                consecutiveSpeechFrames = 0;
                trailingNonSpeechFrames = 0;
                return Action.FLUSH_PREROLL;
            }
            return Action.SUPPRESS;
        }

        if (speechLike) {
            trailingNonSpeechFrames = 0;
        } else {
            trailingNonSpeechFrames++;
            // Send this final trailing frame too, then close before the next one.
            if (trailingNonSpeechFrames >= RELEASE_NON_SPEECH_FRAMES) {
                gateOpen = false;
                trailingNonSpeechFrames = 0;
                consecutiveSpeechFrames = 0;
                clearBufferedFrames();
            }
        }
        return Action.SEND;
    }

    void reset() {
        gateOpen = false;
        consecutiveSpeechFrames = 0;
        trailingNonSpeechFrames = 0;
        lastThreshold = 0.0;
        clearBufferedFrames();
    }

    double lastThreshold() {
        return lastThreshold;
    }

    int bufferedFrameCount() {
        return bufferedCount;
    }

    byte[] bufferedFrame(int chronologicalIndex) {
        int slot = chronologicalSlot(chronologicalIndex);
        return slot < 0 ? null : preRoll[slot];
    }

    int bufferedLength(int chronologicalIndex) {
        int slot = chronologicalSlot(chronologicalIndex);
        return slot < 0 ? 0 : preRollLengths[slot];
    }

    void clearBufferedFrames() {
        for (int i = 0; i < preRollLengths.length; i++) preRollLengths[i] = 0;
        writeIndex = 0;
        bufferedCount = 0;
    }

    private void buffer(byte[] pcm, int count) {
        if (pcm == null || count <= 0) return;
        int safeCount = Math.min(Math.min(count, pcm.length), preRoll[writeIndex].length);
        System.arraycopy(pcm, 0, preRoll[writeIndex], 0, safeCount);
        preRollLengths[writeIndex] = safeCount;
        writeIndex = (writeIndex + 1) % PRE_ROLL_FRAMES;
        if (bufferedCount < PRE_ROLL_FRAMES) bufferedCount++;
    }

    private int chronologicalSlot(int chronologicalIndex) {
        if (chronologicalIndex < 0 || chronologicalIndex >= bufferedCount) return -1;
        int oldest = (writeIndex - bufferedCount + PRE_ROLL_FRAMES) % PRE_ROLL_FRAMES;
        return (oldest + chronologicalIndex) % PRE_ROLL_FRAMES;
    }

    private static boolean shouldGuard(double noiseFloor, String mode, boolean calibrated) {
        if (!calibrated) return false;
        String safeMode = mode == null ? "auto" : mode;
        if ("noisy".equals(safeMode)) return true;
        double activationFloor = "quiet".equals(safeMode) ? 0.040 : 0.024;
        return noiseFloor >= activationFloor;
    }

    private static double speechThreshold(double noiseFloor, String mode, int suppression) {
        String safeMode = mode == null ? "auto" : mode;
        int safeSuppression = Math.max(0, Math.min(100, suppression));
        double multiplier = "noisy".equals(safeMode)
                ? 1.16 : ("quiet".equals(safeMode) ? 1.30 : 1.22);
        double minimum = "noisy".equals(safeMode)
                ? 0.030 : ("quiet".equals(safeMode) ? 0.038 : 0.032);
        minimum += safeSuppression * 0.00004;
        return Math.max(minimum, Math.max(0.008, noiseFloor) * multiplier);
    }

    private static boolean looksLikeSpeech(double rms,
                                           double zcr,
                                           double noiseFloor,
                                           double threshold) {
        if (rms < threshold) return false;
        // Reject strong high-frequency hiss/impulsive texture.
        if (zcr > 0.42) return false;
        // Reject low-frequency wind/engine rumble unless it rises far above ambient.
        if (zcr < 0.006 && rms < Math.max(threshold, noiseFloor * 1.50)) return false;
        return true;
    }
}
