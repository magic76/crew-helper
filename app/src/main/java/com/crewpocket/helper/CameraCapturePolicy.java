package com.crewpocket.helper;

/** Pure camera-orientation policy shared by deterministic photo capture. */
final class CameraCapturePolicy {
    private CameraCapturePolicy() {}

    static int displayDegrees(int surfaceRotation) {
        switch (surfaceRotation) {
            case 1:
                return 90;
            case 2:
                return 180;
            case 3:
                return 270;
            case 0:
            default:
                return 0;
        }
    }

    static int jpegRotation(
            int sensorOrientation,
            int displayDegrees,
            boolean frontFacing) {
        int sensor = normalize(sensorOrientation);
        int display = normalize(displayDegrees);
        if (frontFacing) {
            return normalize(sensor + display);
        }
        return normalize(sensor - display);
    }

    private static int normalize(int value) {
        int normalized = value % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }
}
