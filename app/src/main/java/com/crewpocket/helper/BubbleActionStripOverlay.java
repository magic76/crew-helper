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
 * 0049 compact bubble action rail.
 *
 * Idle:
 *   [Start voice] [More]
 *
 * Live:
 *   [Mute / Unmute / Interrupt speaking] [End voice] [More]
 *
 * The first Live icon is stateful so the rail stays at three controls without
 * adding another floating surface.
 */
final class BubbleActionStripOverlay {
    static final int ACTION_STRIP_GAP_DP = 8;

    interface Actions {
        void onToggleCall();
        void onToggleMute();
        void onOpenConsole();
        void onInterrupt();
    }

    private static final int ICON_CALL = 1;
    private static final int ICON_HANGUP = 2;
    private static final int ICON_MORE = 3;
    private static final int ICON_MIC_ACTIVE = 4;
    private static final int ICON_MIC_MUTED = 5;
    private static final int ICON_SPEAKER = 6;

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
        final boolean speaking = live && NativeLiveService.isAiSpeaking();
        final boolean muted = live && NativeLiveService.isAgentMuted();

        LinearLayout rail = new LinearLayout(context);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(dp(4), dp(4), dp(4), dp(4));
        rail.setClipToPadding(false);
        rail.setClipChildren(false);

        GradientDrawable railBg = new GradientDrawable();
        railBg.setColor(Color.argb(224, 15, 23, 42));
        railBg.setCornerRadius(dp(18));
        railBg.setStroke(dp(1), Color.parseColor("#334155"));
        rail.setBackground(railBg);
        rail.setElevation(dp(12));

        if (live) {
            final int primaryIcon =
                    speaking ? ICON_SPEAKER : (muted ? ICON_MIC_MUTED : ICON_MIC_ACTIVE);
            IconButton primary = iconButton(primaryIcon);
            primary.setContentDescription(
                    speaking ? "打斷助理"
                            : (muted ? "取消靜音" : "麥克風靜音"));
            primary.setOnClickListener(v -> {
                dismiss();
                if (actions == null) return;
                if (speaking) actions.onInterrupt();
                else actions.onToggleMute();
            });
            rail.addView(primary, itemParams());

            IconButton end = iconButton(ICON_HANGUP);
            end.setContentDescription("結束語音通話");
            end.setOnClickListener(v -> {
                dismiss();
                if (actions != null) actions.onToggleCall();
            });
            rail.addView(end, itemParams());
        } else {
            IconButton start = iconButton(ICON_CALL);
            start.setContentDescription("開始語音對話");
            start.setOnClickListener(v -> {
                dismiss();
                if (actions != null) actions.onToggleCall();
            });
            rail.addView(start, itemParams());
        }

        IconButton more = iconButton(ICON_MORE);
        more.setContentDescription("更多設定");
        more.setOnClickListener(v -> {
            dismiss();
            if (actions != null) actions.onOpenConsole();
        });
        rail.addView(more, itemParams());

        int itemCount = live ? 3 : 2;
        int stripWidth = dp(50);
        int stripHeight = dp(8) + itemCount * dp(46);

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
        lp.x = Math.max(dp(6), Math.min(
                screenW - stripWidth - dp(6), centeredX));
        lp.y = bubbleY + bubbleSize + dp(ACTION_STRIP_GAP_DP);

        try {
            rail.setAlpha(0f);
            rail.setScaleX(0.92f);
            rail.setScaleY(0.92f);
            windowManager.addView(rail, lp);
            view = rail;
            rail.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .start();
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
        lp.setMargins(0, dp(2), 0, dp(2));
        return lp;
    }

    private IconButton iconButton(int icon) {
        IconButton button = new IconButton(context);
        button.setIcon(icon);

        int fill;
        int stroke;
        if (icon == ICON_SPEAKER) {
            fill = Color.parseColor("#4D78350F");
            stroke = Color.parseColor("#D97706");
        } else if (icon == ICON_MIC_MUTED) {
            fill = Color.parseColor("#4D4C0519");
            stroke = Color.parseColor("#BE123C");
        } else if (icon == ICON_MIC_ACTIVE) {
            fill = Color.parseColor("#40134E4A");
            stroke = Color.parseColor("#0F766E");
        } else if (icon == ICON_HANGUP) {
            fill = Color.parseColor("#404C0519");
            stroke = Color.parseColor("#9F1239");
        } else if (icon == ICON_CALL) {
            fill = Color.parseColor("#33164E63");
            stroke = Color.parseColor("#0E7490");
        } else {
            fill = Color.TRANSPARENT;
            stroke = Color.parseColor("#334155");
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(13));
        bg.setColor(fill);
        bg.setStroke(dp(1), stroke);
        button.setBackground(bg);
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

            int color = icon == ICON_SPEAKER
                    ? Color.parseColor("#FCD34D")
                    : icon == ICON_MIC_MUTED
                    ? Color.parseColor("#FDA4AF")
                    : icon == ICON_MIC_ACTIVE
                    ? Color.parseColor("#5EEAD4")
                    : icon == ICON_HANGUP
                    ? Color.parseColor("#FB7185")
                    : icon == ICON_CALL
                    ? Color.parseColor("#67E8F9")
                    : Color.parseColor("#CBD5E1");

            paint.setColor(color);
            paint.setStrokeWidth(2.05f * d);
            paint.setStyle(Paint.Style.STROKE);

            if (icon == ICON_CALL || icon == ICON_MIC_ACTIVE || icon == ICON_MIC_MUTED) {
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
                if (icon == ICON_MIC_MUTED) {
                    canvas.drawLine(cx - 8*d, cy + 8*d, cx + 8*d, cy - 8*d, paint);
                }
            } else if (icon == ICON_HANGUP) {
                canvas.drawLine(cx - 5.5f*d, cy - 5.5f*d,
                        cx + 5.5f*d, cy + 5.5f*d, paint);
                canvas.drawLine(cx + 5.5f*d, cy - 5.5f*d,
                        cx - 5.5f*d, cy + 5.5f*d, paint);
            } else if (icon == ICON_MORE) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - 6*d, cy, 1.8f*d, paint);
                canvas.drawCircle(cx, cy, 1.8f*d, paint);
                canvas.drawCircle(cx + 6*d, cy, 1.8f*d, paint);
            } else if (icon == ICON_SPEAKER) {
                android.graphics.Path speaker = new android.graphics.Path();
                speaker.moveTo(cx - 7*d, cy - 3*d);
                speaker.lineTo(cx - 4*d, cy - 3*d);
                speaker.lineTo(cx + 1*d, cy - 7*d);
                speaker.lineTo(cx + 1*d, cy + 7*d);
                speaker.lineTo(cx - 4*d, cy + 3*d);
                speaker.lineTo(cx - 7*d, cy + 3*d);
                speaker.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(speaker, paint);
                paint.setStyle(Paint.Style.STROKE);
                RectF wave1 = new RectF(
                        cx - 2*d, cy - 4*d,
                        cx + 6*d, cy + 4*d);
                canvas.drawArc(wave1, -45, 90, false, paint);
                RectF wave2 = new RectF(
                        cx - 2*d, cy - 8*d,
                        cx + 10*d, cy + 8*d);
                canvas.drawArc(wave2, -45, 90, false, paint);
            }
        }
    }
}
