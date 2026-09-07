package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * 0015 Revised:
 * Bubble single-tap action strip.
 *
 * Idle:
 *   [Start call] [Open console]
 *
 * Live:
 *   [Hang up] [Open console] [Interrupt]
 *
 * Icons only. No labels, no mini-status card.
 */
final class BubbleActionStripOverlay {
    // Keep the expanded rail visually separate from the draggable main bubble.
    // Six dp made their shadows overlap on compact screens.
    static final int ACTION_STRIP_GAP_DP = 34;
    interface Actions {
        void onToggleCall();
        void onOpenConsole();
        void onInterrupt();
    }

    private static final int ICON_CALL = 1;
    private static final int ICON_HANGUP = 2;
    private static final int ICON_CONSOLE = 3;
    private static final int ICON_INTERRUPT = 4;

    private final Context context;
    private final WindowManager windowManager;
    private View view;

    BubbleActionStripOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() {
        return view != null;
    }

    void toggle(int bubbleX, int bubbleY, int bubbleSize, Actions actions) {
        if (isShowing()) {
            dismiss();
        } else {
            show(bubbleX, bubbleY, bubbleSize, actions);
        }
    }

    void refresh(int bubbleX, int bubbleY, int bubbleSize, Actions actions) {
        if (!isShowing()) return;
        dismiss();
        show(bubbleX, bubbleY, bubbleSize, actions);
    }

    void show(int bubbleX, int bubbleY, int bubbleSize, final Actions actions) {
        dismiss();

        final boolean live = NativeLiveService.isActive();

        // 0017: actions are a vertical rail directly below the bubble.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(5), dp(6), dp(5), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(244, 15, 23, 42));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(95, 148, 163, 184));
        row.setBackground(bg);
        row.setElevation(dp(12));

        IconButton call = iconButton(live ? ICON_HANGUP : ICON_CALL);
        call.setContentDescription(live ? "結束通話" : "開始通話");
        call.setOnClickListener(v -> {
            dismiss();
            if (actions != null) actions.onToggleCall();
        });
        row.addView(call, itemParams());

        IconButton console = iconButton(ICON_CONSOLE);
        console.setContentDescription("開啟控制台");
        console.setOnClickListener(v -> {
            dismiss();
            if (actions != null) actions.onOpenConsole();
        });
        row.addView(console, itemParams());

        if (live) {
            IconButton interrupt = iconButton(ICON_INTERRUPT);
            interrupt.setContentDescription("打斷 AI");
            interrupt.setOnClickListener(v -> {
                dismiss();
                if (actions != null) actions.onInterrupt();
            });
            row.addView(interrupt, itemParams());
        }

        int itemCount = live ? 3 : 2;
        int stripWidth = dp(50);
        int stripHeight = dp(12) + itemCount * dp(42);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                stripWidth,
                stripHeight,
                Build.VERSION.SDK_INT >= 26
                        ? 2038
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;

        // Center the vertical rail directly under the bubble.
        int centeredX = bubbleX + bubbleSize / 2 - stripWidth / 2;
        lp.x = Math.max(dp(6), Math.min(screenW - stripWidth - dp(6), centeredX));
        lp.y = bubbleY + bubbleSize + dp(ACTION_STRIP_GAP_DP);

        try {
            windowManager.addView(row, lp);
            view = row;
        } catch (Exception ignored) {
            view = null;
        }
    }

    void dismiss() {
        if (view == null) return;
        View old = view;
        view = null;
        try {
            windowManager.removeViewImmediate(old);
        } catch (Exception ignored) {}
    }

    private LinearLayout.LayoutParams itemParams() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.setMargins(0, dp(2), 0, dp(2));
        return lp;
    }

    private IconButton iconButton(int icon) {
        IconButton b = new IconButton(context);
        b.setIcon(icon);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(230, 30, 41, 59));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.argb(90, 100, 116, 139));
        b.setBackground(bg);
        return b;
    }

    private int dp(float value) {
        return Math.round(
                value * context.getResources().getDisplayMetrics().density);
    }

    private static final class IconButton extends View {
        private int icon = ICON_CALL;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconButton(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setIcon(int value) {
            icon = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            paint.setColor(icon == ICON_HANGUP
                    ? Color.rgb(251, 113, 133)
                    : icon == ICON_INTERRUPT
                    ? Color.rgb(250, 204, 21)
                    : Color.WHITE);
            paint.setStrokeWidth(2.2f * d);
            paint.setStyle(Paint.Style.STROKE);

            if (icon == ICON_CALL) {
                // Simple handset.
                RectF arc = new RectF(
                        cx - 8*d, cy - 8*d,
                        cx + 8*d, cy + 8*d);
                canvas.drawArc(arc, 135, 90, false, paint);
                canvas.drawLine(cx - 6*d, cy + 5*d, cx - 9*d, cy + 8*d, paint);
                canvas.drawLine(cx + 6*d, cy - 5*d, cx + 9*d, cy - 8*d, paint);
            } else if (icon == ICON_HANGUP) {
                // Downward handset / hangup.
                RectF arc = new RectF(
                        cx - 9*d, cy - 2*d,
                        cx + 9*d, cy + 12*d);
                canvas.drawArc(arc, 205, 130, false, paint);
                canvas.drawLine(cx - 7*d, cy + 3*d, cx - 10*d, cy + 1*d, paint);
                canvas.drawLine(cx + 7*d, cy + 3*d, cx + 10*d, cy + 1*d, paint);
            } else if (icon == ICON_CONSOLE) {
                // Three control sliders.
                canvas.drawLine(cx - 9*d, cy - 6*d, cx + 9*d, cy - 6*d, paint);
                canvas.drawLine(cx - 9*d, cy,       cx + 9*d, cy,       paint);
                canvas.drawLine(cx - 9*d, cy + 6*d, cx + 9*d, cy + 6*d, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - 3*d, cy - 6*d, 2.4f*d, paint);
                canvas.drawCircle(cx + 4*d, cy,       2.4f*d, paint);
                canvas.drawCircle(cx - 1*d, cy + 6*d, 2.4f*d, paint);
            } else if (icon == ICON_INTERRUPT) {
                // Stop square: explicit "interrupt", not hangup.
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(
                        new RectF(cx - 6*d, cy - 6*d, cx + 6*d, cy + 6*d),
                        2*d, 2*d, paint);
            }
        }
    }
}
