package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Restored compact embedded action rail.
 *
 * The Crew bubble owns this tiny first-level shortcut rail:
 * - Live inactive: Start, More
 * - Live active: Mic/Interrupt, End, More
 *
 * Full camera/screen/transcript/settings controls belong in Live Console.
 */
final class BubbleActionStripOverlay extends LinearLayout {
    static final int ACTION_STRIP_GAP_DP = 0;

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
    private boolean showing = false;

    BubbleActionStripOverlay(Context context) {
        super(context);
        this.context = context.getApplicationContext();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(4), dp(2), dp(4), dp(4));
        setClipToPadding(false);
        setClipChildren(false);
        setVisibility(GONE);
        setBackgroundColor(Color.TRANSPARENT);
    }

    boolean isShowing() {
        return showing && getVisibility() == VISIBLE;
    }

    int desiredHeightPx() {
        int itemCount = NativeLiveService.isActive() ? 3 : 2;
        return dp(6) + itemCount * dp(44);
    }

    void show(Actions actions) {
        showing = true;
        rebuild(actions);
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(120L).start();
    }

    void refresh(Actions actions) {
        if (!isShowing()) return;
        rebuild(actions);
    }

    void dismiss() {
        showing = false;
        animate().cancel();
        setAlpha(1f);
        setVisibility(GONE);
        removeAllViews();
    }

    private void rebuild(final Actions actions) {
        removeAllViews();

        final boolean live = NativeLiveService.isActive();
        final boolean speaking = live && NativeLiveService.isAiSpeaking();
        final boolean muted = live && NativeLiveService.isAgentMuted();

        if (live) {
            final int primaryIcon =
                    speaking ? ICON_SPEAKER
                            : (muted ? ICON_MIC_MUTED : ICON_MIC_ACTIVE);
            IconButton primary = iconButton(primaryIcon);
            primary.setContentDescription(
                    speaking ? "打斷助理"
                            : (muted ? "取消靜音" : "麥克風靜音"));
            primary.setOnClickListener(v -> {
                if (actions == null) return;
                if (speaking) actions.onInterrupt();
                else actions.onToggleMute();
            });
            addView(primary, itemParams());

            IconButton end = iconButton(ICON_HANGUP);
            end.setContentDescription("結束語音通話");
            end.setOnClickListener(v -> {
                if (actions != null) actions.onToggleCall();
            });
            addView(end, itemParams());
        } else {
            IconButton start = iconButton(ICON_CALL);
            start.setContentDescription("開始語音對話");
            start.setOnClickListener(v -> {
                if (actions != null) actions.onToggleCall();
            });
            addView(start, itemParams());
        }

        IconButton more = iconButton(ICON_MORE);
        more.setContentDescription("更多控制");
        more.setOnClickListener(v -> {
            if (actions != null) actions.onOpenConsole();
        });
        addView(more, itemParams());
    }

    private LinearLayout.LayoutParams itemParams() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(2), 0, dp(2));
        return lp;
    }

    private IconButton iconButton(int icon) {
        IconButton button = new IconButton(context);
        button.setIcon(icon);

        int fill;
        if (icon == ICON_SPEAKER) {
            fill = Color.argb(72, 120, 53, 15);
        } else if (icon == ICON_MIC_MUTED || icon == ICON_HANGUP) {
            fill = Color.argb(58, 76, 5, 25);
        } else if (icon == ICON_MIC_ACTIVE || icon == ICON_CALL) {
            fill = Color.argb(52, 19, 78, 74);
        } else {
            fill = Color.TRANSPARENT;
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(fill);
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
