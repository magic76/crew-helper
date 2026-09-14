package com.crewpocket.helper;

public final class VisionCoordinateMapperTest {
    public static void main(String[] args) {
        assertNear(540.0,
                VisionCoordinateMapper.imageToScreen(230.5, 461, 1080),
                "old-width center");
        assertNear(540.0,
                VisionCoordinateMapper.imageToScreen(345.5, 691, 1080),
                "new-width center");
        assertNear(1200.0,
                VisionCoordinateMapper.imageToScreen(512.0, 1024, 2400),
                "old-height center");
        assertNear(1200.0,
                VisionCoordinateMapper.imageToScreen(768.0, 1536, 2400),
                "new-height center");
        assertNear(1080.0,
                VisionCoordinateMapper.imageToScreen(691.0, 691, 1080),
                "right edge");
        assertNear(2400.0,
                VisionCoordinateMapper.imageToScreen(1536.0, 1536, 2400),
                "bottom edge");
        System.out.println("VisionCoordinateMapperTest OK");
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 0.01) {
            throw new AssertionError(
                    label + ": expected=" + expected + " actual=" + actual);
        }
    }
}
