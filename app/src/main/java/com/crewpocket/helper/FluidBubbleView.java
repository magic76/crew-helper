package com.crewpocket.helper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Minimal Crew orb.
 *
 * The center mark is intentionally stable. Persistent state lives on the outer
 * ring, while concrete phone actions are rendered by BubbleActionChipView.
 */
final class FluidBubbleView extends View {
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcBounds = new RectF();
    private float rotationAngle = 0f;

    private boolean isFlowing = false;
    private boolean isSuccessFlash = false;
    private float microphoneActivity = 0f;
    private boolean microphoneSending = false;
    private boolean contextReadyFlash = false;
    private int contextReadyFlashGeneration = 0;

    private BubbleTaskPhasePolicy.Phase agentPhase =
            BubbleTaskPhasePolicy.Phase.NONE;
    private boolean agentNeedsAttention = false;
    private boolean conversationWaiting = false;

    // 0 none, 1 success, 2 failure.
    private int agentResultFlash = 0;
    private int agentResultFlashGeneration = 0;

    // 0 idle, 1 listening, 2 speaking, 3 error.
    private int nativeVoiceState = 0;
    private ValueAnimator animator;

    FluidBubbleView(Context context) {
        super(context);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);

        markPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeJoin(Paint.Join.ROUND);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);

        badgePaint.setStyle(Paint.Style.FILL);

        badgeTextPaint.setStyle(Paint.Style.FILL);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimationState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimator();
    }

    private void ensureAnimator() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            rotationAngle = (Float) animation.getAnimatedValue();
            invalidate();
        });
    }

    private void stopAnimator() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    private void updateAnimationState() {
        BubbleLogoStatePolicy.Mode mode = visualMode();
        boolean animate =
                isFlowing
                        || isSuccessFlash
                        || contextReadyFlash
                        || agentResultFlash != 0
                        || mode != BubbleLogoStatePolicy.Mode.IDLE;

        if (!animate) {
            stopAnimator();
            rotationAngle = 0f;
            invalidate();
            return;
        }

        ensureAnimator();
        long duration;
        switch (mode) {
            case ACTING:
                duration = 900L;
                break;
            case THINKING:
                duration = 1_850L;
                break;
            case SPEAKING:
                duration = 1_550L;
                break;
            case LISTENING:
                duration = microphoneSending && microphoneActivity > 0.12f
                        ? 1_650L
                        : 2_500L;
                break;
            case WAITING:
                duration = 3_600L;
                break;
            case WAITING_USER:
            case STUCK:
                duration = 2_200L;
                break;
            case CONVERSATION_WAITING:
                duration = 2_900L;
                break;
            case ERROR:
                duration = 1_300L;
                break;
            default:
                duration = 2_400L;
                break;
        }

        animator.setDuration(duration);
        if (isAttachedToWindow() && !animator.isRunning()) {
            animator.start();
        }
        invalidate();
    }

    void startWaterFlow() {
        isFlowing = true;
        isSuccessFlash = false;
        updateAnimationState();
    }

    void stopWaterFlow() {
        isFlowing = false;
        isSuccessFlash = true;
        updateAnimationState();
        postDelayed(() -> {
            isSuccessFlash = false;
            updateAnimationState();
        }, 700L);
    }

    void setMicrophoneActivity(double dbfs, boolean sending) {
        float normalized = 0f;
        if (sending && dbfs > -72d) {
            normalized = (float) ((dbfs + 58d) / 40d);
            normalized = Math.max(0f, Math.min(1f, normalized));
        }
        microphoneActivity =
                microphoneActivity * 0.42f + normalized * 0.58f;
        if (!sending && microphoneActivity < 0.04f) {
            microphoneActivity = 0f;
        }
        microphoneSending = sending;
        updateAnimationState();
    }

    void flashContextReady() {
        final int generation = ++contextReadyFlashGeneration;
        contextReadyFlash = true;
        updateAnimationState();
        postDelayed(() -> {
            if (generation != contextReadyFlashGeneration) return;
            contextReadyFlash = false;
            updateAnimationState();
        }, 850L);
    }

    void setAgentWorking(boolean working) {
        setAgentPhase(
                working
                        ? BubbleTaskPhasePolicy.Phase.THINKING
                        : BubbleTaskPhasePolicy.Phase.NONE);
    }

    void setAgentPhase(BubbleTaskPhasePolicy.Phase phase) {
        BubbleTaskPhasePolicy.Phase resolved =
                phase == null
                        ? BubbleTaskPhasePolicy.Phase.NONE
                        : phase;
        if (agentPhase == resolved) return;
        agentPhase = resolved;
        if (resolved != BubbleTaskPhasePolicy.Phase.NONE) {
            agentNeedsAttention = false;
            agentResultFlash = 0;
            agentResultFlashGeneration++;
        }
        updateAnimationState();
    }

    void setAgentNeedsAttention(boolean needsAttention) {
        if (agentNeedsAttention == needsAttention) return;
        agentNeedsAttention = needsAttention;
        if (needsAttention) {
            agentPhase = BubbleTaskPhasePolicy.Phase.NONE;
            agentResultFlash = 0;
            agentResultFlashGeneration++;
        }
        updateAnimationState();
    }

    void setConversationWaiting(boolean waiting) {
        if (conversationWaiting == waiting) return;
        conversationWaiting = waiting;
        updateAnimationState();
    }

    void flashAgentResult(final boolean success) {
        agentPhase = BubbleTaskPhasePolicy.Phase.NONE;
        agentNeedsAttention = false;

        final int generation = ++agentResultFlashGeneration;
        agentResultFlash = success ? 1 : 2;
        updateAnimationState();

        postDelayed(() -> {
            if (generation != agentResultFlashGeneration) return;
            agentResultFlash = 0;
            updateAnimationState();
        }, success ? 720L : 980L);
    }

    void setNativeVoiceState(int state) {
        nativeVoiceState = state;
        if (state != 1) {
            microphoneActivity = 0f;
            microphoneSending = false;
        }
        updateAnimationState();
    }

    private BubbleLogoStatePolicy.Mode visualMode() {
        return BubbleLogoStatePolicy.resolve(
                nativeVoiceState,
                agentPhase,
                agentNeedsAttention,
                conversationWaiting);
    }

    private float wave(float cycles) {
        double radians = Math.toRadians(rotationAngle * cycles);
        return 0.5f + 0.5f * (float) Math.sin(radians);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius =
                Math.max(
                        1f,
                        Math.min(getWidth(), getHeight()) / 2f - 1.5f);
        BubbleLogoStatePolicy.Mode mode = visualMode();

        drawCrewMark(canvas, cx, cy, radius);
        drawState(canvas, cx, cy, radius, mode);
        drawTransientResult(canvas, cx, cy, radius);
    }

    private void drawCrewMark(
            Canvas canvas,
            float cx,
            float cy,
            float radius) {
        // Stable dark core.
        bgPaint.setColor(Color.parseColor("#08111F"));
        bgPaint.setAlpha(252);
        canvas.drawCircle(cx, cy, radius * 0.82f, bgPaint);

        // Minimal Crew "C" mark: readable even at notification/icon scale.
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(Math.max(3f, radius * 0.17f));
        markPaint.setColor(Color.parseColor("#F8FAFC"));
        markPaint.setAlpha(238);
        float markRadius = radius * 0.43f;
        arcBounds.set(
                cx - markRadius,
                cy - markRadius,
                cx + markRadius,
                cy + markRadius);
        canvas.drawArc(
                arcBounds,
                44f,
                272f,
                false,
                markPaint);

        // One quiet cyan point gives Crew a recognizable signature without
        // turning the mark into another status indicator.
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(Color.parseColor("#67E8F9"));
        markPaint.setAlpha(245);
        canvas.drawCircle(
                cx + radius * 0.44f,
                cy,
                Math.max(2.1f, radius * 0.085f),
                markPaint);
    }

    private void drawState(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            BubbleLogoStatePolicy.Mode mode) {
        float pulse = wave(1f);
        float inset = Math.max(2f, radius * 0.06f);
        RectF ring = new RectF(
                inset,
                inset,
                getWidth() - inset,
                getHeight() - inset);

        ringPaint.setShader(null);
        ringPaint.setStrokeWidth(Math.max(2f, radius * 0.075f));

        switch (mode) {
            case IDLE:
                drawFullRing(canvas, ring, "#64748B", 105);
                break;

            case LISTENING:
                drawFullRing(
                        canvas,
                        ring,
                        "#38BDF8",
                        175 + Math.round(40f * pulse));
                haloPaint.setColor(Color.parseColor("#38BDF8"));
                haloPaint.setStrokeWidth(Math.max(2f, radius * 0.055f));
                haloPaint.setAlpha(
                        20 + Math.round(
                                70f * Math.max(
                                        microphoneActivity,
                                        0.10f) * pulse));
                float haloInset = Math.max(4f, radius * 0.13f);
                canvas.drawOval(
                        new RectF(
                                haloInset,
                                haloInset,
                                getWidth() - haloInset,
                                getHeight() - haloInset),
                        haloPaint);
                break;

            case THINKING:
                drawFullRing(canvas, ring, "#7C3AED", 86);
                drawMovingArc(
                        canvas,
                        ring,
                        "#C084FC",
                        215,
                        74f);
                break;

            case ACTING:
                drawFullRing(canvas, ring, "#0E7490", 72);
                drawMovingArc(
                        canvas,
                        ring,
                        "#22D3EE",
                        255,
                        100f);
                break;

            case WAITING:
                drawFullRing(
                        canvas,
                        ring,
                        "#64748B",
                        80 + Math.round(50f * pulse));
                drawDot(
                        canvas,
                        cx + radius * 0.52f,
                        cy + radius * 0.50f,
                        radius * (0.055f + 0.012f * pulse),
                        "#FBBF24",
                        190);
                break;

            case WAITING_USER:
                drawFullRing(
                        canvas,
                        ring,
                        "#F59E0B",
                        180 + Math.round(55f * pulse));
                drawAttentionBadge(canvas, radius);
                break;

            case CONVERSATION_WAITING:
                drawFullRing(
                        canvas,
                        ring,
                        "#14B8A6",
                        120 + Math.round(70f * pulse));
                drawDot(
                        canvas,
                        cx + radius * 0.50f,
                        cy + radius * 0.50f,
                        radius * (0.055f + 0.018f * pulse),
                        "#5EEAD4",
                        225);
                break;

            case SPEAKING:
                drawFullRing(
                        canvas,
                        ring,
                        "#A855F7",
                        160 + Math.round(70f * pulse));
                break;

            case STUCK:
                drawFullRing(
                        canvas,
                        ring,
                        "#F59E0B",
                        135 + Math.round(95f * pulse));
                drawAttentionBadge(canvas, radius);
                break;

            case ERROR:
                drawFullRing(
                        canvas,
                        ring,
                        "#F43F5E",
                        190 + Math.round(55f * pulse));
                break;
        }

        if (contextReadyFlash) {
            ringPaint.setStrokeWidth(Math.max(2.8f, radius * 0.095f));
            drawFullRing(canvas, ring, "#2DD4BF", 250);
        }

        if (isFlowing
                && mode == BubbleLogoStatePolicy.Mode.IDLE) {
            drawMovingArc(
                    canvas,
                    ring,
                    "#818CF8",
                    210,
                    82f);
        }
    }

    private void drawFullRing(
            Canvas canvas,
            RectF bounds,
            String color,
            int alpha) {
        ringPaint.setColor(Color.parseColor(color));
        ringPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
        canvas.drawOval(bounds, ringPaint);
    }

    private void drawMovingArc(
            Canvas canvas,
            RectF bounds,
            String color,
            int alpha,
            float sweep) {
        ringPaint.setColor(Color.parseColor(color));
        ringPaint.setAlpha(alpha);
        canvas.drawArc(
                bounds,
                rotationAngle - 90f,
                sweep,
                false,
                ringPaint);
    }

    private void drawDot(
            Canvas canvas,
            float x,
            float y,
            float radius,
            String color,
            int alpha) {
        badgePaint.setColor(Color.parseColor(color));
        badgePaint.setAlpha(alpha);
        canvas.drawCircle(x, y, Math.max(2f, radius), badgePaint);
    }

    private void drawAttentionBadge(
            Canvas canvas,
            float radius) {
        float badgeRadius = Math.max(4.2f, radius * 0.18f);
        float badgeCx = getWidth() - radius * 0.34f;
        float badgeCy = radius * 0.34f;

        badgePaint.setColor(Color.parseColor("#FBBF24"));
        badgePaint.setAlpha(255);
        canvas.drawCircle(
                badgeCx,
                badgeCy,
                badgeRadius,
                badgePaint);

        badgeTextPaint.setColor(Color.parseColor("#0F172A"));
        badgeTextPaint.setAlpha(255);
        badgeTextPaint.setTextSize(Math.max(8f, radius * 0.31f));
        Paint.FontMetrics metrics = badgeTextPaint.getFontMetrics();
        float baseline =
                badgeCy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(
                "!",
                badgeCx,
                baseline,
                badgeTextPaint);
    }

    private void drawTransientResult(
            Canvas canvas,
            float cx,
            float cy,
            float radius) {
        if (agentResultFlash == 0 && !isSuccessFlash) return;

        boolean success =
                agentResultFlash == 1
                        || (agentResultFlash == 0 && isSuccessFlash);
        int color = Color.parseColor(
                success ? "#34D399" : "#FB7185");

        ringPaint.setShader(null);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(3f, radius * 0.10f));
        ringPaint.setColor(color);
        ringPaint.setAlpha(250);
        float inset = ringPaint.getStrokeWidth();
        canvas.drawOval(
                new RectF(
                        inset,
                        inset,
                        getWidth() - inset,
                        getHeight() - inset),
                ringPaint);

        if (success) {
            markPaint.setStyle(Paint.Style.STROKE);
            markPaint.setStrokeCap(Paint.Cap.ROUND);
            markPaint.setStrokeJoin(Paint.Join.ROUND);
            markPaint.setStrokeWidth(Math.max(3f, radius * 0.14f));
            markPaint.setColor(Color.parseColor("#ECFDF5"));
            markPaint.setAlpha(250);
            canvas.drawLine(
                    cx - radius * 0.22f,
                    cy,
                    cx - radius * 0.04f,
                    cy + radius * 0.18f,
                    markPaint);
            canvas.drawLine(
                    cx - radius * 0.04f,
                    cy + radius * 0.18f,
                    cx + radius * 0.28f,
                    cy - radius * 0.20f,
                    markPaint);
        }
    }
}
