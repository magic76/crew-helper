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
        check(MediaPlaybackCompletionPolicy.isPlayControl("Resume"),
                "generic resume control recognized");
        check(MediaPlaybackCompletionPolicy.isPlayControl("繼續播放"),
                "Chinese resume control recognized");
        check(!MediaPlaybackCompletionPolicy.isPlayControl("播放列表"),
                "playlist label is not playback control");

        check(MediaPlaybackCompletionPolicy.uiIndicatesPlaying(
                        "{label:'暫停'}"),
                "pause UI proves player entered playing state");
        check(MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        false,
                        true,
                        false),
                "inactive to active transition completes task");
        check(MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        true,
                        true),
                "pause UI completes even when audio was already active");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        true,
                        false),
                "pre-existing audio alone does not prove this tap worked");
        check(MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        false,
                        false,
                        "MEDIA:PLAY",
                        true),
                "explicit play goal plus verified screen effect completes without waiting for AudioManager");
        check(MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.example.music",
                        "播放",
                        true,
                        false,
                        false,
                        false,
                        "MEDIA:PLAY",
                        true),
                "generic explicit media play goal may complete from verified low-risk play-control effect");
        check(MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PLAY",
                        "播放",
                        true,
                        true),
                "RuntimeV2 verified observable play effect completes media goal");
        check(!MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PLAY",
                        "播放",
                        true,
                        false),
                "verified play without observable effect does not complete");
        check(!MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PLAY",
                        "更多",
                        true,
                        true),
                "non-play control never completes media goal");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        true,
                        false,
                        "MEDIA:PLAY",
                        false),
                "explicit play goal still needs observable action effect");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        true,
                        false,
                        "SEARCH:RESULT",
                        true),
                "unrelated terminal goal cannot use the relaxed play completion path");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        false,
                        false,
                        true,
                        true),
                "failed tap never claims playback completion");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.example.music",
                        "播放",
                        true,
                        false,
                        true,
                        true),
                "unknown app without explicit MEDIA:PLAY goal does not gain completion authority");

        System.out.println(
                "PASS MediaPlaybackCompletionPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
