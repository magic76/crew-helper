package com.crewpocket.helper;

/**
 * Keeps microphone admission in playback-safe mode until queued assistant audio
 * is no longer physically audible. Server turnComplete may arrive before the
 * local AudioTrack/Oboe queue has drained.
 */
final class PlaybackTailMicPolicy {
    private PlaybackTailMicPolicy() {}

    static boolean shouldUsePlaybackGate(
            boolean aiSpeaking,
            long nowMs,
            long lastPlaybackActiveAtMs) {
        return aiSpeaking
                || (lastPlaybackActiveAtMs > 0L
                    && nowMs < lastPlaybackActiveAtMs);
    }

    static boolean isPlaybackTailOnly(
            boolean aiSpeaking,
            long nowMs,
            long lastPlaybackActiveAtMs) {
        return !aiSpeaking
                && lastPlaybackActiveAtMs > 0L
                && nowMs < lastPlaybackActiveAtMs;
    }
}
