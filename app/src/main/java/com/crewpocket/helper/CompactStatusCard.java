package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small reusable status UI for overlay messages. Intended to replace oversized
 * transient dialogs for "working / learned / need confirmation" states.
 */
final class CompactStatusCard extends LinearLayout {
    private final TextView title;
    private final TextView detail;
    private final TextView collapse;

    CompactStatusCard(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(8), dp(10), dp(8));
        setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(242, 15, 23, 42));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(130, 148, 163, 184));
        setBackground(bg);
        setElevation(dp(8));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setMaxLines(1);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        collapse = new TextView(context);
        collapse.setText("—");
        collapse.setTextColor(Color.parseColor("#CBD5E1"));
        collapse.setTextSize(18);
        collapse.setGravity(Gravity.CENTER);
        collapse.setPadding(dp(8), 0, dp(4), 0);
        top.addView(collapse, new LinearLayout.LayoutParams(dp(36), dp(32)));
        addView(top, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        detail = new TextView(context);
        detail.setTextColor(Color.parseColor("#CBD5E1"));
        detail.setTextSize(12);
        detail.setMaxLines(2);
        detail.setPadding(0, dp(3), 0, 0);
        addView(detail, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void setContent(String titleText, String detailText) {
        title.setText(titleText == null ? "" : titleText);
        detail.setText(detailText == null ? "" : detailText);
        detail.setVisibility(detailText == null || detailText.isEmpty() ? GONE : VISIBLE);
    }

    void setCollapsed(boolean collapsed) {
        detail.setVisibility(collapsed ? GONE : VISIBLE);
        collapse.setText(collapsed ? "+" : "—");
    }

    void setOnCollapseClickListener(View.OnClickListener listener) {
        collapse.setOnClickListener(listener);
    }

    View dragHandle() {
        return title;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
