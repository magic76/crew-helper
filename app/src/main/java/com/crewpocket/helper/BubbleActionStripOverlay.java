package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 0085 compact system-assistant controls shown beside the 48dp Crew bubble. */
final class BubbleActionStripOverlay extends LinearLayout {
    interface Actions {
        void onPrimaryMic();
        void onSendScreen();
        void onSendCamera();
        void onStop();
        void onEndCall();
        void onOpenConsole();
    }

    private static final int ICON_MIC_ACTIVE = 1;
    private static final int ICON_MIC_MUTED = 2;
    private static final int ICON_SCREEN = 3;
    private static final int ICON_CAMERA = 4;
    private static final int ICON_STOP = 5;
    private static final int ICON_MORE = 6;

    private final Context context;
    private boolean showing;
    private Actions actions;
    private String status = "";

    BubbleActionStripOverlay(Context context) {
        super(context);
        this.context = context.getApplicationContext();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(246, 58, 58, 60));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#66717176"));
        setBackground(bg);
        setElevation(dp(16));
    }

    boolean isShowing() {
        return showing && getVisibility() == VISIBLE;
    }

    int desiredWidthPx() { return dp(276); }
    int desiredHeightPx() { return dp(108); }

    void show(Actions nextActions, String nextStatus) {
        actions = nextActions;
        status = cleanStatus(nextStatus);
        showing = true;
        rebuild();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setScaleX(0.97f);
        setScaleY(0.97f);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120L).start();
    }

    void refresh(Actions nextActions, String nextStatus) {
        if (!isShowing()) return;
        actions = nextActions;
        status = cleanStatus(nextStatus);
        rebuild();
    }

    void dismiss() {
        showing = false;
        animate().cancel();
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setVisibility(GONE);
        removeAllViews();
        actions = null;
    }

    private void rebuild() {
        removeAllViews();

        final boolean live = NativeLiveService.isActive();
        final boolean muted = live && NativeLiveService.isAgentMuted();
        final boolean taskActive = live && NativeLiveService.hasActiveAgentTask();
        final boolean speaking = live && NativeLiveService.isAiSpeaking();

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(10);
        dot.setGravity(Gravity.CENTER);
        dot.setTextColor(statusColor(live, muted, taskActive, speaking));
        header.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(28)));

        TextView statusText = new TextView(context);
        statusText.setText(status.isEmpty() ? (live ? "正在聽…" : "待命") : status);
        statusText.setTextSize(12.5f);
        statusText.setTextColor(Color.parseColor("#F4F4F5"));
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(statusText, new LinearLayout.LayoutParams(0, dp(28), 1f));

        IconButton more = iconButton(ICON_MORE, true);
        more.setContentDescription("更多控制");
        more.setOnClickListener(v -> { if (actions != null) actions.onOpenConsole(); });
        header.addView(more, new LinearLayout.LayoutParams(dp(34), dp(28)));
        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30)));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        ActionCell mic = actionCell(
                muted ? ICON_MIC_MUTED : ICON_MIC_ACTIVE,
                muted ? "取消靜音" : (live ? "麥克風" : "開始"),
                true);
        mic.setContentDescription(live ? (muted ? "取消麥克風靜音" : "麥克風靜音") : "開始 Gemini Live");
        mic.setOnClickListener(v -> { if (actions != null) actions.onPrimaryMic(); });
        row.addView(mic, weightedCellParams());

        ActionCell screen = actionCell(ICON_SCREEN, "畫面", live);
        screen.setContentDescription("傳送目前畫面給 Gemini");
        screen.setOnClickListener(v -> {
            if (actions != null && NativeLiveService.isActive()) actions.onSendScreen();
        });
        row.addView(screen, weightedCellParams());

        ActionCell camera = actionCell(ICON_CAMERA, "相機", live);
        camera.setContentDescription("拍照給 Gemini");
        camera.setOnClickListener(v -> {
            if (actions != null && NativeLiveService.isActive()) actions.onSendCamera();
        });
        row.addView(camera, weightedCellParams());

        ActionCell stop = actionCell(ICON_STOP, "停止", live);
        stop.setContentDescription(taskActive || speaking
                ? "停止目前任務；長按結束 Live" : "長按結束 Live");
        stop.setOnClickListener(v -> {
            if (actions != null && NativeLiveService.isActive()) actions.onStop();
        });
        stop.setOnLongClickListener(v -> {
            if (actions != null && NativeLiveService.isActive()) {
                actions.onEndCall();
                return true;
            }
            return false;
        });
        row.addView(stop, weightedCellParams());

        LinearLayout.LayoutParams rowLp =
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(60));
        rowLp.setMargins(0, dp(4), 0, 0);
        addView(row, rowLp);
    }

    private LinearLayout.LayoutParams weightedCellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private ActionCell actionCell(int icon, String label, boolean enabled) {
        ActionCell cell = new ActionCell(context);
        cell.bind(icon, label, enabled);
        return cell;
    }

    private IconButton iconButton(int icon, boolean enabled) {
        IconButton button = new IconButton(context);
        button.setIcon(icon);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        return button;
    }

    private int statusColor(boolean live, boolean muted, boolean taskActive, boolean speaking) {
        String lower = status.toLowerCase();
        if (lower.contains("失敗") || lower.contains("錯誤") || lower.contains("無法")) {
            return Color.parseColor("#FB7185");
        }
        if (taskActive) return Color.parseColor("#FBBF24");
        if (speaking) return Color.parseColor("#FCD34D");
        if (muted) return Color.parseColor("#FDA4AF");
        if (live) return Color.parseColor("#5EEAD4");
        return Color.parseColor("#A1A1AA");
    }

    private String cleanStatus(String value) {
        String out = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (out.length() > 52) out = out.substring(0, 51) + "…";
        return out;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class ActionCell extends LinearLayout {
        private final IconButton icon = new IconButton(context);
        private final TextView label = new TextView(context);

        ActionCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(2), 0, dp(2), 0);
            addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            label.setTextSize(9.5f);
            label.setTextColor(Color.parseColor("#D4D4D8"));
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            addView(label, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(18)));
        }

        void bind(int iconType, String text, boolean enabled) {
            icon.setIcon(iconType);
            label.setText(text);
            setEnabled(enabled);
            icon.setEnabled(enabled);
            label.setEnabled(enabled);
            setClickable(enabled);
            setAlpha(enabled ? 1f : 0.35f);
        }
    }

    private static final class IconButton extends View {
        private int icon = ICON_MIC_ACTIVE;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconButton(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(false);
            setFocusable(false);
        }

        void setIcon(int value) { icon = value; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            int color;
            if (icon == ICON_MIC_MUTED || icon == ICON_STOP) color = Color.parseColor("#FDA4AF");
            else if (icon == ICON_MIC_ACTIVE) color = Color.parseColor("#5EEAD4");
            else if (icon == ICON_SCREEN) color = Color.parseColor("#93C5FD");
            else if (icon == ICON_CAMERA) color = Color.parseColor("#C4B5FD");
            else color = Color.parseColor("#D4D4D8");

            paint.setColor(color);
            paint.setStrokeWidth(2f * d);
            paint.setStyle(Paint.Style.STROKE);

            if (icon == ICON_MIC_ACTIVE || icon == ICON_MIC_MUTED) {
                RectF cap = new RectF(cx - 3.3f*d, cy - 8*d, cx + 3.3f*d, cy + 1*d);
                canvas.drawRoundRect(cap, 3.3f*d, 3.3f*d, paint);
                RectF cradle = new RectF(cx - 6.1f*d, cy - 4*d, cx + 6.1f*d, cy + 4*d);
                canvas.drawArc(cradle, 0, 180, false, paint);
                canvas.drawLine(cx, cy + 4*d, cx, cy + 7*d, paint);
                canvas.drawLine(cx - 4*d, cy + 7*d, cx + 4*d, cy + 7*d, paint);
                if (icon == ICON_MIC_MUTED) {
                    canvas.drawLine(cx - 8*d, cy + 8*d, cx + 8*d, cy - 8*d, paint);
                }
            } else if (icon == ICON_SCREEN) {
                RectF screen = new RectF(cx - 10*d, cy - 7*d, cx + 10*d, cy + 4*d);
                canvas.drawRoundRect(screen, 2*d, 2*d, paint);
                canvas.drawLine(cx, cy + 4*d, cx, cy + 8*d, paint);
                canvas.drawLine(cx - 5*d, cy + 8*d, cx + 5*d, cy + 8*d, paint);
            } else if (icon == ICON_CAMERA) {
                RectF body = new RectF(cx - 9*d, cy - 6*d, cx + 9*d, cy + 7*d);
                canvas.drawRoundRect(body, 2.5f*d, 2.5f*d, paint);
                RectF lens = new RectF(cx - 4*d, cy - 4*d, cx + 4*d, cy + 4*d);
                canvas.drawOval(lens, paint);
                canvas.drawLine(cx - 5*d, cy - 6*d, cx - 2*d, cy - 9*d, paint);
                canvas.drawLine(cx - 2*d, cy - 9*d, cx + 3*d, cy - 9*d, paint);
                canvas.drawLine(cx + 3*d, cy - 9*d, cx + 5*d, cy - 6*d, paint);
            } else if (icon == ICON_STOP) {
                paint.setStyle(Paint.Style.FILL);
                RectF stop = new RectF(cx - 6*d, cy - 6*d, cx + 6*d, cy + 6*d);
                canvas.drawRoundRect(stop, 2*d, 2*d, paint);
            } else if (icon == ICON_MORE) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - 6*d, cy, 1.7f*d, paint);
                canvas.drawCircle(cx, cy, 1.7f*d, paint);
                canvas.drawCircle(cx + 6*d, cy, 1.7f*d, paint);
            }
        }
    }
}
