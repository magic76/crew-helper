package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact morphing shell for the floating Crew orb.
 *
 * The shell deliberately sizes itself from the current status text instead of
 * using one fixed capsule width. FluidBubbleView remains responsible for the
 * logo animation; this class adds the glass capsule and text motion.
 */
final class MorphBubbleView extends LinearLayout {
    private final FluidBubbleView orbView;
    private final TextView statusView;
    private final int orbSizePx;
    private boolean dockOnLeft = true;
    private int currentAccentColor = Color.parseColor("#38BDF8");

    MorphBubbleView(Context context, FluidBubbleView orbView, int orbSizePx) {
        super(context);
        this.orbView = orbView;
        this.orbSizePx = orbSizePx;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(18));

        statusView = new TextView(context);
        statusView.setSingleLine(true);
        statusView.setTextSize(12.5f);
        statusView.setTextColor(Color.WHITE);
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(7), 0, dp(12), 0);
        statusView.setAlpha(0f);
        statusView.setVisibility(View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            statusView.setLetterSpacing(0.015f);
        }

        rebuildChildren();
    }

    void setDockOnLeft(boolean onLeft) {
        if (dockOnLeft == onLeft && getChildCount() == 2) return;
        dockOnLeft = onLeft;
        rebuildChildren();
        if (statusView.getVisibility() == View.VISIBLE) {
            applyGlassBackground(currentAccentColor);
        }
    }

    boolean isDockOnLeft() {
        return dockOnLeft;
    }

    /**
     * Width follows the rendered text. Short labels stay compact while longer
     * states are capped so the assistant never turns into a long toolbar.
     */
    int desiredWidthPx(String text) {
        String resolved = text == null ? "" : text.trim();
        if (resolved.isEmpty()) return orbSizePx;

        float textWidth = statusView.getPaint().measureText(resolved);
        int natural = orbSizePx + Math.round(textWidth) + dp(21);
        int minExpanded = orbSizePx + dp(34);
        int maxExpanded = dp(142);
        return Math.max(minExpanded, Math.min(maxExpanded, natural));
    }

    void showStatus(String text, int accentColor) {
        String resolved = text == null ? "" : text.trim();
        if (resolved.isEmpty()) {
            hideStatus();
            return;
        }

        currentAccentColor = accentColor;
        boolean changed = !resolved.equals(statusText());

        statusView.animate().cancel();
        statusView.setText(resolved);
        statusView.setVisibility(View.VISIBLE);
        statusView.setTextColor(Color.WHITE);
        statusView.setShadowLayer(
                dp(4),
                0f,
                0f,
                withAlpha(accentColor, 150));

        if (changed || statusView.getAlpha() < 0.99f) {
            statusView.setAlpha(0f);
            statusView.setTranslationX(
                    dockOnLeft ? -dp(8) : dp(8));
            statusView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(170L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            statusView.setAlpha(1f);
            statusView.setTranslationX(0f);
        }

        applyGlassBackground(accentColor);

        if (changed) {
            orbView.animate().cancel();
            orbView.animate()
                    .scaleX(1.055f)
                    .scaleY(1.055f)
                    .setDuration(85L)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            orbView.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(110L)
                                    .setInterpolator(new DecelerateInterpolator())
                                    .start();
                        }
                    })
                    .start();
        }
    }

    void beginHideStatus() {
        if (statusView.getVisibility() != View.VISIBLE) return;
        statusView.animate().cancel();
        statusView.animate()
                .alpha(0f)
                .translationX(dockOnLeft ? -dp(5) : dp(5))
                .setDuration(105L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    void hideStatus() {
        statusView.animate().cancel();
        statusView.setAlpha(0f);
        statusView.setTranslationX(0f);
        statusView.setVisibility(View.GONE);
        statusView.setText("");
        statusView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        setBackground(null);
    }

    String statusText() {
        CharSequence text = statusView.getText();
        return text == null ? "" : text.toString();
    }

    private void applyGlassBackground(int accentColor) {
        int deep = Color.argb(232, 7, 18, 36);
        int mid = withAlpha(accentColor, 118);
        int near = withAlpha(accentColor, 190);

        int[] colors = dockOnLeft
                ? new int[]{near, mid, deep}
                : new int[]{deep, mid, near};

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                colors);
        bg.setCornerRadius(orbSizePx / 2f);
        bg.setStroke(dp(1), withAlpha(accentColor, 205));
        setBackground(bg);
    }

    private void rebuildChildren() {
        removeAllViews();

        LinearLayout.LayoutParams orbLp =
                new LinearLayout.LayoutParams(orbSizePx, orbSizePx);
        LinearLayout.LayoutParams statusLp =
                new LinearLayout.LayoutParams(0, orbSizePx, 1f);

        if (dockOnLeft) {
            addView(orbView, orbLp);
            addView(statusView, statusLp);
        } else {
            addView(statusView, statusLp);
            addView(orbView, orbLp);
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
