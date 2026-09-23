package com.crewpocket.helper;

public final class MediaPlaybackCompletionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(MediaPlaybackCompletionPolicy.isDefaultTrustedPackage(
                        "com.apple.android.music"),
                "Apple Music is a default low-risk trusted app");
        check(!MediaPlaybackCompletionPolicy.isDefaultTrustedPackage(
                        "com.example.bank"),
                "unrelated app is not trusted");

        check(MediaPlaybackCompletionPolicy.isPlayControl("播放"),
                "Chinese play control recognized");
        check(MediaPlaybackCompletionPolicy.isPlayControl("Play"),
                "English play control recognized");
        check(!MediaPlaybackCompletionPolicy.isPlayControl("播放列表"),
                "playlist label is not playback control");

        check(MediaPlaybackCompletionPolicy.uiIndicatesPlaying(
                        "{label:'暫停'}"),
                "pause UI proves player entered playing state");
        check(MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        false),
                "active music after successful play completes task");
        check(MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        false,
                        true),
                "pause UI after successful play completes task");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        false,
                        true,
                        true),
                "failed tap never claims playback completion");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.example.music",
                        "播放",
                        true,
                        true,
                        true),
                "unknown app does not gain media completion authority");

        System.out.println(
                "PASS MediaPlaybackCompletionPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
