package com.crewpocket.helper;

/** Pure camera-orientation policy shared by deterministic photo capture. */
final class CameraCapturePolicy {
    static final long MAX_PICTURE_PIXELS = 3_200_000L;
    static final int JPEG_QUALITY = 82;

    private CameraCapturePolicy() {}

    /**
     * Prefer the largest supported still size at or below the AI/photo budget.
     * If a device exposes only larger sizes, choose the smallest one instead of
     * silently falling back to the sensor maximum.
     */
    static int[] selectPictureSize(int[][] sizes) {
        if (sizes == null || sizes.length == 0) return null;

        int[] bestWithinBudget = null;
        long bestWithinPixels = -1L;
        int[] smallestFallback = null;
        long smallestPixels = Long.MAX_VALUE;

        for (int[] size : sizes) {
            if (size == null || size.length < 2
                    || size[0] <= 0 || size[1] <= 0) {
                continue;
            }
            long pixels = size[0] * (long) size[1];
            if (pixels < smallestPixels) {
                smallestPixels = pixels;
                smallestFallback = new int[]{size[0], size[1]};
            }
            if (pixels <= MAX_PICTURE_PIXELS
                    && pixels > bestWithinPixels) {
                bestWithinPixels = pixels;
                bestWithinBudget = new int[]{size[0], size[1]};
            }
        }

        return bestWithinBudget != null
                ? bestWithinBudget
                : smallestFallback;
    }

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
