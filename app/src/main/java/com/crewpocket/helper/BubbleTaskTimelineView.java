package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Compact 3-step task trail shown when the floating bubble is tapped. */
final class BubbleTaskTimelineView extends LinearLayout {
    private final TextView titleView;
    private final LinearLayout rows;

    BubbleTaskTimelineView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setElevation(dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Color.argb(246, 15, 23, 42));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        setBackground(bg);

        titleView = new TextView(context);
        titleView.setTextSize(11f);
        titleView.setTextColor(Color.parseColor("#94A3B8"));
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp(5));
        addView(
                titleView,
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));

        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        addView(
                rows,
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
    }

    void setActions(
            List<String> actions,
            boolean active,
            boolean needsAttention) {
        rows.removeAllViews();
        titleView.setText(
                needsAttention
                        ? "需要你"
                        : (active ? "Crew 正在執行" : "最近動作"));

        if (actions == null || actions.isEmpty()) {
            addRow("•", "等待下一個動作", false);
            return;
        }

        int size = actions.size();
        for (int i = 0; i < size; i++) {
            boolean current = active && i == size - 1;
            String marker = current
                    ? (needsAttention ? "!" : "●")
                    : "✓";
            addRow(marker, actions.get(i), current);
        }
    }

    private void addRow(
            String marker,
            String label,
            boolean current) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView dot = new TextView(getContext());
        dot.setText(marker);
        dot.setGravity(Gravity.CENTER);
        dot.setTextSize(11.5f);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextColor(
                "!".equals(marker)
                        ? Color.parseColor("#FBBF24")
                        : (current
                                ? Color.parseColor("#22D3EE")
                                : Color.parseColor("#34D399")));
        row.addView(
                dot,
                new LinearLayout.LayoutParams(dp(22), dp(26)));

        TextView text = new TextView(getContext());
        text.setText(label == null ? "" : label);
        text.setTextSize(11.5f);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTextColor(
                current
                        ? Color.parseColor("#F8FAFC")
                        : Color.parseColor("#CBD5E1"));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setTypeface(
                Typeface.DEFAULT,
                current ? Typeface.BOLD : Typeface.NORMAL);
        row.addView(
                text,
                new LinearLayout.LayoutParams(
                        0,
                        dp(28),
                        1f));

        rows.addView(
                row,
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
