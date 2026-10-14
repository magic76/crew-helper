package com.crewpocket.helper;

import java.util.Arrays;
import java.util.Collections;

public final class MediaPlayFlowRecoveryPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        check(MediaPlayFlowRecoveryPolicy.isUnsupportedPlayAction(
                "PLAY", "MEDIA:PLAY", "com.apple.android.music"),
                "unsupported PLAY alias eligible for safe repair");
        check(MediaPlayFlowRecoveryPolicy.isUnsupportedPlayAction(
                "START_PLAYBACK", "MEDIA:PLAY", "com.apple.android.music"),
                "unsupported START_PLAYBACK eligible for safe repair");
        check(!MediaPlayFlowRecoveryPolicy.isUnsupportedPlayAction(
                "PLAY", "MEDIA:PAUSE", "com.apple.android.music"),
                "wrong goal cannot be repaired");
        check(!MediaPlayFlowRecoveryPolicy.isUnsupportedPlayAction(
                "PLAY", "MEDIA:PLAY", "com.example.shopping"),
                "other apps cannot use Apple Music action repair");
        check(!MediaPlayFlowRecoveryPolicy.isUnsupportedPlayAction(
                "DELETE", "MEDIA:PLAY", "com.apple.android.music"),
                "destructive unknown action never repaired");

        String goal = "開啟 Apple Music 搜尋蔡依林然後播放";
        check("蔡依林".equals(MediaPlayFlowRecoveryPolicy.safeNextTarget(
                goal, "", Arrays.asList(
                    candidate("蔡依林", true, 0.95),
                    candidate("播放", true, 0.95)))),
                "open unique artist result before playing");
        check("播放".equals(MediaPlayFlowRecoveryPolicy.safeNextTarget(
                goal, "蔡依林", Arrays.asList(
                    candidate("蔡依林", true, 0.95),
                    candidate("播放", true, 0.95)))),
                "once artist is opened choose a unique Play button");
        check("".equals(MediaPlayFlowRecoveryPolicy.safeNextTarget(
                goal, "", Collections.singletonList(
                    candidate("播放", true, 0.95)))),
                "do not play stale mini-player without matching artist evidence");
        check("".equals(MediaPlayFlowRecoveryPolicy.safeNextTarget(
                goal, "", Arrays.asList(
                    candidate("蔡依林", true, 0.95),
                    candidate("蔡依林", true, 0.95),
                    candidate("播放", true, 0.95)))),
                "ambiguous duplicate artist must not be skipped");
        check("".equals(MediaPlayFlowRecoveryPolicy.safeNextTarget(
                goal, "蔡依林", Arrays.asList(
                    candidate("蔡依林", true, 0.95),
                    candidate("播放", true, 0.95),
                    candidate("Play", true, 0.95)))),
                "two Play controls must not be guessed");
        check("".equals(MediaPlayFlowRecoveryPolicy.safeNextTarget(
                goal, "", Collections.singletonList(
                    candidate("蔡依林", true, 0.60)))),
                "low-confidence target is not eligible");
        check(MediaPlayFlowRecoveryPolicy.isSearchAndPlayGoal(goal),
                "search and play goal distinguished from plain play");
        System.out.println("MediaPlayFlowRecoveryPolicyTest passed "
                + checks + " checks");
    }

    private static MediaTapRecoveryPolicy.Candidate candidate(
            String label, boolean clickable, double confidence) {
        return new MediaTapRecoveryPolicy.Candidate(
                "button", label, "", clickable, true, false, confidence);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
