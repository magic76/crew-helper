package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;

/**
 * Short first-level bubble menu.
 *
 * Keep only three immediate actions here. Less common controls live in the
 * full console so opening the bubble never turns into a tall toolbar.
 */
final class BubbleActionStripOverlay extends LinearLayout {
    interface Actions {
        void onToggleCall();
        void onToggleMute();
        void onOpenConsole();
        void onInterrupt();
        void onTeachCurrentApp();
        void onRegionSelection();
    }

    private static final int ICON_CALL = 1;
    private static final int ICON_HANGUP = 2;
    private static final int ICON_MORE = 3;
    private static final int ICON_MIC_ACTIVE = 4;
    private static final int ICON_MIC_MUTED = 5;
    private static final int ICON_SPEAKER = 6;
    private static final int ICON_REGION = 7;

    private final Context context;
    private boolean showing = false;

    BubbleActionStripOverlay(Context context) {
        super(context);
        this.context = context.getApplicationContext();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(4), dp(5), dp(4), dp(5));
        setClipToPadding(false);
        setClipChildren(false);
        setVisibility(GONE);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Color.argb(238, 15, 23, 42));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        setBackground(bg);
    }

    boolean isShowing() {
        return showing && getVisibility() == VISIBLE;
    }

    int desiredHeightPx() {
        return dp(130);
    }

    void show(Actions actions) {
        showing = true;
        rebuild(actions);
        setVisibility(VISIBLE);
        setAlpha(0f);
        setScaleX(0.96f);
        setScaleY(0.96f);
        animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start();
    }

    void refresh(Actions actions) {
        if (!isShowing()) return;
        rebuild(actions);
    }

    void dismiss() {
        showing = false;
        animate().cancel();
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setVisibility(GONE);
        removeAllViews();
    }

    private void rebuild(final Actions actions) {
        removeAllViews();

        final boolean live = NativeLiveService.isActive();
        final boolean speaking =
                live && NativeLiveService.isAiSpeaking();
        final boolean muted =
                live && NativeLiveService.isAgentMuted();

        if (live) {
            final int primaryIcon =
                    speaking
                            ? ICON_SPEAKER
                            : (muted
                                    ? ICON_MIC_MUTED
                                    : ICON_MIC_ACTIVE);
            CrewIconView primary = iconButton(primaryIcon);
            primary.setContentDescription(
                    speaking
                            ? "打斷助理"
                            : (muted
                                    ? "取消靜音"
                                    : "麥克風靜音"));
            primary.setOnClickListener(v -> {
                if (actions == null) return;
                if (speaking) actions.onInterrupt();
                else actions.onToggleMute();
            });
            addView(primary, itemParams());

            CrewIconView end = iconButton(ICON_HANGUP);
            end.setContentDescription("結束語音通話");
            end.setOnClickListener(v -> {
                if (actions != null) actions.onToggleCall();
            });
            addView(end, itemParams());
        } else {
            CrewIconView start = iconButton(ICON_CALL);
            start.setContentDescription("開始語音對話");
            start.setOnClickListener(v -> {
                if (actions != null) actions.onToggleCall();
            });
            addView(start, itemParams());

            CrewIconView region = iconButton(ICON_REGION);
            region.setContentDescription("框選截圖");
            region.setOnClickListener(v -> {
                if (actions != null) actions.onRegionSelection();
            });
            addView(region, itemParams());
        }

        CrewIconView more = iconButton(ICON_MORE);
        more.setContentDescription("更多控制");
        more.setOnClickListener(v -> {
            if (actions != null) actions.onOpenConsole();
        });
        addView(more, itemParams());
    }

    private LinearLayout.LayoutParams itemParams() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(1), 0, dp(1));
        return lp;
    }

    private CrewIconView iconButton(int icon) {
        CrewIconView button = new CrewIconView(context);
        button.setIconScale(0.62f);

        int color;
        int fill;
        if (icon == ICON_SPEAKER) {
            color = Color.parseColor("#FCD34D");
            fill = Color.argb(62, 120, 53, 15);
            button.setIcon(CrewIcons.INTERRUPT, color);
        } else if (icon == ICON_MIC_MUTED) {
            color = Color.parseColor("#FDA4AF");
            fill = Color.argb(58, 76, 5, 25);
            button.setIcon(CrewIcons.MIC_MUTED, color);
        } else if (icon == ICON_HANGUP) {
            color = Color.parseColor("#FB7185");
            fill = Color.argb(58, 76, 5, 25);
            button.setIcon(CrewIcons.HANGUP, color);
        } else if (icon == ICON_MIC_ACTIVE) {
            color = Color.parseColor("#5EEAD4");
            fill = Color.argb(48, 19, 78, 74);
            button.setIcon(CrewIcons.MIC, color);
        } else if (icon == ICON_CALL) {
            color = Color.parseColor("#67E8F9");
            fill = Color.argb(48, 19, 78, 74);
            button.setIcon(CrewIcons.VOICE, color);
        } else if (icon == ICON_REGION) {
            color = Color.parseColor("#93C5FD");
            fill = Color.argb(48, 30, 64, 175);
            button.setIcon(CrewIcons.REGION, color);
        } else {
            color = Color.parseColor("#CBD5E1");
            fill = Color.TRANSPARENT;
            button.setIcon(CrewIcons.MORE, color);
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
}
