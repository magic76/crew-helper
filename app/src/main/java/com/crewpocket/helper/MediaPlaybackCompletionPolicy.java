package com.crewpocket.helper;

import java.util.Locale;

/** Pure policy for low-risk media playback autonomy and completion evidence. */
final class MediaPlaybackCompletionPolicy {
    static final String APPLE_MUSIC_PACKAGE = "com.apple.android.music";

    private MediaPlaybackCompletionPolicy() {}

    static boolean isDefaultTrustedPackage(String packageName) {
        return APPLE_MUSIC_PACKAGE.equals(clean(packageName));
    }

    static boolean isPlayControl(String metadata) {
        String value = fold(metadata);
        if (value.isEmpty()) return false;
        return value.equals("播放")
                || value.equals("播放歌曲")
                || value.equals("開始播放")
                || value.equals("开始播放")
                || value.equals("play")
                || value.equals("playbutton")
                || value.equals("startplayback")
                || value.contains("media:play")
                || value.contains("play_control");
    }

    static boolean uiIndicatesPlaying(String compactAfter) {
        String value = fold(compactAfter);
        if (value.isEmpty()) return false;
        return value.contains("暫停")
                || value.contains("暂停")
                || value.contains("pause")
                || value.contains("正在播放")
                || value.contains("nowplaying");
    }

    static boolean shouldComplete(
            String packageName,
            String targetMetadata,
            boolean tapSuccess,
            boolean musicActiveBefore,
            boolean musicActiveAfter,
            boolean uiIndicatesPlaying) {
        boolean playbackBecameActive =
                !musicActiveBefore && musicActiveAfter;
        return isDefaultTrustedPackage(packageName)
                && isPlayControl(targetMetadata)
                && tapSuccess
                && (playbackBecameActive || uiIndicatesPlaying);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String fold(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+", "");
    }
}
