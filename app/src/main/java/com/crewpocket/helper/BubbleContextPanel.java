package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

final class BubbleContextPanel extends LinearLayout {
    interface ChoiceListener {
        void onChoice(String elementId);
        void onCancel();
    }

    static final int MODE_IDLE = 0;
    static final int MODE_LISTENING = 1;
    static final int MODE_THINKING = 2;
    static final int MODE_SPEAKING = 3;
    static final int MODE_CHOICE = 4;
    static final int MODE_ERROR = 5;

    private final Context context;
    private final TextView statusText;
    private final TextView moreButton;
    private final TextView closeButton;
    private final TextView transcriptText;
    private final LinearLayout choiceContainer;
    private final LinearLayout actionRow;
    private final TextView primaryButton;
    private final TextView secondaryButton;

    private boolean showingChoices;

    BubbleContextPanel(Context context) {
        super(context);
        this.context = context.getApplicationContext();

        setOrientation(VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(14));
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(18));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248, 15, 23, 42));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        setBackground(bg);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        statusText = new TextView(context);
        statusText.setTextSize(13);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setSingleLine(true);
        header.addView(statusText, new LayoutParams(0, dp(34), 1f));

        moreButton = headerAction("⋯", "更多設定");
        header.addView(moreButton, new LayoutParams(dp(38), dp(34)));

        closeButton = headerAction("×", "關閉");
        closeButton.setTextSize(22);
        header.addView(closeButton, new LayoutParams(dp(34), dp(34)));

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));

        transcriptText = new TextView(context);
        transcriptText.setText("等待對話開始…");
        transcriptText.setTextSize(14);
        transcriptText.setTextColor(Color.parseColor("#E2E8F0"));
        transcriptText.setLineSpacing(0, 1.08f);
        transcriptText.setMaxLines(4);
        transcriptText.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable transcriptBg = new GradientDrawable();
        transcriptBg.setColor(Color.parseColor("#B30B1220"));
        transcriptBg.setCornerRadius(dp(14));
        transcriptBg.setStroke(dp(1), Color.parseColor("#1E293B"));
        transcriptText.setBackground(transcriptBg);

        LayoutParams transcriptLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        transcriptLp.setMargins(0, dp(8), 0, dp(8));
        addView(transcriptText, transcriptLp);

        choiceContainer = new LinearLayout(context);
        choiceContainer.setOrientation(VERTICAL);
        choiceContainer.setVisibility(GONE);
        addView(choiceContainer, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        actionRow = new LinearLayout(context);
        actionRow.setOrientation(HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        primaryButton = actionButton();
        secondaryButton = actionButton();

        LayoutParams primaryLp = new LayoutParams(0, dp(44), 1f);
        LayoutParams secondaryLp = new LayoutParams(0, dp(44), 1f);
        secondaryLp.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(primaryButton, primaryLp);
        actionRow.addView(secondaryButton, secondaryLp);
        addView(actionRow, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        setStatus("待命", MODE_IDLE);
        setSecondaryAction("", null, false);
    }

    View dragHandle() {
        return statusText;
    }

    void setMoreClickListener(OnClickListener listener) {
        moreButton.setOnClickListener(listener);
    }

    void setCloseClickListener(OnClickListener listener) {
        closeButton.setOnClickListener(listener);
    }

    void setStatus(String status, int mode) {
        String value = status == null || status.trim().isEmpty() ? "待命" : status.trim();
        int color;
        switch (mode) {
            case MODE_LISTENING:
                color = Color.parseColor("#5EEAD4");
                break;
            case MODE_THINKING:
                color = Color.parseColor("#A5B4FC");
                break;
            case MODE_SPEAKING:
                color = Color.parseColor("#67E8F9");
                break;
            case MODE_CHOICE:
                color = Color.parseColor("#FCD34D");
                break;
            case MODE_ERROR:
                color = Color.parseColor("#FDA4AF");
                break;
            case MODE_IDLE:
            default:
                color = Color.parseColor("#CBD5E1");
                break;
        }
        statusText.setText("●  " + value);
        statusText.setTextColor(color);
    }

    void setTranscript(String transcript) {
        if (showingChoices) return;
        String value = transcript == null || transcript.trim().isEmpty()
                ? "等待對話開始…" : transcript.trim();
        transcriptText.setText(value);
    }

    boolean isShowingChoices() {
        return showingChoices;
    }

    void setPrimaryAction(String label, OnClickListener listener, boolean danger) {
        applyAction(primaryButton, label, listener, danger, true);
    }

    void setSecondaryAction(String label, OnClickListener listener, boolean danger) {
        applyAction(secondaryButton, label, listener, danger, false);
    }

    void setChoices(String title,
                    List<PendingUiChoice.Option> options,
                    final ChoiceListener listener) {
        showingChoices = true;
        setStatus("需要你的選擇", MODE_CHOICE);
        transcriptText.setText(title == null || title.trim().isEmpty()
                ? "請選擇下一步" : title.trim());

        choiceContainer.removeAllViews();
        choiceContainer.setVisibility(VISIBLE);
        actionRow.setVisibility(GONE);

        if (options != null) {
            for (int i = 0; i < options.size() && i < 4; i++) {
                final PendingUiChoice.Option option = options.get(i);
                TextView choice = new TextView(context);
                choice.setText((i + 1) + ". " + option.label);
                choice.setTextSize(14);
                choice.setTextColor(Color.parseColor("#E0F2FE"));
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setPadding(dp(12), 0, dp(12), 0);
                choice.setMinHeight(dp(46));
                choice.setContentDescription("選擇 " + (i + 1) + "：" + option.label);

                GradientDrawable choiceBg = new GradientDrawable();
                choiceBg.setColor(Color.parseColor("#172554"));
                choiceBg.setCornerRadius(dp(12));
                choiceBg.setStroke(dp(1), Color.parseColor("#1D4ED8"));
                choice.setBackground(choiceBg);

                choice.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        if (listener != null) listener.onChoice(option.elementId);
                    }
                });

                LayoutParams lp = new LayoutParams(
                        LayoutParams.MATCH_PARENT, dp(46));
                lp.setMargins(0, dp(4), 0, 0);
                choiceContainer.addView(choice, lp);
            }
        }

        TextView cancel = new TextView(context);
        cancel.setText("取消");
        cancel.setTextSize(12);
        cancel.setTextColor(Color.parseColor("#CBD5E1"));
        cancel.setGravity(Gravity.CENTER);
        cancel.setMinHeight(dp(38));
        cancel.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onCancel();
            }
        });
        LayoutParams cancelLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(38));
        cancelLp.setMargins(0, dp(4), 0, 0);
        choiceContainer.addView(cancel, cancelLp);
    }

    void clearChoices() {
        if (!showingChoices) return;
        showingChoices = false;
        choiceContainer.removeAllViews();
        choiceContainer.setVisibility(GONE);
        actionRow.setVisibility(VISIBLE);
    }

    private TextView headerAction(String label, String description) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(18);
        view.setTextColor(Color.parseColor("#94A3B8"));
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView actionButton() {
        TextView button = new TextView(context);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void applyAction(TextView button,
                             String label,
                             OnClickListener listener,
                             boolean danger,
                             boolean primary) {
        if (label == null || label.trim().isEmpty()) {
            button.setVisibility(GONE);
            button.setOnClickListener(null);
            return;
        }

        button.setVisibility(VISIBLE);
        button.setText(label);
        button.setOnClickListener(listener);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (danger) {
            bg.setColor(Color.parseColor("#881337"));
            bg.setStroke(dp(1), Color.parseColor("#E11D48"));
            button.setTextColor(Color.parseColor("#FECDD3"));
        } else if (primary) {
            bg.setColor(Color.parseColor("#0F766E"));
            bg.setStroke(dp(1), Color.parseColor("#2DD4BF"));
            button.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.parseColor("#1E293B"));
            bg.setStroke(dp(1), Color.parseColor("#475569"));
            button.setTextColor(Color.parseColor("#CBD5E1"));
        }
        button.setBackground(bg);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
