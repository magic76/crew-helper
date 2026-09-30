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

/** One-line companion chip for the current concrete phone action. */
final class BubbleActionChipView extends LinearLayout {
    enum Tone {
        NORMAL,
        ATTENTION,
        SUCCESS,
        ERROR
    }

    private final TextView iconView;
    private final TextView labelView;
    private String currentLabel = "";

    BubbleActionChipView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), 0, dp(12), 0);
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(12));

        iconView = new TextView(context);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTextSize(13f);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);
        addView(
                iconView,
                new LinearLayout.LayoutParams(dp(24), dp(32)));

        labelView = new TextView(context);
        labelView.setSingleLine(true);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setTextSize(12f);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setTextColor(Color.parseColor("#F8FAFC"));
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        addView(
                labelView,
                new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        dp(34)));
    }

    void setAction(
            String label,
            BubbleActionChipPolicy.Kind kind,
            Tone tone) {
        final String next = label == null ? "" : label.trim();
        final BubbleActionChipPolicy.Kind resolvedKind =
                kind == null
                        ? BubbleActionChipPolicy.Kind.GENERIC
                        : kind;
        final Tone resolvedTone = tone == null ? Tone.NORMAL : tone;

        applyBackground(resolvedKind, resolvedTone);
        iconView.setText(glyph(resolvedKind, resolvedTone));
        iconView.setTextColor(accent(resolvedKind, resolvedTone));

        if (next.equals(currentLabel)) {
            labelView.setText(next);
            return;
        }

        currentLabel = next;
        if (getVisibility() == View.VISIBLE && labelView.getAlpha() > 0.8f) {
            labelView.animate().cancel();
            iconView.animate().cancel();
            labelView.animate()
                    .alpha(0f)
                    .translationY(dp(2))
                    .setDuration(55L)
                    .withEndAction(() -> {
                        labelView.setText(next);
                        labelView.setTranslationY(-dp(2));
                        labelView.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(105L)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    })
                    .start();
            iconView.setAlpha(0.55f);
            iconView.animate()
                    .alpha(1f)
                    .setDuration(120L)
                    .start();
        } else {
            labelView.setText(next);
            labelView.setAlpha(1f);
            labelView.setTranslationY(0f);
        }
        setContentDescription(next);
    }

    int desiredWidthPx(int maxWidthPx) {
        int textWidth = Math.round(
                labelView.getPaint().measureText(
                        currentLabel == null ? "" : currentLabel));
        int natural = dp(8) + dp(24) + textWidth + dp(12);
        return Math.max(dp(88), Math.min(maxWidthPx, natural));
    }

    private void applyBackground(
            BubbleActionChipPolicy.Kind kind,
            Tone tone) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Color.argb(242, 15, 23, 42));
        bg.setStroke(dp(1), accent(kind, tone));
        setBackground(bg);
    }

    private int accent(
            BubbleActionChipPolicy.Kind kind,
            Tone tone) {
        if (tone == Tone.ERROR) return Color.parseColor("#FB7185");
        if (tone == Tone.SUCCESS) return Color.parseColor("#34D399");
        if (tone == Tone.ATTENTION) return Color.parseColor("#FBBF24");

        switch (kind) {
            case SEARCH:
                return Color.parseColor("#60A5FA");
            case OPEN:
                return Color.parseColor("#818CF8");
            case TAP:
                return Color.parseColor("#67E8F9");
            case SWIPE:
                return Color.parseColor("#22D3EE");
            case TYPE:
                return Color.parseColor("#A78BFA");
            case WAIT:
                return Color.parseColor("#94A3B8");
            case THINK:
                return Color.parseColor("#C084FC");
            case MEDIA:
                return Color.parseColor("#5EEAD4");
            default:
                return Color.parseColor("#64748B");
        }
    }

    private String glyph(
            BubbleActionChipPolicy.Kind kind,
            Tone tone) {
        if (tone == Tone.ERROR) return "!";
        if (tone == Tone.SUCCESS) return "✓";
        if (tone == Tone.ATTENTION) return "!";
        switch (kind) {
            case SEARCH:
                return "⌕";
            case OPEN:
                return "↗";
            case TAP:
                return "•";
            case SWIPE:
                return "↔";
            case TYPE:
                return "T";
            case WAIT:
                return "…";
            case THINK:
                return "✦";
            case MEDIA:
                return "▶";
            default:
                return "•";
        }
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
