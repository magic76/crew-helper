package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-interactive numbered overlay used to create a shared visual reference
 * between the user and Gemini Live.
 *
 * It never executes taps and never intercepts touch. Runtime remains the only
 * component allowed to turn the user's confirmed cell into a phone mutation.
 */
final class SharedVisualReferenceOverlay {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WindowManager windowManager;
    private static ReferenceView view;

    private SharedVisualReferenceOverlay() {}

    static void show(Context context,
                     List<VisualReferenceGrid.Cell> cells,
                     String title,
                     boolean refined) {
        if (context == null || cells == null || cells.isEmpty()) return;
        final Context app = context.getApplicationContext();
        final ArrayList<VisualReferenceGrid.Cell> snapshot =
                new ArrayList<VisualReferenceGrid.Cell>(cells);
        final String safeTitle = clip(title, 72);
        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    dismissLocked();
                    try {
                        windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                        if (windowManager == null) return;

                        view = new ReferenceView(app, snapshot, safeTitle, refined);
                        view.setImportantForAccessibility(
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        params.gravity = Gravity.TOP | Gravity.START;
                        windowManager.addView(view, params);
                    } catch (Exception ignored) {
                        dismissLocked();
                    }
                }
            }
        });
    }

    static void dismiss() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) { dismissLocked(); }
            }
        });
    }

    private static void dismissLocked() {
        if (windowManager != null && view != null) {
            try { windowManager.removeViewImmediate(view); }
            catch (Exception ignored) {}
        }
        view = null;
        windowManager = null;
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static final class ReferenceView extends View {
        private final List<VisualReferenceGrid.Cell> cells;
        private final String title;
        private final boolean refined;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG);

        ReferenceView(Context context,
                      List<VisualReferenceGrid.Cell> cells,
                      String title,
                      boolean refined) {
            super(context);
            this.cells = cells;
            this.title = title;
            this.refined = refined;

            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setColor(Color.argb(230, 255, 255, 255));

            chip.setStyle(Paint.Style.FILL);
            chip.setColor(Color.argb(215, 20, 20, 24));

            text.setColor(Color.WHITE);
            text.setTextSize(sp(13));
            text.setTypeface(Typeface.DEFAULT_BOLD);

            shade.setStyle(Paint.Style.FILL);
            shade.setColor(Color.argb(75, 0, 0, 0));

            banner.setStyle(Paint.Style.FILL);
            banner.setColor(Color.argb(225, 18, 18, 22));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (cells == null || cells.isEmpty()) return;

            if (refined) drawOutsideShade(canvas);

            for (VisualReferenceGrid.Cell cell : cells) {
                RectF r = new RectF(cell.left, cell.top, cell.right, cell.bottom);
                canvas.drawRect(r, line);
                drawChip(canvas, cell);
            }
            drawBanner(canvas);
        }

        private void drawOutsideShade(Canvas canvas) {
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (VisualReferenceGrid.Cell cell : cells) {
                left = Math.min(left, cell.left);
                top = Math.min(top, cell.top);
                right = Math.max(right, cell.right);
                bottom = Math.max(bottom, cell.bottom);
            }
            if (left > right || top > bottom) return;
            canvas.drawRect(0, 0, getWidth(), Math.max(0, top), shade);
            canvas.drawRect(0, Math.max(0, bottom), getWidth(), getHeight(), shade);
            canvas.drawRect(0, Math.max(0, top), Math.max(0, left), Math.max(0, bottom), shade);
            canvas.drawRect(Math.max(0, right), Math.max(0, top), getWidth(), Math.max(0, bottom), shade);
        }

        private void drawChip(Canvas canvas, VisualReferenceGrid.Cell cell) {
            String label = cell.displayLabel();
            float padX = dp(7);
            float padY = dp(5);
            float textWidth = text.measureText(label);
            float h = text.getTextSize() + padY * 2;
            float left = cell.left + dp(6);
            float top = cell.top + dp(6);
            float maxWidth = Math.max(dp(42), cell.width() - dp(12));
            float w = Math.min(textWidth + padX * 2, maxWidth);
            RectF box = new RectF(left, top, left + w, top + h);
            canvas.drawRoundRect(box, dp(9), dp(9), chip);

            String draw = label;
            while (draw.length() > 1 && text.measureText(draw) > w - padX * 2) {
                draw = draw.substring(0, draw.length() - 1);
            }
            canvas.drawText(draw, left + padX,
                    top + padY + text.getTextSize() * 0.82f, text);
        }

        private void drawBanner(Canvas canvas) {
            String label = title.isEmpty()
                    ? (refined ? "再選一次：說 1–9 或位置" : "說 1–12 或位置")
                    : title;
            float margin = dp(12);
            float padX = dp(12);
            float padY = dp(8);
            float width = Math.min(getWidth() - margin * 2,
                    text.measureText(label) + padX * 2);
            float height = text.getTextSize() + padY * 2;
            RectF box = new RectF(margin, margin, margin + width, margin + height);
            canvas.drawRoundRect(box, dp(12), dp(12), banner);

            String draw = label;
            while (draw.length() > 1 && text.measureText(draw) > width - padX * 2) {
                draw = draw.substring(0, draw.length() - 1);
            }
            canvas.drawText(draw, margin + padX,
                    margin + padY + text.getTextSize() * 0.82f, text);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float sp(float value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }
    }
}
