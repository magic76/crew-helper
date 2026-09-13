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
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** 0106: animated Crew orb rendering extracted from FloatingBubbleManager. */
final class FluidBubbleView extends View {
    private Paint bgPaint;
    private Paint ringPaint;
    private Paint glowPaint;
    private Bitmap logoBitmap;
    private RectF ringBounds = new RectF();
    private SweepGradient idleSweepGradient;
    private SweepGradient activeSweepGradient;
    private SweepGradient speakingSweepGradient;
    private SweepGradient errorSweepGradient;
    private SweepGradient attentionSweepGradient;
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

        long duration;
        if (agentWorking) {
            duration = 850L;
        } else if (isFlowing) {
            duration = 1200L;
        } else if (nativeVoiceState == 2) {
            duration = 1500L;
        } else if (nativeVoiceState == 1
                && microphoneSending
                && microphoneActivity > 0.12f) {
            duration = 1550L;
        } else if (nativeVoiceState == 1) {
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float radius = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - 1.5f);

        // 0072 brand core: selected Crew assistant logo. The source PNG has
        // transparent corners so the overlay remains a true circular bubble.
        RectF logoBounds = new RectF(0f, 0f, getWidth(), getHeight());
        bgPaint.setShader(null);
        bgPaint.setAlpha(255);
        if (logoBitmap != null && !logoBitmap.isRecycled()) {
            canvas.drawBitmap(logoBitmap, null, logoBounds, bgPaint);
        } else {
            bgPaint.setColor(Color.parseColor("#071426"));
            canvas.drawCircle(cx, cy, radius, bgPaint);
        }

        // Keep the previous runtime-state language as a thin animated rim:
        // neutral=idle, blue=listening, purple=speaking, red=error,
        // rainbow=tool execution. The logo itself never changes identity.
        matrix.setRotate(rotationAngle, cx, cy);
        SweepGradient rimGradient = nativeVoiceState == 3
                ? errorSweepGradient
                : agentNeedsAttention
                ? attentionSweepGradient
                : nativeVoiceState == 2
                ? speakingSweepGradient
                : nativeVoiceState == 1
                ? activeSweepGradient
                : isFlowing
                ? rainbowSweepGradient
                : idleSweepGradient;
        if (rimGradient != null) {
            rimGradient.setLocalMatrix(matrix);
            ringPaint.setShader(rimGradient);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            float listeningBoost = nativeVoiceState == 1 && microphoneSending
                    ? microphoneActivity : 0f;
            ringPaint.setStrokeWidth(Math.max(
                    2f,
                    radius * (0.075f + 0.035f * listeningBoost)));
            ringPaint.setAlpha(
                    agentWorking
                            ? 78
                            : (nativeVoiceState == 0
                                    && !isFlowing
                                    && !agentNeedsAttention
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

        if (nativeVoiceState == 1
                && microphoneSending
                && microphoneActivity > 0.06f
                && !agentWorking
                && !agentNeedsAttention) {
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
