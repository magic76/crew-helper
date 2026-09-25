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

    static boolean shouldCompleteFromVerifiedEffect(
            String goalIntent,
            String targetMetadata,
            boolean committed,
            boolean observableEffect) {
        return "MEDIA:PLAY".equals(clean(goalIntent))
                && isPlayControl(targetMetadata)
                && committed
                && observableEffect;
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
        return shouldComplete(
                packageName,
                targetMetadata,
                tapSuccess,
                musicActiveBefore,
                musicActiveAfter,
                uiIndicatesPlaying,
                "",
                false);
    }

    static boolean shouldComplete(
            String packageName,
            String targetMetadata,
            boolean tapSuccess,
            boolean musicActiveBefore,
            boolean musicActiveAfter,
            boolean uiIndicatesPlaying,
            String goalIntent,
            boolean screenChanged) {
        boolean playbackBecameActive =
                !musicActiveBefore && musicActiveAfter;
        boolean explicitPlayGoalEffect =
                "MEDIA:PLAY".equals(clean(goalIntent))
                        && screenChanged;

        if (!isPlayControl(targetMetadata) || !tapSuccess) {
            return false;
        }

        // Strong playback evidence stays available for the default trusted
        // package. But when the user's terminal goal is explicitly MEDIA:PLAY,
        // a verified Play-control tap that produced an observable screen effect
        // is already sufficient proof that the app accepted the requested
        // low-risk action. Do not wait on AudioManager/UI state and then time out.
        if (explicitPlayGoalEffect) {
            return true;
        }

        return isDefaultTrustedPackage(packageName)
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
