package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Lightweight post-capture confirmation overlay.
 *
 * This is intentionally separate from CameraPreviewOverlay: one-shot photos
 * should not start or compete with the live Camera session. The overlay shows
 * the exact file that was saved, then removes itself automatically.
 */
final class PhotoCapturePreviewOverlay {
    private static final Object LOCK = new Object();
    private static View currentView;
    private static WindowManager currentWindowManager;
    private static Runnable currentHideRunnable;

    private PhotoCapturePreviewOverlay() {}

    static void show(
            final Context context,
            final String path,
            final boolean frontFacing) {
        if (context == null || path == null || path.trim().isEmpty()) return;
        final Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                showOnMain(appContext, path, frontFacing);
            }
        });
    }

    private static void showOnMain(
            final Context context,
            final String path,
            final boolean frontFacing) {
        if (Build.VERSION.SDK_INT >= 23
                && !Settings.canDrawOverlays(context)) {
            return;
        }

        final File file = new File(path);
        if (!file.isFile()) return;

        final WindowManager windowManager =
                (WindowManager) context.getSystemService(
                        Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        removeCurrent();

        final Handler handler = new Handler(Looper.getMainLooper());
        final int screenWidth =
                context.getResources().getDisplayMetrics().widthPixels;
        final int screenHeight =
                context.getResources().getDisplayMetrics().heightPixels;

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 8), dp(context, 8),
                dp(context, 8), dp(context, 8));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#F20F172A"));
        background.setCornerRadius(dp(context, 18));
        background.setStroke(dp(context, 1), Color.parseColor("#475569"));
        root.setBackground(background);
        if (Build.VERSION.SDK_INT >= 21) {
            root.setElevation(dp(context, 14));
        }

        TextView title = new TextView(context);
        title.setText("📷 已拍攝");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Color.BLACK);
        Bitmap preview = decodePreview(path, 960);
        if (preview == null) return;
        image.setImageBitmap(preview);

        final LinearLayout.LayoutParams imageParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 164));
        root.addView(image, imageParams);

        TextView meta = new TextView(context);
        long bytes = Math.max(0L, file.length());
        String sizeText = bytes >= 1024L * 1024L
                ? String.format(java.util.Locale.US, "%.1f MB",
                        bytes / (1024f * 1024f))
                : Math.max(1L, Math.round(bytes / 1024f)) + " KB";
        meta.setText((frontFacing ? "前鏡頭" : "後鏡頭")
                + " · " + sizeText + " · 點一下放大");
        meta.setTextColor(Color.parseColor("#CBD5E1"));
        meta.setTextSize(10);
        meta.setPadding(dp(context, 4), dp(context, 6),
                dp(context, 4), 0);
        root.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int overlayType = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        dp(context, 240),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(context, 12);
        params.y = dp(context, 84);

        final boolean[] expanded = new boolean[]{false};
        image.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded[0] = !expanded[0];
                if (expanded[0]) {
                    params.width = Math.max(
                            dp(context, 280),
                            screenWidth - dp(context, 24));
                    imageParams.height = Math.max(
                            dp(context, 280),
                            Math.round(screenHeight * 0.58f));
                } else {
                    params.width = dp(context, 240);
                    imageParams.height = dp(context, 164);
                }
                image.setLayoutParams(imageParams);
                try {
                    windowManager.updateViewLayout(root, params);
                } catch (Exception ignored) {}
                scheduleHide(
                        handler,
                        windowManager,
                        root,
                        expanded[0] ? 8000L : 3500L);
            }
        });

        try {
            windowManager.addView(root, params);
            synchronized (LOCK) {
                currentView = root;
                currentWindowManager = windowManager;
            }
            scheduleHide(handler, windowManager, root, 3500L);
        } catch (Exception ignored) {
            try { preview.recycle(); } catch (Exception ignoredRecycle) {}
        }
    }

    private static void scheduleHide(
            final Handler handler,
            final WindowManager windowManager,
            final View root,
            long delayMs) {
        synchronized (LOCK) {
            if (currentHideRunnable != null) {
                handler.removeCallbacks(currentHideRunnable);
            }
            currentHideRunnable = new Runnable() {
                @Override public void run() {
                    synchronized (LOCK) {
                        if (currentView != root) return;
                    }
                    try {
                        windowManager.removeViewImmediate(root);
                    } catch (Exception ignored) {}
                    synchronized (LOCK) {
                        if (currentView == root) {
                            currentView = null;
                            currentWindowManager = null;
                            currentHideRunnable = null;
                        }
                    }
                }
            };
            handler.postDelayed(currentHideRunnable, delayMs);
        }
    }

    private static void removeCurrent() {
        synchronized (LOCK) {
            if (currentView != null && currentWindowManager != null) {
                try {
                    currentWindowManager.removeViewImmediate(currentView);
                } catch (Exception ignored) {}
            }
            currentView = null;
            currentWindowManager = null;
            currentHideRunnable = null;
        }
    }

    private static Bitmap decodePreview(String path, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int width = Math.max(1, bounds.outWidth);
            int height = Math.max(1, bounds.outHeight);
            int sample = 1;
            while (Math.max(width / sample, height / sample) > maxEdge) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int dp(Context context, float value) {
        return (int) (value
                * context.getResources().getDisplayMetrics().density
                + 0.5f);
    }
}
