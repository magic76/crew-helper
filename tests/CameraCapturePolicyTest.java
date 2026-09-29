package com.crewpocket.helper;

public final class CameraCapturePolicyTest {
    private static int assertions;

    private static void check(int actual, int expected, String name) {
        assertions++;
        if (actual != expected) {
            throw new AssertionError(
                    name + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void checkSize(
            int[] actual,
            int expectedWidth,
            int expectedHeight,
            String name) {
        assertions++;
        if (actual == null
                || actual.length < 2
                || actual[0] != expectedWidth
                || actual[1] != expectedHeight) {
            throw new AssertionError(
                    name + ": expected=" + expectedWidth + "x" + expectedHeight
                            + ", actual="
                            + (actual == null
                                    ? "null"
                                    : actual[0] + "x" + actual[1]));
        }
    }

    public static void main(String[] args) {
        check(CameraCapturePolicy.displayDegrees(0), 0, "rotation 0");
        check(CameraCapturePolicy.displayDegrees(1), 90, "rotation 90");
        check(CameraCapturePolicy.displayDegrees(2), 180, "rotation 180");
        check(CameraCapturePolicy.displayDegrees(3), 270, "rotation 270");

        check(CameraCapturePolicy.jpegRotation(90, 0, false),
                90, "portrait back camera");
        check(CameraCapturePolicy.jpegRotation(90, 90, false),
                0, "landscape back camera");
        check(CameraCapturePolicy.jpegRotation(90, 270, false),
                180, "reverse landscape back camera");

        check(CameraCapturePolicy.jpegRotation(270, 0, true),
                270, "portrait front camera");
        check(CameraCapturePolicy.jpegRotation(270, 90, true),
                0, "landscape front camera");
        check(CameraCapturePolicy.jpegRotation(270, 270, true),
                180, "reverse landscape front camera");

        checkSize(
                CameraCapturePolicy.selectPictureSize(
                        new int[][]{
                                {4000, 3000},
                                {2048, 1536},
                                {1920, 1080},
                                {1600, 1200}
                        }),
                2048,
                1536,
                "largest still size within budget");
        checkSize(
                CameraCapturePolicy.selectPictureSize(
                        new int[][]{
                                {8000, 6000},
                                {4000, 3000}
                        }),
                4000,
                3000,
                "smallest fallback when all sizes exceed budget");
        check(
                CameraCapturePolicy.JPEG_QUALITY,
                82,
                "jpeg quality");

        System.out.println(
                "PASS CameraCapturePolicyTest: "
                        + assertions + " checks");
    }
}
