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
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.apple.android.music",
                        "播放",
                        true,
                        true,
                        false,
                        false,
                        "MEDIA:PLAY",
                        true),
                "screen effect alone cannot prove actual playback");
        check(!MediaPlaybackCompletionPolicy.shouldComplete(
                        "com.example.music",
                        "播放",
                        true,
                        false,
                        false,
                        false,
                        "MEDIA:PLAY",
                        true),
                "unknown media app cannot complete play from a screen effect alone");
        check(!MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PLAY",
                        "播放",
                        true,
                        true),
                "RuntimeV2 tap effect alone cannot complete a play goal");
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

        check(MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PAUSE",
                        "暫停",
                        true,
                        true),
                "verified pause control effect completes pause goal");
        check(MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:NEXT",
                        "下一個音軌",
                        true,
                        true),
                "verified next-track effect completes next goal");
        check(MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PREVIOUS",
                        "上一個音軌",
                        true,
                        true),
                "verified previous-track effect completes previous goal");
        check(!MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:PREVIOUS",
                        "下一個音軌",
                        true,
                        true),
                "mismatched transport control cannot complete the goal");
        check(!MediaPlaybackCompletionPolicy.shouldCompleteFromVerifiedEffect(
                        "MEDIA:NEXT",
                        "下一個音軌",
                        true,
                        false),
                "transport goal still requires an observable verified effect");
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
