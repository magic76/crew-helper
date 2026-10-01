package com.crewpocket.helper;

public final class CrewOrbitMarkPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(close(
                        CrewOrbitMarkPolicy.nodeAngleDegrees(
                                BubbleLogoStatePolicy.Mode.IDLE,
                                180f),
                        CrewOrbitMarkPolicy.BASE_NODE_ANGLE_DEG),
                "idle node stays parked");

        check(close(
                        CrewOrbitMarkPolicy.nodeAngleDegrees(
                                BubbleLogoStatePolicy.Mode.LISTENING,
                                270f),
                        CrewOrbitMarkPolicy.BASE_NODE_ANGLE_DEG),
                "listening node breathes without travelling");

        float thinkingStart =
                CrewOrbitMarkPolicy.nodeAngleDegrees(
                        BubbleLogoStatePolicy.Mode.THINKING,
                        0f);
        float thinkingFar =
                CrewOrbitMarkPolicy.nodeAngleDegrees(
                        BubbleLogoStatePolicy.Mode.THINKING,
                        180f);
        float thinkingReturn =
                CrewOrbitMarkPolicy.nodeAngleDegrees(
                        BubbleLogoStatePolicy.Mode.THINKING,
                        360f);
        check(close(
                        thinkingStart,
                        CrewOrbitMarkPolicy.BASE_NODE_ANGLE_DEG),
                "thinking starts at logo cut");
        check(thinkingFar
                        < CrewOrbitMarkPolicy.BASE_NODE_ANGLE_DEG - 70f,
                "thinking travels visibly along the C");
        check(close(thinkingReturn, thinkingStart),
                "thinking node returns to its cut");

        float actingStart =
                CrewOrbitMarkPolicy.nodeAngleDegrees(
                        BubbleLogoStatePolicy.Mode.ACTING,
                        0f);
        float actingLate =
                CrewOrbitMarkPolicy.nodeAngleDegrees(
                        BubbleLogoStatePolicy.Mode.ACTING,
                        300f);
        check(actingLate < actingStart - 70f,
                "acting node moves directionally along the mark");

        check(CrewOrbitMarkPolicy.movesAlongMark(
                        BubbleLogoStatePolicy.Mode.THINKING),
                "thinking moves the node");
        check(CrewOrbitMarkPolicy.movesAlongMark(
                        BubbleLogoStatePolicy.Mode.ACTING),
                "acting moves the node");
        check(!CrewOrbitMarkPolicy.movesAlongMark(
                        BubbleLogoStatePolicy.Mode.WAITING),
                "waiting keeps the node parked");

        System.out.println(
                "PASS CrewOrbitMarkPolicyTest: "
                        + checks + " checks");
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 0.001f;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
