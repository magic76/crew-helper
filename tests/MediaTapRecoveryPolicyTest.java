package com.crewpocket.helper;

import java.util.Arrays;
import java.util.Collections;

public final class MediaTapRecoveryPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check("鄧紫棋".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放鄧紫棋",
                                "",
                                Arrays.asList(
                                        candidate("鄧紫棋", "", 0.95),
                                        candidate("播放", "", 0.95)))),
                "unique goal entity is recovered before Play");

        check("播放".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放鄧紫棋",
                                "鄧紫棋",
                                Arrays.asList(
                                        candidate("鄧紫棋", "", 0.95),
                                        candidate("播放", "", 0.95)))),
                "completed goal entity allows unique Play recovery");

        check("".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放鄧紫棋",
                                "",
                                Arrays.asList(
                                        candidate("鄧紫棋", "", 0.95),
                                        candidate("鄧紫棋", "", 0.96),
                                        candidate("播放", "", 0.95)))),
                "multiple goal candidates do not skip ahead to Play");

        check("".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放音樂",
                                "",
                                Arrays.asList(
                                        candidate("播放", "", 0.95),
                                        candidate("Play", "", 0.96)))),
                "multiple Play controls are not auto-selected");

        check("播放".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放鄧紫棋",
                                "",
                                Arrays.asList(
                                        candidate("鄧紫棋", "", 0.60),
                                        candidate("播放", "", 0.95)))),
                "low-confidence goal entity is ignored before unique Play");

        check("".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放鄧紫棋",
                                "",
                                Collections.singletonList(
                                        new MediaTapRecoveryPolicy.Candidate(
                                                "button",
                                                "鄧紫棋",
                                                "",
                                                true,
                                                true,
                                                true,
                                                0.99)))),
                "sensitive candidate is never recovered");

        check("Resume".equals(
                        MediaTapRecoveryPolicy.recoverTarget(
                                "播放音樂",
                                "",
                                Collections.singletonList(
                                        candidate("Resume", "", 0.95)))),
                "generic Resume control is eligible");

        System.out.println(
                "MediaTapRecoveryPolicyTest passed " + checks + " checks");
    }

    private static MediaTapRecoveryPolicy.Candidate candidate(
            String label,
            String semanticHint,
            double confidence) {
        return new MediaTapRecoveryPolicy.Candidate(
                "button",
                label,
                semanticHint,
                true,
                true,
                false,
                confidence);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
