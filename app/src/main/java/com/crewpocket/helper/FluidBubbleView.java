package com.crewpocket.helper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** 0106: animated Crew orb rendering extracted from FloatingBubbleManager. */
final class FluidBubbleView extends View {
    private Paint bgPaint;
    private Paint ringPaint;
    private Paint glowPaint;
    private Paint accentPaint;
    private Paint badgePaint;
    private Paint badgeTextPaint;
    private Bitmap logoBitmap;
    private RectF ringBounds = new RectF();
    private Path logoClipPath = new Path();
    private SweepGradient idleSweepGradient;
    private SweepGradient activeSweepGradient;
    private SweepGradient speakingSweepGradient;
    private SweepGradient errorSweepGradient;
    private SweepGradient attentionSweepGradient;
    private SweepGradient conversationWaitingSweepGradient;
    private SweepGradient rainbowSweepGradient;
    private Matrix matrix = new Matrix();
    private float rotationAngle = 0f;
    private boolean isFlowing = false;
    private boolean isSuccessFlash = false;
    // 0110: visual-only microphone activity. This never gates audio.
    private float microphoneActivity = 0f;
    private boolean microphoneSending = false;
    private boolean contextReadyFlash = false;
    private int contextReadyFlashGeneration = 0;

    // 0090: explicit Agent state, independent of Gemini Live state.
    private boolean agentWorking = false;
    private boolean agentNeedsAttention = false;
    private boolean conversationWaiting = false;
    // 0 none, 1 success, 2 failure
    private int agentResultFlash = 0;
    private int agentResultFlashGeneration = 0;

    // 0 idle, 1 connected/listening, 2 AI speaking, 3 connection error
    private int nativeVoiceState = 0;
    private ValueAnimator continuousRotator;

    public FluidBubbleView(Context context) {
        super(context);
        init();
    }

