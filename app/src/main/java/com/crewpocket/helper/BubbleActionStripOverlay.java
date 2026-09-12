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
import android.widget.TextView;

/**
 * 0086 vertical Bubble Mini Console.
 *
 * Controls stay in a narrow vertical rail so the assistant occupies less
 * horizontal screen space. A slim side tag communicates the current state.
 */
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
    private boolean bubbleOnLeft = true;

    BubbleActionStripOverlay(Context context) {
        super(context);
        this.context = context.getApplicationContext();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.TOP);
        setPadding(0, 0, 0, 0);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);
        setBackgroundColor(Color.TRANSPARENT);
        setElevation(dp(16));
    }

    boolean isShowing() {
        return showing && getVisibility() == VISIBLE;
    }

    int desiredWidthPx() { return dp(84); }
    int desiredHeightPx() { return dp(270); }

    void setBubbleOnLeft(boolean value) {
        if (bubbleOnLeft == value) return;
        bubbleOnLeft = value;
        if (showing) rebuild();
    }

    void show(Actions nextActions, String nextStatus) {
        actions = nextActions;
        status = cleanStatus(nextStatus);
        showing = true;
        rebuild();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setScaleX(0.96f);
        setScaleY(0.96f);
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

        LinearLayout rail = buildRail(live, muted, taskActive, speaking);
        TextView tag = buildStatusTag(live, muted, taskActive, speaking);

        LinearLayout.LayoutParams railLp =
                new LinearLayout.LayoutParams(dp(58), LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams tagLp =
                new LinearLayout.LayoutParams(dp(22), dp(58));
        tagLp.topMargin = dp(8);

        if (bubbleOnLeft) {
            addView(rail, railLp);
            tagLp.leftMargin = dp(4);
            addView(tag, tagLp);
        } else {
            tagLp.rightMargin = dp(4);
            addView(tag, tagLp);
            addView(rail, railLp);
        }
    }

    private LinearLayout buildRail(boolean live,
                                   boolean muted,
                                   boolean taskActive,
                                   boolean speaking) {
        LinearLayout rail = new LinearLayout(context);
        rail.setOrientation(VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(6), dp(8), dp(6), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(246, 58, 58, 60));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#66717176"));
        rail.setBackground(bg);
        rail.setElevation(dp(16));

        ActionCell mic = actionCell(
                muted ? ICON_MIC_MUTED : ICON_MIC_ACTIVE,
                muted ? "解除" : (live ? "麥克風" : "開始"),
                true);
        mic.setContentDescription(
                live ? (muted ? "取消麥克風靜音" : "麥克風靜音")
                        : "開始 Gemini Live");
        mic.setOnClickListener(v -> { if (actions != null) actions.onPrimaryMic(); });
        rail.addView(mic, cellParams());

        ActionCell screen = actionCell(ICON_SCREEN, "畫面", live);
        screen.setContentDescription("傳送目前畫面給 Gemini");
        screen.setOnClickListener(v -> {
            if (actions != null && NativeLiveService.isActive()) actions.onSendScreen();
        });
        rail.addView(screen, cellParams());

        ActionCell camera = actionCell(ICON_CAMERA, "相機", live);
        camera.setContentDescription("拍照給 Gemini");
        camera.setOnClickListener(v -> {
            if (actions != null && NativeLiveService.isActive()) actions.onSendCamera();
        });
        rail.addView(camera, cellParams());

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
        rail.addView(stop, cellParams());

        ActionCell more = actionCell(ICON_MORE, "更多", true);
        more.setContentDescription("更多控制");
        more.setOnClickListener(v -> { if (actions != null) actions.onOpenConsole(); });
        rail.addView(more, cellParams());

        return rail;
    }

    private TextView buildStatusTag(boolean live,
                                    boolean muted,
                                    boolean taskActive,
                                    boolean speaking) {
        TextView tag = new TextView(context);
        tag.setGravity(Gravity.CENTER);
        tag.setTextSize(9.5f);
        tag.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tag.setText(statusTag(live, muted, taskActive, speaking));
        tag.setTextColor(statusColor(live, muted, taskActive, speaking));
        tag.setLineSpacing(0f, 0.92f);
        tag.setPadding(0, dp(4), 0, dp(4));
        tag.setContentDescription(
                status.isEmpty() ? statusTagSpoken(live, muted, taskActive, speaking) : status);

        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(Color.argb(236, 39, 39, 42));
        tagBg.setCornerRadius(dp(11));
        tagBg.setStroke(dp(1), withAlpha(statusColor(live, muted, taskActive, speaking), 150));
        tag.setBackground(tagBg);
        return tag;
    }

    private String statusTag(boolean live,
                             boolean muted,
                             boolean taskActive,
                             boolean speaking) {
        String lower = status.toLowerCase();
        if (lower.contains("失敗") || lower.contains("錯誤") || lower.contains("無法")) return "錯\n誤";
        if (taskActive) return "執\n行";
        if (speaking) return "回\n覆";
        if (muted) return "靜\n音";
        if (lower.contains("連線")) return "連\n線";
        if (live) return "聆\n聽";
        return "待\n命";
    }

    private String statusTagSpoken(boolean live,
                                   boolean muted,
                                   boolean taskActive,
                                   boolean speaking) {
        String lower = status.toLowerCase();
        if (lower.contains("失敗") || lower.contains("錯誤") || lower.contains("無法")) return "錯誤";
        if (taskActive) return "正在執行任務";
        if (speaking) return "Gemini 正在回覆";
        if (muted) return "麥克風已靜音";
        if (lower.contains("連線")) return "正在連線";
        if (live) return "正在聆聽";
        return "待命";
    }

    private int statusColor(boolean live,
                            boolean muted,
                            boolean taskActive,
                            boolean speaking) {
        String lower = status.toLowerCase();
        if (lower.contains("失敗") || lower.contains("錯誤") || lower.contains("無法")) return Color.parseColor("#FB7185");
        if (taskActive) return Color.parseColor("#FBBF24");
        if (speaking) return Color.parseColor("#FCD34D");
        if (muted) return Color.parseColor("#FDA4AF");
        if (lower.contains("連線")) return Color.parseColor("#93C5FD");
        if (live) return Color.parseColor("#5EEAD4");
        return Color.parseColor("#A1A1AA");
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(48));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(1), 0, dp(1));
        return lp;
    }

    private ActionCell actionCell(int icon, String label, boolean enabled) {
        ActionCell cell = new ActionCell(context);
        cell.bind(icon, label, enabled);
        return cell;
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
            setPadding(dp(1), dp(1), dp(1), 0);
            addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            label.setTextSize(8.5f);
            label.setTextColor(Color.parseColor("#D4D4D8"));
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            addView(label, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(14)));
        }

        void bind(int iconType, String text, boolean enabled) {
            icon.setIcon(iconType);
            label.setText(text);
            setEnabled(enabled);
            icon.setEnabled(enabled);
            label.setEnabled(enabled);
            setClickable(enabled);
            setAlpha(enabled ? 1f : 0.34f);

            GradientDrawable normal = new GradientDrawable();
            normal.setColor(Color.TRANSPARENT);
            normal.setCornerRadius(dp(12));
            setBackground(normal);

            setOnTouchListener((v, event) -> {
                if (!isEnabled()) return false;
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    GradientDrawable pressed = new GradientDrawable();
                    pressed.setColor(Color.argb(54, 255, 255, 255));
                    pressed.setCornerRadius(dp(12));
                    setBackground(pressed);
                } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    GradientDrawable reset = new GradientDrawable();
                    reset.setColor(Color.TRANSPARENT);
                    reset.setCornerRadius(dp(12));
                    setBackground(reset);
                }
                return false;
            });
        }
    }

    /** Keep the 0085 Canvas icons so they stay crisp at Android densities. */
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
                if (icon == ICON_MIC_MUTED) canvas.drawLine(cx - 8*d, cy + 8*d, cx + 8*d, cy - 8*d, paint);
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
