package com.crewpocket.helper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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

    private final Path crewMarkPath = new Path();
    private final RectF outerMarkBounds = new RectF();
    private final RectF innerMarkBounds = new RectF();
    private Shader coreGradient;
    private Shader markGradient;
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
    }

    @Override
    protected void onSizeChanged(
            int w,
            int h,
            int oldw,
            int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float radius =
                Math.max(1f, Math.min(w, h) / 2f - 1.5f);

        coreGradient = new RadialGradient(
                cx - radius * 0.18f,
                cy - radius * 0.20f,
                radius * 0.95f,
                new int[]{
                        Color.parseColor("#13233A"),
                        Color.parseColor("#08111F"),
                        Color.parseColor("#050B14")
                },
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP);

        markGradient = new LinearGradient(
                cx,
                cy - radius * 0.55f,
                cx,
                cy + radius * 0.55f,
                Color.parseColor("#F8FAFC"),
                Color.parseColor("#CBD5E1"),
                Shader.TileMode.CLAMP);
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
        drawOrbitNode(canvas, cx, cy, radius, mode);
        drawTransientResult(canvas, cx, cy, radius);
    }

    private void drawCrewMark(
            Canvas canvas,
            float cx,
            float cy,
            float radius) {
        // Subtle depth only. The core stays quiet so the mark reads first.
        bgPaint.setShader(coreGradient);
        bgPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, radius * 0.82f, bgPaint);
        bgPaint.setShader(null);

        // Crew Orbit Mark:
        // - filled geometric C instead of a font glyph
        // - terminals are radial cuts, creating matching diagonal bevels
        // - the cyan node parks just beyond the upper cut
        float outerRadius = radius * 0.50f;
        float innerRadius = radius * 0.29f;
        float startAngle = 42f;
        float sweepAngle = 276f;
        float endAngle = startAngle + sweepAngle;

        outerMarkBounds.set(
                cx - outerRadius,
                cy - outerRadius,
                cx + outerRadius,
                cy + outerRadius);
        innerMarkBounds.set(
                cx - innerRadius,
                cy - innerRadius,
                cx + innerRadius,
                cy + innerRadius);

        crewMarkPath.reset();
        crewMarkPath.arcTo(
                outerMarkBounds,
                startAngle,
                sweepAngle,
                true);
        crewMarkPath.arcTo(
                innerMarkBounds,
                endAngle,
                -sweepAngle,
                false);
        crewMarkPath.close();

        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setShader(markGradient);
        markPaint.setAlpha(245);
        canvas.drawPath(crewMarkPath, markPaint);
        markPaint.setShader(null);
    }

    private void drawOrbitNode(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            BubbleLogoStatePolicy.Mode mode) {
        float angle =
                CrewOrbitMarkPolicy.nodeAngleDegrees(
                        mode,
                        rotationAngle);
        double radians = Math.toRadians(angle);
        float orbitRadius = radius * 0.55f;
        float x =
                cx + (float) Math.cos(radians) * orbitRadius;
        float y =
                cy + (float) Math.sin(radians) * orbitRadius;

        float pulse = wave(1f);
        float nodeRadius = Math.max(2.3f, radius * 0.105f);
        int color = Color.parseColor("#67E8F9");
        int alpha = 245;
        float scale = 1f;

        if (agentResultFlash == 1 || isSuccessFlash) {
            color = Color.parseColor("#34D399");
            scale = 1.10f;
        } else if (agentResultFlash == 2
                || mode == BubbleLogoStatePolicy.Mode.ERROR) {
            color = Color.parseColor("#FB7185");
            scale = 1.08f;
        } else if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER
                || mode == BubbleLogoStatePolicy.Mode.STUCK) {
            color = Color.parseColor("#FBBF24");
            scale = 0.96f + 0.12f * pulse;
        } else if (mode == BubbleLogoStatePolicy.Mode.WAITING) {
            color = Color.parseColor("#FBBF24");
            alpha = 175;
            scale = 0.92f + 0.06f * pulse;
        } else if (mode
                == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING) {
            color = Color.parseColor("#5EEAD4");
            scale = 0.95f + 0.08f * pulse;
        } else if (mode == BubbleLogoStatePolicy.Mode.SPEAKING) {
            color = Color.parseColor("#C084FC");
            scale = 0.96f + 0.10f * pulse;
        } else if (mode == BubbleLogoStatePolicy.Mode.LISTENING) {
            float activity =
                    Math.max(
                            microphoneActivity,
                            microphoneSending ? 0.18f : 0.08f);
            scale = 0.94f
                    + 0.16f * Math.max(pulse, activity);
        } else if (mode == BubbleLogoStatePolicy.Mode.THINKING) {
            scale = 0.98f + 0.06f * pulse;
        } else if (mode == BubbleLogoStatePolicy.Mode.ACTING) {
            scale = 1.02f + 0.06f * pulse;
        }

        badgePaint.setColor(color);
        badgePaint.setAlpha(
                Math.max(18, Math.min(70, alpha / 5)));
        canvas.drawCircle(
                x,
                y,
                nodeRadius * 1.85f * scale,
                badgePaint);

        badgePaint.setColor(color);
        badgePaint.setAlpha(alpha);
        canvas.drawCircle(
                x,
                y,
                nodeRadius * scale,
                badgePaint);
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
                break;

            case WAITING_USER:
                drawFullRing(
                        canvas,
                        ring,
                        "#F59E0B",
                        180 + Math.round(55f * pulse));
                break;

            case CONVERSATION_WAITING:
                drawFullRing(
                        canvas,
                        ring,
                        "#14B8A6",
                        120 + Math.round(70f * pulse));
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
