package com.crewpocket.helper;

public final class PlaybackTailMicPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(PlaybackTailMicPolicy.shouldUsePlaybackGate(
                        true, 1_000L, 0L),
                "model speaking always uses playback-safe mic gate");

        check(PlaybackTailMicPolicy.shouldUsePlaybackGate(
                        false, 1_000L, 1_500L),
                "queued audio tail keeps playback-safe gate after turnComplete");

        check(PlaybackTailMicPolicy.isPlaybackTailOnly(
                        false, 1_000L, 1_500L),
                "tail-only state is observable for diagnostics");

        check(!PlaybackTailMicPolicy.shouldUsePlaybackGate(
                        false, 1_500L, 1_500L),
                "normal mic gate resumes exactly when playback tail drains");

        check(!PlaybackTailMicPolicy.isPlaybackTailOnly(
                        true, 1_000L, 1_500L),
                "active model speech is not counted as tail-only protection");

        check(!PlaybackTailMicPolicy.shouldUsePlaybackGate(
                        false, 2_000L, 0L),
                "idle session uses normal admission path");

        System.out.println(
                "PASS PlaybackTailMicPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
