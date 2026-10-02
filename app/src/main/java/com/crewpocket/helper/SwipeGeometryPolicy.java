package com.crewpocket.helper;

import java.util.Locale;

/**
 * Safe normalized swipe geometry.
 *
 * Coordinates deliberately stay away from system gesture edges/status areas:
 * horizontal starts/ends remain inside 16-40% margins depending on distance;
 * vertical starts/ends remain inside 22-42% margins.
 */
final class SwipeGeometryPolicy {
    static final class Fractions {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final int durationMs;

        Fractions(
                float x1,
                float y1,
                float x2,
                float y2,
                int durationMs) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.durationMs = durationMs;
        }
    }

    private SwipeGeometryPolicy() {}

    static Fractions forPhysicalDirection(
            String rawDirection,
            String rawDistance) {
        String direction = normalize(rawDirection, "up");
        String distance = normalize(rawDistance, "normal");

        boolean shortDistance =
                "short".equals(distance)
                        || "little".equals(distance);
        boolean longDistance =
                "long".equals(distance)
                        || "page".equals(distance)
                        || "fast".equals(distance);

        float horizontalHigh = shortDistance
                ? 0.60f
                : (longDistance ? 0.84f : 0.80f);
        float horizontalLow = 1f - horizontalHigh;

        float verticalHigh = shortDistance
                ? 0.60f
                : (longDistance ? 0.78f : 0.72f);
        float verticalLow = shortDistance
                ? 0.42f
                : (longDistance ? 0.24f : 0.30f);

        int duration = shortDistance
                ? 260
                : (longDistance ? 280 : 320);

        if ("down".equals(direction)) {
            return new Fractions(
                    0.50f,
                    verticalLow,
                    0.50f,
                    verticalHigh,
                    duration);
        }
        if ("left".equals(direction)) {
            return new Fractions(
                    horizontalHigh,
                    0.52f,
                    horizontalLow,
                    0.52f,
                    duration);
        }
        if ("right".equals(direction)) {
            return new Fractions(
                    horizontalLow,
                    0.52f,
                    horizontalHigh,
                    0.52f,
                    duration);
        }

        return new Fractions(
                0.50f,
                verticalHigh,
                0.50f,
                verticalLow,
                duration);
    }

    static float horizontalHigh(String rawDistance) {
        String distance = normalize(rawDistance, "normal");
        if ("short".equals(distance) || "little".equals(distance)) {
            return 0.68f;
        }
        if ("long".equals(distance)
                || "page".equals(distance)
                || "fast".equals(distance)) {
            return 0.84f;
        }
        return 0.80f;
    }

    static float horizontalLow(String rawDistance) {
        return 1f - horizontalHigh(rawDistance);
    }

    private static String normalize(
            String value,
            String fallback) {
        String clean = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        return clean.isEmpty() ? fallback : clean;
    }
}
