package com.crewpocket.helper;

/**
 * Maps coordinates from the actual full-screen frame sent to Gemini back into
 * device-screen coordinates.
 *
 * Selected-region crops deliberately never update full-screen vision
 * dimensions, so crop coordinates cannot be routed through this mapper.
 */
final class VisionCoordinateMapper {
    private VisionCoordinateMapper() {}

    static double imageToScreen(double imageCoordinate, int imageExtent, int screenExtent) {
        return (imageCoordinate / Math.max(1, imageExtent)) * Math.max(1, screenExtent);
    }
}
