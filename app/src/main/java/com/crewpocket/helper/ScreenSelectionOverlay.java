package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.widget.Toast;
import android.util.Log;

/**
 * Full-screen explicit selection surface.
 *
 * It only establishes a user reference ("this region"). It never clicks,
 * scrolls, types, or performs any phone mutation.
 */
final class ScreenSelectionOverlay {
    private static final String TAG = "CrewSelectionOverlay";
    interface Callback {
        void onSelected(Rect region, int screenWidth, int screenHeight);
        void onCancelled();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SelectionView view;
    private Callback callback;

    ScreenSelectionOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager)
                this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean show(Callback callback) {
        if (windowManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(context)) {
            return false;
        }

        dismiss(false);
        this.callback = callback;
        this.view = new SelectionView(context);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(view, lp);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to attach selection overlay", error);
            view = null;
            this.callback = null;
            return false;
        }
    }

    void dismiss() {
        dismiss(true);
    }

    private void dismiss(boolean notifyCancel) {
        SelectionView old = view;
        view = null;
        if (old != null) {
            try {
                windowManager.removeViewImmediate(old);
            } catch (Exception ignored) {}
        }

        Callback oldCallback = callback;
        callback = null;
        if (notifyCancel && oldCallback != null) {
            oldCallback.onCancelled();
        }
    }

    private void complete(Rect region) {
        if (region == null || region.width() <= 0 || region.height() <= 0) {
            return;
        }

        final Callback target = callback;
        final int width = view == null ? 1 : Math.max(1, view.getWidth());
        final int height = view == null ? 1 : Math.max(1, view.getHeight());

        dismiss(false);

        // Give SurfaceFlinger one frame to remove the selector before the
        // Accessibility screenshot is requested.
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (target != null) {
                    target.onSelected(region, width, height);
                }
            }
        }, 80L);
    }

    private final class SelectionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF selection = new RectF();
        private final RectF confirmButton = new RectF();
        private final RectF cancelButton = new RectF();

        private float downX;
        private float downY;
        private boolean dragging;
        private boolean hasSelection;

        SelectionView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setFocusable(true);
            setClickable(true);

            textPaint.setTypeface(
                    android.graphics.Typeface.create(
                            "sans-serif-medium",
                            android.graphics.Typeface.NORMAL));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float d = getResources().getDisplayMetrics().density;
            int width = getWidth();
            int height = getHeight();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(155, 2, 6, 23));

            if (hasSelection && selection.width() > 0 && selection.height() > 0) {
                canvas.drawRect(0, 0, width, selection.top, paint);
                canvas.drawRect(0, selection.bottom, width, height, paint);
                canvas.drawRect(
                        0,
                        selection.top,
                        selection.left,
                        selection.bottom,
                        paint);
                canvas.drawRect(
                        selection.right,
                        selection.top,
                        width,
                        selection.bottom,
                        paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.2f * d);
                paint.setColor(Color.parseColor("#22D3EE"));
                canvas.drawRoundRect(selection, 8f * d, 8f * d, paint);
            } else {
                canvas.drawRect(0, 0, width, height, paint);
            }

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(15f * d);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(
                    hasSelection ? "確認框選範圍" : "拖曳框選你要 Crew 理解的區域",
                    width / 2f,
                    48f * d,
                    textPaint);

            textPaint.setTextSize(11.5f * d);
            textPaint.setColor(Color.parseColor("#CBD5E1"));
            canvas.drawText(
                    hasSelection
                            ? "框選只是指定「這個」，不會直接點擊畫面"
                            : "例如地址、商品、錯誤訊息、按鈕或一段文字",
                    width / 2f,
                    70f * d,
                    textPaint);

            if (!hasSelection) return;

            float buttonHeight = 46f * d;
            float buttonWidth = Math.min(142f * d, width * 0.38f);
            float gap = 12f * d;
            float total = buttonWidth * 2 + gap;
            float left = (width - total) / 2f;
            float top = height - 82f * d;

            cancelButton.set(
                    left,
                    top,
                    left + buttonWidth,
                    top + buttonHeight);
            confirmButton.set(
                    left + buttonWidth + gap,
                    top,
                    left + buttonWidth + gap + buttonWidth,
                    top + buttonHeight);

            drawButton(
                    canvas,
                    cancelButton,
                    "取消",
                    Color.argb(235, 39, 39, 42),
                    Color.parseColor("#71717A"));
            drawButton(
                    canvas,
                    confirmButton,
                    "使用這個",
                    Color.argb(242, 8, 47, 73),
                    Color.parseColor("#22D3EE"));
        }

        private void drawButton(
                Canvas canvas,
                RectF rect,
                String label,
                int fill,
                int stroke) {
            float d = getResources().getDisplayMetrics().density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(rect, 13f * d, 13f * d, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * d);
            paint.setColor(stroke);
            canvas.drawRoundRect(rect, 13f * d, 13f * d, paint);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(13f * d);
            textPaint.setColor(Color.WHITE);
            float baseline = rect.centerY()
                    - (textPaint.ascent() + textPaint.descent()) / 2f;
            canvas.drawText(label, rect.centerX(), baseline, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (hasSelection && confirmButton.contains(x, y)) {
                    complete(normalizedSelection());
                    return true;
                }
                if (hasSelection && cancelButton.contains(x, y)) {
                    dismiss(true);
                    return true;
                }

                downX = x;
                downY = y;
                selection.set(x, y, x, y);
                dragging = true;
                hasSelection = false;
                invalidate();
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && dragging) {
                selection.set(
                        Math.min(downX, x),
                        Math.min(downY, y),
                        Math.max(downX, x),
                        Math.max(downY, y));
                hasSelection = true;
                invalidate();
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && dragging) {
                dragging = false;
                selection.set(
                        Math.min(downX, x),
                        Math.min(downY, y),
                        Math.max(downX, x),
                        Math.max(downY, y));

                float minSize = 44f
                        * getResources().getDisplayMetrics().density;
                if (selection.width() < minSize
                        || selection.height() < minSize) {
                    hasSelection = false;
                    Toast.makeText(
                            context,
                            "框選範圍太小，請重新拖曳",
                            Toast.LENGTH_SHORT).show();
                } else {
                    hasSelection = true;
                }
                invalidate();
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                return true;
            }

            return true;
        }

        private Rect normalizedSelection() {
            int left = Math.max(0, Math.round(selection.left));
            int top = Math.max(0, Math.round(selection.top));
            int right = Math.min(getWidth(), Math.round(selection.right));
            int bottom = Math.min(getHeight(), Math.round(selection.bottom));
            return new Rect(left, top, right, bottom);
        }
    }
}
