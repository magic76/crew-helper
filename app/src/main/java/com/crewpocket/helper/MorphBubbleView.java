package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact morphing shell for the floating Crew orb.
 *
 * The window width is still controlled by FloatingBubbleManager. This view only
 * owns child ordering, pill styling, and the short status label so the existing
 * FluidBubbleView can keep rendering all state-specific logo animation.
 */
final class MorphBubbleView extends LinearLayout {
    private final FluidBubbleView orbView;
    private final TextView statusView;
    private final int orbSizePx;
    private boolean dockOnLeft = true;

    MorphBubbleView(Context context, FluidBubbleView orbView, int orbSizePx) {
        super(context);
        this.orbView = orbView;
        this.orbSizePx = orbSizePx;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(16));

        statusView = new TextView(context);
        statusView.setSingleLine(true);
        statusView.setTextSize(12.5f);
        statusView.setTextColor(Color.parseColor("#F8FAFC"));
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(10), 0, dp(14), 0);
        statusView.setAlpha(0f);
        statusView.setVisibility(View.GONE);

        rebuildChildren();
    }

    void setDockOnLeft(boolean onLeft) {
        if (dockOnLeft == onLeft && getChildCount() == 2) return;
        dockOnLeft = onLeft;
        rebuildChildren();
    }

    boolean isDockOnLeft() {
        return dockOnLeft;
    }

    void showStatus(String text, int accentColor) {
        String resolved = text == null ? "" : text.trim();
        if (resolved.isEmpty()) {
            hideStatus();
            return;
        }

        statusView.setText(resolved);
        statusView.setVisibility(View.VISIBLE);
        statusView.animate().cancel();
        statusView.setAlpha(1f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(236, 15, 23, 42));
        bg.setCornerRadius(orbSizePx / 2f);
        bg.setStroke(dp(1), withAlpha(accentColor, 210));
        setBackground(bg);
    }

    void beginHideStatus() {
        if (statusView.getVisibility() != View.VISIBLE) return;
        statusView.animate()
                .alpha(0f)
                .setDuration(100L)
                .start();
    }

    void hideStatus() {
        statusView.animate().cancel();
        statusView.setAlpha(0f);
        statusView.setVisibility(View.GONE);
        statusView.setText("");
        setBackground(null);
    }

    String statusText() {
        CharSequence text = statusView.getText();
        return text == null ? "" : text.toString();
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
