package com.crewpocket.helper;

/** Pure geometry policy for the Crew Orbit Mark node. */
final class CrewOrbitMarkPolicy {
    static final float BASE_NODE_ANGLE_DEG = -42f;
    static final float THINKING_TRAVEL_DEG = 78f;
    static final float ACTING_TRAVEL_DEG = 92f;

    private CrewOrbitMarkPolicy() {}

    static float nodeAngleDegrees(
            BubbleLogoStatePolicy.Mode mode,
            float rotationAngle) {
        BubbleLogoStatePolicy.Mode resolved =
                mode == null
                        ? BubbleLogoStatePolicy.Mode.IDLE
                        : mode;
        float normalized =
                (((rotationAngle % 360f) + 360f) % 360f) / 360f;

        if (resolved == BubbleLogoStatePolicy.Mode.THINKING) {
            double radians = normalized * Math.PI * 2d;
            float pingPong =
                    0.5f - 0.5f * (float) Math.cos(radians);
            return BASE_NODE_ANGLE_DEG
                    - THINKING_TRAVEL_DEG * pingPong;
        }

        if (resolved == BubbleLogoStatePolicy.Mode.ACTING) {
            return BASE_NODE_ANGLE_DEG
                    - ACTING_TRAVEL_DEG * normalized;
        }

        return BASE_NODE_ANGLE_DEG;
    }

    static boolean movesAlongMark(
            BubbleLogoStatePolicy.Mode mode) {
        return mode == BubbleLogoStatePolicy.Mode.THINKING
                || mode == BubbleLogoStatePolicy.Mode.ACTING;
    }
}
