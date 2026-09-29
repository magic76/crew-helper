package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CameraCaptureManager {
    private static final long EXPOSURE_SETTLE_MS = 450L;
    private static final long AUTOFOCUS_TIMEOUT_MS = 1200L;

    public interface CaptureCallback {
        void onSuccess(String filePath);
        void onError(String error);
    }

    public static void capturePhoto(
            final Context context,
            final boolean isFront,
            final CaptureCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Camera camera = null;
                SurfaceTexture surfaceTexture = null;
                try {
                    int cameraId = findCameraId(isFront);
                    if (cameraId < 0) {
                        callback.onError(
                                isFront
                                        ? "Front camera unavailable"
                                        : "Back camera unavailable");
                        return;
                    }

                    Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                    Camera.getCameraInfo(cameraId, cameraInfo);

                    camera = Camera.open(cameraId);
                    int[] textures = new int[1];
                    android.opengl.GLES20.glGenTextures(
                            1, textures, 0);
                    surfaceTexture =
                            new SurfaceTexture(textures[0]);
                    camera.setPreviewTexture(surfaceTexture);

                    Camera.Parameters params = camera.getParameters();
                    configurePictureQuality(params);
                    configureJpegRotation(
                            context,
                            params,
                            cameraInfo,
                            isFront);
                    boolean shouldAutoFocus =
                            configureFocusAndMetering(
                                    params,
                                    isFront);

                    camera.setParameters(params);
                    camera.startPreview();

                    // Give AE/AWB a brief deterministic settling window before
                    // asking AF to lock. This is not used as proof of focus.
                    Thread.sleep(EXPOSURE_SETTLE_MS);

                    if (shouldAutoFocus) {
                        awaitAutoFocus(camera);
                    }

                    final Camera finalCam = camera;
                    final SurfaceTexture finalSurfaceTexture =
                            surfaceTexture;
                    camera.takePicture(
                            null,
                            null,
                            new Camera.PictureCallback() {
                                @Override
                                public void onPictureTaken(
                                        byte[] data,
                                        Camera cam) {
                                    try {
                                        File dir = new File(
                                                "/sdcard/Pictures/CrewPocket");
                                        dir.mkdirs();

                                        String timeStamp =
                                                new SimpleDateFormat(
                                                        "yyyyMMdd_HHmmss",
                                                        Locale.getDefault())
                                                        .format(new Date());
                                        File file = new File(
                                                dir,
                                                "IMG_" + timeStamp + ".jpg");

                                        writeBytes(file, data);

                                        // Keep a stable path for downstream
                                        // Runtime consumers.
                                        try {
                                            writeBytes(
                                                    new File(
                                                            dir,
                                                            "latest_camera_photo.jpg"),
                                                    data);
                                        } catch (Exception ignored) {}

                                        PhotoCapturePreviewOverlay.show(
                                                context,
                                                file.getAbsolutePath(),
                                                isFront);
                                        callback.onSuccess(
                                                file.getAbsolutePath());
                                    } catch (Exception error) {
                                        callback.onError(
                                                "Save failed: "
                                                        + error.getMessage());
                                    } finally {
                                        release(
                                                finalCam,
                                                finalSurfaceTexture);
                                    }
                                }
                            });
                } catch (Exception error) {
                    release(camera, surfaceTexture);
                    callback.onError(
                            "Camera error: " + error.getMessage());
                }
            }
        }, "CrewPhotoCapture").start();
    }

    private static int findCameraId(boolean front) {
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            int expected = front
                    ? Camera.CameraInfo.CAMERA_FACING_FRONT
                    : Camera.CameraInfo.CAMERA_FACING_BACK;
            if (info.facing == expected) {
                return i;
            }
        }
        return -1;
    }

    private static void configurePictureQuality(
            Camera.Parameters params) {
        List<Camera.Size> sizes =
                params.getSupportedPictureSizes();
        if (sizes != null && !sizes.isEmpty()) {
            int[][] candidates = new int[sizes.size()][2];
            for (int i = 0; i < sizes.size(); i++) {
                Camera.Size size = sizes.get(i);
                candidates[i][0] = size.width;
                candidates[i][1] = size.height;
            }
            int[] selected =
                    CameraCapturePolicy.selectPictureSize(candidates);
            if (selected != null) {
                params.setPictureSize(selected[0], selected[1]);
            }
        }
        params.setJpegQuality(CameraCapturePolicy.JPEG_QUALITY);
    }

    private static void configureJpegRotation(
            Context context,
            Camera.Parameters params,
            Camera.CameraInfo info,
            boolean isFront) {
        int surfaceRotation = Surface.ROTATION_0;
        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(
                            Context.WINDOW_SERVICE);
            if (windowManager != null) {
                surfaceRotation =
                        windowManager.getDefaultDisplay()
                                .getRotation();
            }
        } catch (Exception ignored) {}

        int displayDegrees =
                CameraCapturePolicy.displayDegrees(
                        surfaceRotation);
        int jpegRotation =
                CameraCapturePolicy.jpegRotation(
                        info.orientation,
                        displayDegrees,
                        isFront);
        params.setRotation(jpegRotation);
    }

    private static boolean configureFocusAndMetering(
            Camera.Parameters params,
            boolean isFront) {
        List<String> focusModes =
                params.getSupportedFocusModes();
        boolean hasAuto =
                focusModes != null
                        && focusModes.contains(
                                Camera.Parameters.FOCUS_MODE_AUTO);
        boolean hasContinuousPicture =
                focusModes != null
                        && focusModes.contains(
                                Camera.Parameters
                                        .FOCUS_MODE_CONTINUOUS_PICTURE);

        // A real AF callback is preferred whenever the hardware exposes it.
        // Fixed-focus front cameras simply fall through without waiting.
        if (hasAuto) {
            params.setFocusMode(
                    Camera.Parameters.FOCUS_MODE_AUTO);
        } else if (hasContinuousPicture) {
            params.setFocusMode(
                    Camera.Parameters
                            .FOCUS_MODE_CONTINUOUS_PICTURE);
        }

        Camera.Area centerArea =
                new Camera.Area(
                        new Rect(-300, -300, 300, 300),
                        1000);
        ArrayList<Camera.Area> areas =
                new ArrayList<Camera.Area>();
        areas.add(centerArea);

        try {
            if (params.getMaxNumFocusAreas() > 0
                    && (hasAuto || hasContinuousPicture)) {
                params.setFocusAreas(areas);
            }
        } catch (Exception ignored) {}

        try {
            if (params.getMaxNumMeteringAreas() > 0) {
                params.setMeteringAreas(areas);
            }
        } catch (Exception ignored) {}

        return hasAuto;
    }

    private static void awaitAutoFocus(Camera camera) {
        final CountDownLatch focused =
                new CountDownLatch(1);
        try {
            camera.autoFocus(
                    new Camera.AutoFocusCallback() {
                        @Override
                        public void onAutoFocus(
                                boolean success,
                                Camera camera) {
                            focused.countDown();
                        }
                    });
            focused.await(
                    AUTOFOCUS_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // AF failure/timeout is non-fatal. Capture still proceeds so a
            // difficult scene cannot stall a voice command indefinitely.
        }
    }

    private static void writeBytes(
            File file,
            byte[] data) throws Exception {
        FileOutputStream output =
                new FileOutputStream(file);
        try {
            output.write(data);
            output.flush();
        } finally {
            output.close();
        }
    }

    private static void release(
            Camera camera,
            SurfaceTexture surfaceTexture) {
        if (camera != null) {
            try {
                camera.release();
            } catch (Exception ignored) {}
        }
        if (surfaceTexture != null) {
            try {
                surfaceTexture.release();
            } catch (Exception ignored) {}
        }
    }
}
