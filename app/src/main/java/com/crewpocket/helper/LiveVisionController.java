package com.crewpocket.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * 0106: low-level visual transport/crop state extracted from NativeGeminiLiveClient.
 *
 * This class deliberately owns only image encoding, selected-region cropping and
 * the coordinate-space dimensions of the latest full-screen frame. It does not
 * decide when to observe, tap, retry, or finish an Agent task.
 */
final class LiveVisionController {
    interface Sender {
        boolean send(String payload);
    }

    private final Sender sender;
    private volatile int lastVisionWidth = 1;
    private volatile int lastVisionHeight = 1;
    private volatile int lastScreenWidth = 1;
    private volatile int lastScreenHeight = 1;

    LiveVisionController(Sender sender) {
        this.sender = sender;
    }

    int lastVisionWidth() { return lastVisionWidth; }
    int lastVisionHeight() { return lastVisionHeight; }
    int lastScreenWidth() { return lastScreenWidth; }
    int lastScreenHeight() { return lastScreenHeight; }

    boolean sendJpegBytes(byte[] jpegBytes) throws Exception {
        if (jpegBytes == null || jpegBytes.length == 0) return false;
        JSONObject video = new JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP));
        return sendVideo(video);
    }

    boolean sendImageFile(String path, boolean isScreenFrame) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) return false;
        int sourceWidth = bitmap.getWidth();
        int sourceHeight = bitmap.getHeight();
        int maxEdge = 1024;
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxEdge) {
            float scale = maxEdge / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale),
                    true);
            bitmap.recycle();
            bitmap = scaled;
        }
        lastVisionWidth = bitmap.getWidth();
        lastVisionHeight = bitmap.getHeight();
        if (isScreenFrame) {
            lastScreenWidth = sourceWidth;
            lastScreenHeight = sourceHeight;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output);
        } finally {
            bitmap.recycle();
        }
        JSONObject video = new JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        return sendVideo(video);
    }

    boolean sendSelectedRegionSnapshot(SelectedRegionContext selected) throws Exception {
        String path = selected.snapshotPath == null ? "" : selected.snapshotPath.trim();
        if (path.isEmpty() || !new File(path).isFile()) {
            throw new Exception("selected-region frozen snapshot missing");
        }

        Bitmap full = BitmapFactory.decodeFile(path);
        if (full == null) {
            throw new Exception("selected-region screenshot decode failed");
        }

        Bitmap crop;
        try {
            int imageWidth = full.getWidth();
            int imageHeight = full.getHeight();
            float sx = imageWidth / (float) Math.max(1, selected.screenWidth);
            float sy = imageHeight / (float) Math.max(1, selected.screenHeight);

            int left = clamp(Math.round(selected.bounds.left * sx), 0, Math.max(0, imageWidth - 1));
            int top = clamp(Math.round(selected.bounds.top * sy), 0, Math.max(0, imageHeight - 1));
            int right = clamp(Math.round(selected.bounds.right * sx), left + 1, imageWidth);
            int bottom = clamp(Math.round(selected.bounds.bottom * sy), top + 1, imageHeight);

            int padX = Math.max(4, Math.round((right - left) * 0.04f));
            int padY = Math.max(4, Math.round((bottom - top) * 0.04f));
            left = Math.max(0, left - padX);
            top = Math.max(0, top - padY);
            right = Math.min(imageWidth, right + padX);
            bottom = Math.min(imageHeight, bottom + padY);

            crop = Bitmap.createBitmap(
                    full,
                    left,
                    top,
                    Math.max(1, right - left),
                    Math.max(1, bottom - top));
        } finally {
            full.recycle();
        }

        return sendContextBitmapWithoutCoordinates(crop);
    }

    private boolean sendContextBitmapWithoutCoordinates(Bitmap bitmap) throws Exception {
        if (bitmap == null) return false;
        int maxEdge = 1024;
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxEdge) {
            float scale = maxEdge / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)),
                    true);
            bitmap.recycle();
            bitmap = scaled;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output);
        } finally {
            bitmap.recycle();
        }
        JSONObject video = new JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        return sendVideo(video);
    }

    private boolean sendVideo(JSONObject video) throws Exception {
        if (sender == null) return false;
        return sender.send(new JSONObject()
                .put("realtimeInput", new JSONObject().put("video", video))
                .toString());
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
