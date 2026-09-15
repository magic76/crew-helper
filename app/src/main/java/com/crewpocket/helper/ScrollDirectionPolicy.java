package com.crewpocket.helper;

import java.util.Locale;

/**
 * Converts user/model-facing content direction into physical finger movement.
 *
 * Semantic direction answers: "which content should become visible?"
 * Physical direction answers: "which way should the finger move?"
 */
final class ScrollDirectionPolicy {
    private ScrollDirectionPolicy() {}

    static String normalizeSemantic(String value) {
        String direction = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (direction.isEmpty()) return "forward";

        // Backward compatibility with older sessions that used physical-looking
        // vertical words. Preserve their previous meaning as semantic navigation.
        if ("up".equals(direction)) return "forward";
        if ("down".equals(direction)) return "backward";
        return direction;
    }

    static boolean isSupported(String semanticDirection) {
        return "forward".equals(semanticDirection)
                || "backward".equals(semanticDirection)
                || "left".equals(semanticDirection)
                || "right".equals(semanticDirection);
    }

    static String toPhysical(String semanticDirection) {
        if ("forward".equals(semanticDirection)) return "up";
        if ("backward".equals(semanticDirection)) return "down";
        if ("right".equals(semanticDirection)) return "left";
        if ("left".equals(semanticDirection)) return "right";
        return semanticDirection == null ? "" : semanticDirection;
    }
}