    private void init() {
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);
        logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.crew_assistant_bubble);

        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(6.5f);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(12f);

        accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentPaint.setStyle(Paint.Style.FILL);

        badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setStyle(Paint.Style.FILL);

        badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeTextPaint.setStyle(Paint.Style.FILL);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        startContinuousRotation();
    }

    private void startContinuousRotation() {
        if (continuousRotator == null) {
            continuousRotator = ValueAnimator.ofFloat(0f, 360f);
            continuousRotator.setDuration(4000); // 4s full rotation (identical to Web CSS)
            continuousRotator.setRepeatCount(ValueAnimator.INFINITE);
            continuousRotator.setInterpolator(new LinearInterpolator());
            continuousRotator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    rotationAngle = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
        }
        if (!continuousRotator.isRunning()) {
            continuousRotator.start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startContinuousRotation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (continuousRotator != null) {
            continuousRotator.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float stroke = ringPaint.getStrokeWidth();
        ringBounds.set(stroke / 2f + 2f, stroke / 2f + 2f, w - stroke / 2f - 2f, h - stroke / 2f - 2f);

        float cx = w / 2f;
        float cy = h / 2f;

        // 1. Idle is deliberately neutral: it should not look as if it is listening.
        int[] idleColors = new int[]{
            Color.parseColor("#64748B"),
            Color.parseColor("#94A3B8"),
            Color.parseColor("#475569"),
            Color.parseColor("#64748B")
        };
        float[] idlePositions = new float[]{0.0f, 0.32f, 0.72f, 1.0f};
        idleSweepGradient = new SweepGradient(cx, cy, idleColors, idlePositions);

        // 2. Blue says "connected and listening".
        int[] activeColors = new int[]{
            Color.parseColor("#38BDF8"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#818CF8"),
            Color.parseColor("#38BDF8")
        };
        activeSweepGradient = new SweepGradient(cx, cy, activeColors, null);

        // 3. Purple is reserved for the assistant speaking.
        int[] speakColors = new int[]{
            Color.parseColor("#A855F7"),
            Color.parseColor("#C084FC"),
            Color.parseColor("#7C3AED"),
            Color.parseColor("#A855F7")
        };
        speakingSweepGradient = new SweepGradient(cx, cy, speakColors, null);

        int[] errorColors = new int[]{
            Color.parseColor("#F43F5E"), Color.parseColor("#EF4444"),
            Color.parseColor("#FB7185"), Color.parseColor("#F43F5E")
        };
        errorSweepGradient = new SweepGradient(cx, cy, errorColors, null);

        int[] attentionColors = new int[]{
            Color.parseColor("#F59E0B"), Color.parseColor("#FCD34D"),
            Color.parseColor("#FBBF24"), Color.parseColor("#F59E0B")
        };
        attentionSweepGradient =
                new SweepGradient(cx, cy, attentionColors, null);

        int[] conversationWaitingColors = new int[]{
            Color.parseColor("#2DD4BF"),
            Color.parseColor("#14B8A6"),
            Color.parseColor("#22D3EE"),
            Color.parseColor("#2DD4BF")
        };
        conversationWaitingSweepGradient =
                new SweepGradient(cx, cy, conversationWaitingColors, null);

        logoClipPath.reset();
        logoClipPath.addCircle(
                cx,
                cy,
                Math.min(w, h) * 0.42f,
                Path.Direction.CW);

        int[] rainbowColors = new int[]{
            Color.parseColor("#38BDF8"),
            Color.parseColor("#818CF8"),
            Color.parseColor("#C084FC"),
            Color.parseColor("#F43F5E"),
            Color.parseColor("#38BDF8")
        };
        rainbowSweepGradient = new SweepGradient(cx, cy, rainbowColors, null);
    }

    private void updateRotationSpeed() {
        if (continuousRotator == null) return;

        BubbleLogoStatePolicy.Mode mode = visualMode();
        long duration;
        if (mode == BubbleLogoStatePolicy.Mode.WORKING) {
            duration = 850L;
        } else if (isFlowing) {
            duration = 1200L;
        } else if (mode == BubbleLogoStatePolicy.Mode.SPEAKING) {
            duration = 1500L;
        } else if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER) {
            duration = 1900L;
        } else if (mode == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING) {
            duration = 3200L;
        } else if (mode == BubbleLogoStatePolicy.Mode.LISTENING
                && microphoneSending
                && microphoneActivity > 0.12f) {
            duration = 1550L;
        } else if (mode == BubbleLogoStatePolicy.Mode.LISTENING) {
            duration = 2500L;
        } else {
            duration = 4000L;
        }
        continuousRotator.setDuration(duration);
    }

    public void startWaterFlow() {
        isFlowing = true;
        isSuccessFlash = false;
        updateRotationSpeed();
        invalidate();
    }

    public void stopWaterFlow() {
        isFlowing = false;
        updateRotationSpeed();
        isSuccessFlash = true;
        invalidate();

        postDelayed(new Runnable() {
            @Override
            public void run() {
                isSuccessFlash = false;
                invalidate();
            }
        }, 850);
    }

    /** 0110: microphone telemetry drives visuals only; Server VAD remains authoritative. */
    public void setMicrophoneActivity(double dbfs, boolean sending) {
        float normalized = 0f;
        if (sending && dbfs > -72d) {
            normalized = (float) ((dbfs + 58d) / 40d);
            normalized = Math.max(0f, Math.min(1f, normalized));
        }
        microphoneActivity = microphoneActivity * 0.42f + normalized * 0.58f;
        if (!sending && microphoneActivity < 0.04f) microphoneActivity = 0f;
        microphoneSending = sending;
        updateRotationSpeed();
        invalidate();
    }

    /** Explicit selected-region context is ready for the next voice instruction. */
    public void flashContextReady() {
        final int generation = ++contextReadyFlashGeneration;
        contextReadyFlash = true;
        invalidate();
        postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != contextReadyFlashGeneration) return;
                contextReadyFlash = false;
                invalidate();
            }
        }, 900L);
    }

    public void setAgentWorking(boolean working) {
        if (agentWorking == working) return;
        agentWorking = working;
        if (working) {
            agentNeedsAttention = false;
            agentResultFlash = 0;
            agentResultFlashGeneration++;
        }
        updateRotationSpeed();
        invalidate();
    }

    public void setAgentNeedsAttention(boolean needsAttention) {
        if (agentNeedsAttention == needsAttention) return;
        agentNeedsAttention = needsAttention;
        if (needsAttention) {
            agentWorking = false;
            agentResultFlash = 0;
            agentResultFlashGeneration++;
        }
        updateRotationSpeed();
        invalidate();
    }

    public void setConversationWaiting(boolean waiting) {
        if (conversationWaiting == waiting) return;
        conversationWaiting = waiting;
        updateRotationSpeed();
        invalidate();
    }

    public void flashAgentResult(final boolean success) {
        agentWorking = false;
        agentNeedsAttention = false;
        updateRotationSpeed();

        final int generation = ++agentResultFlashGeneration;
        agentResultFlash = success ? 1 : 2;
        invalidate();

        postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != agentResultFlashGeneration) return;
                agentResultFlash = 0;
                invalidate();
            }
        }, success ? 650L : 950L);
    }

    public void setNativeVoiceState(int state) {
        this.nativeVoiceState = state;
        if (state != 1) {
            microphoneActivity = 0f;
            microphoneSending = false;
        }
        updateRotationSpeed();
        invalidate();
    }

    private BubbleLogoStatePolicy.Mode visualMode() {
        return BubbleLogoStatePolicy.resolve(
                nativeVoiceState,
                agentWorking,
                agentNeedsAttention,
                conversationWaiting);
    }

    private float wave(float cycles) {
        double radians = Math.toRadians(rotationAngle * cycles);
        return 0.5f + 0.5f * (float) Math.sin(radians);
    }

    private void drawLogoWithState(
            Canvas canvas,
            RectF logoBounds,
            float cx,
            float cy,
            float radius,
            BubbleLogoStatePolicy.Mode mode) {
        float pulse = wave(1f);
        float scale = 1f;
        float dx = 0f;
        float dy = 0f;
        float tilt = 0f;
        int accentColor = Color.TRANSPARENT;
        int accentAlpha = 0;

        if (mode == BubbleLogoStatePolicy.Mode.LISTENING) {
            scale = 0.992f + 0.018f * pulse;
            accentColor = Color.parseColor("#38BDF8");
            accentAlpha = 14 + Math.round(24f * Math.max(
                    microphoneActivity, 0.15f));
        } else if (mode == BubbleLogoStatePolicy.Mode.SPEAKING) {
            scale = 0.982f + 0.038f * pulse;
            accentColor = Color.parseColor("#A855F7");
            accentAlpha = 34 + Math.round(26f * pulse);
        } else if (mode == BubbleLogoStatePolicy.Mode.WORKING) {
            scale = 0.994f + 0.012f * pulse;
            tilt = (float) Math.sin(
                    Math.toRadians(rotationAngle * 2f)) * 1.8f;
            accentColor = Color.parseColor("#22D3EE");
            accentAlpha = 26 + Math.round(14f * pulse);
        } else if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER) {
            scale = 0.998f + 0.026f * pulse;
            dy = -radius * 0.035f * Math.max(
                    0f,
                    (float) Math.sin(
                            Math.toRadians(rotationAngle * 1.4f)));
            accentColor = Color.parseColor("#F59E0B");
            accentAlpha = 34 + Math.round(22f * pulse);
        } else if (mode
                == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING) {
            float heartbeat = (float) Math.pow(
                    Math.max(
                            0f,
                            Math.sin(
                                    Math.toRadians(rotationAngle * 2f))),
                    4d);
            scale = 0.992f + 0.026f * heartbeat;
            accentColor = Color.parseColor("#2DD4BF");
            accentAlpha = 24 + Math.round(34f * heartbeat);
        } else if (mode == BubbleLogoStatePolicy.Mode.ERROR) {
            dx = (float) Math.sin(
                    Math.toRadians(rotationAngle * 4f))
                    * radius * 0.018f;
            accentColor = Color.parseColor("#F43F5E");
            accentAlpha = 34;
        }

        if (agentResultFlash == 1) {
            scale += 0.025f * pulse;
            accentColor = Color.parseColor("#34D399");
            accentAlpha = 62;
        } else if (agentResultFlash == 2) {
            dx += (float) Math.sin(
                    Math.toRadians(rotationAngle * 8f))
                    * radius * 0.04f;
            accentColor = Color.parseColor("#FB7185");
            accentAlpha = 62;
        }

        if (accentAlpha > 0) {
            accentPaint.setStyle(Paint.Style.FILL);
            accentPaint.setColor(accentColor);
            accentPaint.setAlpha(accentAlpha);
            canvas.drawCircle(
                    cx,
                    cy,
                    radius * (0.72f + 0.025f * pulse),
                    accentPaint);
        }

        canvas.save();
        canvas.translate(dx, dy);
        canvas.rotate(tilt, cx, cy);
        canvas.scale(scale, scale, cx, cy);

        bgPaint.setShader(null);
        bgPaint.setAlpha(255);
        if (logoBitmap != null && !logoBitmap.isRecycled()) {
            canvas.drawBitmap(logoBitmap, null, logoBounds, bgPaint);
        } else {
            bgPaint.setColor(Color.parseColor("#071426"));
            canvas.drawCircle(cx, cy, radius, bgPaint);
        }
        canvas.restore();

        if (accentAlpha > 0) {
            accentPaint.setStyle(Paint.Style.FILL);
            accentPaint.setColor(accentColor);
            accentPaint.setAlpha(Math.max(8, accentAlpha / 3));
            canvas.drawCircle(cx, cy, radius * 0.64f, accentPaint);
        }

        if (mode == BubbleLogoStatePolicy.Mode.WORKING) {
            drawWorkingScanner(canvas, radius);
        } else if (mode
                == BubbleLogoStatePolicy.Mode.CONVERSATION_WAITING) {
            drawConversationWaitingDot(canvas, cx, cy, radius, pulse);
        }

        if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER) {
            drawAttentionBadge(canvas, radius);
        }
    }

    private void drawWorkingScanner(Canvas canvas, float radius) {
        float fraction = rotationAngle / 360f;
        float scanX = -radius
                + fraction * (getWidth() + radius * 2f);

        canvas.save();
        canvas.clipPath(logoClipPath);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setStrokeWidth(Math.max(3f, radius * 0.16f));
        accentPaint.setColor(Color.parseColor("#67E8F9"));
        accentPaint.setAlpha(68);
        canvas.drawLine(
                scanX - radius * 0.72f,
                getHeight(),
                scanX + radius * 0.72f,
                0f,
                accentPaint);
        canvas.restore();
    }

    private void drawConversationWaitingDot(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            float pulse) {
        accentPaint.setStyle(Paint.Style.FILL);
        accentPaint.setColor(Color.parseColor("#5EEAD4"));
        accentPaint.setAlpha(225);
        canvas.drawCircle(
                cx + radius * 0.43f,
                cy + radius * 0.43f,
                radius * (0.055f + 0.018f * pulse),
                accentPaint);
    }

    private void drawAttentionBadge(Canvas canvas, float radius) {
        float badgeRadius = Math.max(4f, radius * 0.18f);
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
        float baseline = badgeCy
                - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText("!", badgeCx, baseline, badgeTextPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float radius = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - 1.5f);

        RectF logoBounds = new RectF(0f, 0f, getWidth(), getHeight());
        BubbleLogoStatePolicy.Mode mode = visualMode();
        drawLogoWithState(
                canvas,
                logoBounds,
                cx,
                cy,
                radius,
                mode);

        // Keep the state rim as secondary redundancy; the logo itself now also
        // breathes/glows/scans so state is readable without memorizing colors.
        matrix.setRotate(rotationAngle, cx, cy);
        SweepGradient rimGradient =
                mode == BubbleLogoStatePolicy.Mode.ERROR
                        ? errorSweepGradient
                        : mode == BubbleLogoStatePolicy.Mode.WAITING_USER
                        ? attentionSweepGradient
                        : mode == BubbleLogoStatePolicy.Mode.SPEAKING
                        ? speakingSweepGradient
                        : mode
                                == BubbleLogoStatePolicy.Mode
                                        .CONVERSATION_WAITING
                        ? conversationWaitingSweepGradient
                        : mode == BubbleLogoStatePolicy.Mode.LISTENING
                        || mode == BubbleLogoStatePolicy.Mode.WORKING
                        ? activeSweepGradient
                        : isFlowing
                        ? rainbowSweepGradient
                        : idleSweepGradient;
        if (rimGradient != null) {
            rimGradient.setLocalMatrix(matrix);
            ringPaint.setShader(rimGradient);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            float listeningBoost =
                    mode == BubbleLogoStatePolicy.Mode.LISTENING
                            && microphoneSending
                    ? microphoneActivity : 0f;
            ringPaint.setStrokeWidth(Math.max(
                    2f,
                    radius * (0.075f + 0.035f * listeningBoost)));
            ringPaint.setAlpha(
                    mode == BubbleLogoStatePolicy.Mode.WORKING
                            ? 78
                            : (mode == BubbleLogoStatePolicy.Mode.IDLE
                                    && !isFlowing
                                    ? 90
                                    : Math.min(255,
                                            205 + Math.round(50f * listeningBoost))));
            RectF stateRing = new RectF(
                    ringPaint.getStrokeWidth() / 2f,
                    ringPaint.getStrokeWidth() / 2f,
                    getWidth() - ringPaint.getStrokeWidth() / 2f,
                    getHeight() - ringPaint.getStrokeWidth() / 2f);
            canvas.drawOval(stateRing, ringPaint);
            ringPaint.setShader(null);
        }

        if (mode == BubbleLogoStatePolicy.Mode.LISTENING
                && microphoneSending
                && microphoneActivity > 0.06f) {
            glowPaint.setShader(null);
            glowPaint.setColor(Color.parseColor("#38BDF8"));
            glowPaint.setAlpha(28 + Math.round(72f * microphoneActivity));
            glowPaint.setStrokeWidth(Math.max(3f, radius * 0.055f));
            float haloInset = Math.max(3f, radius * 0.11f);
            RectF listeningHalo = new RectF(
                    haloInset, haloInset,
                    getWidth() - haloInset,
                    getHeight() - haloInset);
            canvas.drawOval(listeningHalo, glowPaint);
        }

        if (agentWorking) {
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setShader(null);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeWidth(Math.max(2.5f, radius * 0.095f));
            ringPaint.setColor(Color.parseColor("#22D3EE"));
            ringPaint.setAlpha(255);

            float inset = ringPaint.getStrokeWidth() / 2f;
            RectF agentSpinnerRing = new RectF(
                    inset,
                    inset,
                    getWidth() - inset,
                    getHeight() - inset);
            canvas.drawArc(
                    agentSpinnerRing,
                    rotationAngle - 90f,
                    92f,
                    false,
                    ringPaint);
        }

        if (agentResultFlash != 0) {
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setShader(null);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeWidth(Math.max(2.5f, radius * 0.085f));
            ringPaint.setColor(
                    agentResultFlash == 1
                            ? Color.parseColor("#34D399")
                            : Color.parseColor("#FB7185"));
            ringPaint.setAlpha(245);

            float resultInset = ringPaint.getStrokeWidth();
            RectF resultRing = new RectF(
                    resultInset,
                    resultInset,
                    getWidth() - resultInset,
                    getHeight() - resultInset);
            canvas.drawOval(resultRing, ringPaint);
        }

        if (contextReadyFlash) {
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setShader(null);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeWidth(Math.max(3f, radius * 0.095f));
            ringPaint.setColor(Color.parseColor("#2DD4BF"));
            ringPaint.setAlpha(250);
            float contextInset = ringPaint.getStrokeWidth();
            RectF contextRing = new RectF(
                    contextInset, contextInset,
                    getWidth() - contextInset,
                    getHeight() - contextInset);
            canvas.drawOval(contextRing, ringPaint);
        }

        if (isSuccessFlash) {
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setShader(null);
            ringPaint.setStrokeWidth(Math.max(2f, radius * 0.055f));
            ringPaint.setColor(Color.WHITE);
            ringPaint.setAlpha(210);
            RectF successRing = new RectF(
                    ringPaint.getStrokeWidth(), ringPaint.getStrokeWidth(),
                    getWidth() - ringPaint.getStrokeWidth(),
                    getHeight() - ringPaint.getStrokeWidth());
            canvas.drawOval(successRing, ringPaint);
        }
    }
}
