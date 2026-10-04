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
                || value.equals("resume")
                || value.equals("resumeplayback")
                || value.equals("繼續播放")
                || value.equals("继续播放")
                || value.equals("恢復播放")
                || value.equals("恢复播放")
                || value.contains("media:play")
                || value.contains("media:resume")
                || value.contains("play_control")
                || value.contains("resume_control");
    }

    static boolean isMediaTransportGoal(String goalIntent) {
        String goal = clean(goalIntent);
        return "MEDIA:PLAY".equals(goal)
                || "MEDIA:PAUSE".equals(goal)
                || "MEDIA:NEXT".equals(goal)
                || "MEDIA:PREVIOUS".equals(goal);
    }

    static boolean isPauseControl(String metadata) {
        String value = fold(metadata);
        return value.equals("暫停")
                || value.equals("暂停")
                || value.equals("pause")
                || value.equals("pausebutton")
                || value.equals("pauseplayback")
                || value.contains("media:pause")
                || value.contains("pause_control");
    }

    static boolean isNextControl(String metadata) {
        String value = fold(metadata);
        return value.equals("下一首")
                || value.equals("下一曲")
                || value.equals("下一個音軌")
                || value.equals("下一个音轨")
                || value.equals("next")
                || value.equals("nexttrack")
                || value.equals("skip")
                || value.equals("skiptrack")
                || value.equals("skipforward")
                || value.contains("media:next")
                || value.contains("next_control");
    }

    static boolean isPreviousControl(String metadata) {
        String value = fold(metadata);
        return value.equals("上一首")
                || value.equals("上一曲")
                || value.equals("上一個音軌")
                || value.equals("上一个音轨")
                || value.equals("previous")
                || value.equals("previoustrack")
                || value.equals("prev")
                || value.equals("prevtrack")
                || value.equals("skipback")
                || value.contains("media:previous")
                || value.contains("previous_control");
    }

    static boolean isMatchingTransportControl(
            String goalIntent,
            String targetMetadata) {
        String goal = clean(goalIntent);
        if ("MEDIA:PLAY".equals(goal)) {
            return isPlayControl(targetMetadata);
        }
        if ("MEDIA:PAUSE".equals(goal)) {
            return isPauseControl(targetMetadata);
        }
        if ("MEDIA:NEXT".equals(goal)) {
            return isNextControl(targetMetadata);
        }
        if ("MEDIA:PREVIOUS".equals(goal)) {
            return isPreviousControl(targetMetadata);
        }
        return false;
    }

    static boolean shouldCompleteFromVerifiedEffect(
            String goalIntent,
            String targetMetadata,
            boolean committed,
            boolean observableEffect) {
        String goal = clean(goalIntent);

        // MEDIA:PLAY is special: a changed screen only proves that the tap did
        // something, not that audio is actually playing. Play completion must
        // come from AudioManager activation or a UI state that exposes Pause /
        // Now Playing. Other transport controls can still complete from a
        // verified observable effect.
        if ("MEDIA:PLAY".equals(goal)) {
            return false;
        }

        return isMediaTransportGoal(goal)
                && isMatchingTransportControl(goal, targetMetadata)
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

        if (!isPlayControl(targetMetadata) || !tapSuccess) {
            return false;
        }

        // Never equate "Play was tapped" or "the screen changed" with actual
        // playback. Completion needs strong post-action evidence from the
        // trusted player: audio became active, or the UI now exposes a playing
        // state such as Pause / Now Playing.
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
