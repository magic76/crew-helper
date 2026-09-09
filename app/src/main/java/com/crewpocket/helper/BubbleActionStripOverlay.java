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
 * 0039 Listening Core action rail.
 *
 * Idle:
 *   [Start voice] [More]
 *
 * Live:
 *   [Interrupt current action] [End voice] [More]
 *
 * Functionality is unchanged; only visual hierarchy/order is simplified.
 */
final class BubbleActionStripOverlay {
    static final int ACTION_STRIP_GAP_DP = 12;

    interface Actions {
        void onToggleCall();
        void onOpenConsole();
        void onInterrupt();
    }

    private static final int ICON_CALL = 1;
    private static final int ICON_HANGUP = 2;
    private static final int ICON_MORE = 3;
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
        if (isShowing()) dismiss();
        else show(bubbleX, bubbleY, bubbleSize, actions);
    }

    void refresh(int bubbleX, int bubbleY, int bubbleSize, Actions actions) {
        if (!isShowing()) return;
        dismiss();
        show(bubbleX, bubbleY, bubbleSize, actions);
    }

    void show(int bubbleX, int bubbleY, int bubbleSize, final Actions actions) {
        dismiss();

        final boolean live = NativeLiveService.isActive();

        LinearLayout rail = new LinearLayout(context);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(dp(6), dp(7), dp(6), dp(7));
        rail.setClipToPadding(false);
        rail.setClipChildren(false);

        GradientDrawable railBg = new GradientDrawable();
        railBg.setColor(Color.argb(188, 5, 17, 38));
        railBg.setCornerRadius(dp(28));
        railBg.setStroke(dp(1), Color.argb(92, 34, 211, 238));
        rail.setBackground(railBg);
        rail.setElevation(dp(14));

        // During Live, the most urgent deterministic action is first.
        if (live) {
            IconButton interrupt = iconButton(ICON_INTERRUPT);
            interrupt.setContentDescription("停止目前操作");
            interrupt.setOnClickListener(v -> {
                dismiss();
                if (actions != null) actions.onInterrupt();
            });
            rail.addView(interrupt, itemParams());
        }

        IconButton call = iconButton(live ? ICON_HANGUP : ICON_CALL);
        if (live) call.setContentDescription("結束語音通話");
        else call.setContentDescription("開始語音對話");
        call.setOnClickListener(v -> {
            dismiss();
            if (actions != null) actions.onToggleCall();
        });
        rail.addView(call, itemParams());

        IconButton more = iconButton(ICON_MORE);
        more.setContentDescription("更多與控制台");
        more.setOnClickListener(v -> {
            dismiss();
            if (actions != null) actions.onOpenConsole();
        });
        rail.addView(more, itemParams());

        int itemCount = live ? 3 : 2;
        int stripWidth = dp(56);
        int stripHeight = dp(14) + itemCount * dp(48);

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
        int centeredX = bubbleX + bubbleSize / 2 - stripWidth / 2;
        lp.x = Math.max(dp(6), Math.min(screenW - stripWidth - dp(6), centeredX));
        lp.y = bubbleY + bubbleSize + dp(ACTION_STRIP_GAP_DP);

        try {
            windowManager.addView(rail, lp);
            view = rail;
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
                new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    private IconButton iconButton(int icon) {
        IconButton button = new IconButton(context);
        button.setIcon(icon);

        int accent = icon == ICON_INTERRUPT
                ? Color.parseColor("#FBBF24")
                : icon == ICON_HANGUP
                ? Color.parseColor("#FB7185")
                : icon == ICON_CALL
                ? Color.parseColor("#22D3EE")
                : Color.parseColor("#818CF8");

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(238, 8, 22, 48));
        bg.setStroke(dp(icon == ICON_INTERRUPT || icon == ICON_HANGUP ? 2 : 1.5f),
                accent);
        button.setBackground(bg);
        button.setElevation(dp(5));
        return button;
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

            int color = icon == ICON_INTERRUPT
                    ? Color.parseColor("#FDE68A")
                    : icon == ICON_HANGUP
                    ? Color.parseColor("#FDA4AF")
                    : icon == ICON_CALL
                    ? Color.parseColor("#67E8F9")
                    : Color.parseColor("#C4B5FD");

            paint.setColor(color);
            paint.setStrokeWidth(2.15f * d);
            paint.setStyle(Paint.Style.STROKE);

            if (icon == ICON_CALL) {
                // Listening-core companion: a clean microphone.
                RectF cap = new RectF(
                        cx - 3.4f*d, cy - 8*d,
                        cx + 3.4f*d, cy + 1*d);
                canvas.drawRoundRect(cap, 3.4f*d, 3.4f*d, paint);
                RectF cradle = new RectF(
                        cx - 6.3f*d, cy - 4*d,
                        cx + 6.3f*d, cy + 4*d);
                canvas.drawArc(cradle, 0, 180, false, paint);
                canvas.drawLine(cx, cy + 4*d, cx, cy + 7*d, paint);
                canvas.drawLine(cx - 4*d, cy + 7*d, cx + 4*d, cy + 7*d, paint);
            } else if (icon == ICON_HANGUP) {
                // Explicit call-end X; visually distinct from interrupt square.
                canvas.drawLine(cx - 5.5f*d, cy - 5.5f*d,
                        cx + 5.5f*d, cy + 5.5f*d, paint);
                canvas.drawLine(cx + 5.5f*d, cy - 5.5f*d,
                        cx - 5.5f*d, cy + 5.5f*d, paint);
            } else if (icon == ICON_MORE) {
                // Three-dot "more": fewer visual details than the old sliders.
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - 6*d, cy, 2.2f*d, paint);
                canvas.drawCircle(cx, cy, 2.2f*d, paint);
                canvas.drawCircle(cx + 6*d, cy, 2.2f*d, paint);
            } else if (icon == ICON_INTERRUPT) {
                // Stop current Agent action, not the voice call.
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(
                        new RectF(cx - 5.5f*d, cy - 5.5f*d,
                                cx + 5.5f*d, cy + 5.5f*d),
                        1.8f*d, 1.8f*d, paint);
            }
        }
    }
}
