package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 0106: bubble dock icon rendering extracted from FloatingBubbleManager. */
final class DockIconButton extends View {
    public static final int ICON_CAMERA = 1;
    public static final int ICON_SCREEN = 2;
    public static final int ICON_MIC_ACTIVE = 3;
    public static final int ICON_MIC_MUTED = 4;
    public static final int ICON_SPEAKER = 5;
    public static final int ICON_CALL_START = 6;
    public static final int ICON_CALL_HANGUP = 7;

    private int iconType = ICON_CAMERA;
    private int primaryColor = Color.parseColor("#94A3B8");
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public DockIconButton(Context context) {
        super(context);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setIcon(int type, int color) {
        this.iconType = type;
        this.primaryColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        iconPaint.setColor(primaryColor);
        float density = getResources().getDisplayMetrics().density;
        iconPaint.setStrokeWidth(2f * density);

        float sz = 11f * density;
        bounds.set(cx - sz, cy - sz, cx + sz, cy + sz);

        switch (iconType) {
            case ICON_CAMERA:
                // Camera body
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF camBody = new RectF(cx - 10 * density, cy - 6 * density, cx + 5 * density, cy + 8 * density);
                canvas.drawRoundRect(camBody, 2.5f * density, 2.5f * density, iconPaint);
                // Lens triangle
                android.graphics.Path camLens = new android.graphics.Path();
                camLens.moveTo(cx + 5 * density, cy - 2 * density);
                camLens.lineTo(cx + 11 * density, cy - 6 * density);
                camLens.lineTo(cx + 11 * density, cy + 8 * density);
                camLens.lineTo(cx + 5 * density, cy + 4 * density);
                camLens.close();
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(camLens, iconPaint);
                break;

            case ICON_SCREEN:
                // Monitor screen
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF screenBox = new RectF(cx - 10 * density, cy - 7 * density, cx + 10 * density, cy + 4 * density);
                canvas.drawRoundRect(screenBox, 2f * density, 2f * density, iconPaint);
                // Stand base
                canvas.drawLine(cx, cy + 4 * density, cx, cy + 8 * density, iconPaint);
                canvas.drawLine(cx - 5 * density, cy + 8 * density, cx + 5 * density, cy + 8 * density, iconPaint);
                break;

            case ICON_MIC_ACTIVE:
                // Microphone capsule
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF micCap = new RectF(cx - 3.5f * density, cy - 8 * density, cx + 3.5f * density, cy + 1 * density);
                canvas.drawRoundRect(micCap, 3.5f * density, 3.5f * density, iconPaint);
                // Mic cradle
                RectF micCradle = new RectF(cx - 6.5f * density, cy - 4 * density, cx + 6.5f * density, cy + 3 * density);
                canvas.drawArc(micCradle, 0, 180, false, iconPaint);
                // Stem & base
                canvas.drawLine(cx, cy + 3 * density, cx, cy + 7 * density, iconPaint);
                canvas.drawLine(cx - 4 * density, cy + 7 * density, cx + 4 * density, cy + 7 * density, iconPaint);
                break;

            case ICON_MIC_MUTED:
                // Muted mic with diagonal slash
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF micMutedCap = new RectF(cx - 3.5f * density, cy - 8 * density, cx + 3.5f * density, cy + 1 * density);
                canvas.drawRoundRect(micMutedCap, 3.5f * density, 3.5f * density, iconPaint);
                RectF micMutedCradle = new RectF(cx - 6.5f * density, cy - 4 * density, cx + 6.5f * density, cy + 3 * density);
                canvas.drawArc(micMutedCradle, 0, 180, false, iconPaint);
                canvas.drawLine(cx, cy + 3 * density, cx, cy + 7 * density, iconPaint);
                // Slash
                iconPaint.setColor(Color.parseColor("#F43F5E"));
                canvas.drawLine(cx - 9 * density, cy + 8 * density, cx + 9 * density, cy - 8 * density, iconPaint);
                break;

            case ICON_SPEAKER:
                // AI speaking wave / speaker
                iconPaint.setStyle(Paint.Style.STROKE);
                android.graphics.Path spk = new android.graphics.Path();
                spk.moveTo(cx - 7 * density, cy - 3 * density);
                spk.lineTo(cx - 4 * density, cy - 3 * density);
                spk.lineTo(cx + 1 * density, cy - 7 * density);
                spk.lineTo(cx + 1 * density, cy + 7 * density);
                spk.lineTo(cx - 4 * density, cy + 3 * density);
                spk.lineTo(cx - 7 * density, cy + 3 * density);
                spk.close();
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(spk, iconPaint);
                // Sound waves
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF wave1 = new RectF(cx - 2 * density, cy - 4 * density, cx + 6 * density, cy + 4 * density);
                canvas.drawArc(wave1, -45, 90, false, iconPaint);
                RectF wave2 = new RectF(cx - 2 * density, cy - 8 * density, cx + 10 * density, cy + 8 * density);
                canvas.drawArc(wave2, -45, 90, false, iconPaint);
                break;

            case ICON_CALL_START:
                // Start call (Phone handset / mic trigger)
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF startCap = new RectF(cx - 3.5f * density, cy - 7 * density, cx + 3.5f * density, cy + 1 * density);
                canvas.drawRoundRect(startCap, 3.5f * density, 3.5f * density, iconPaint);
                RectF startCradle = new RectF(cx - 6f * density, cy - 3 * density, cx + 6f * density, cy + 3 * density);
                canvas.drawArc(startCradle, 0, 180, false, iconPaint);
                canvas.drawLine(cx, cy + 3 * density, cx, cy + 7 * density, iconPaint);
                canvas.drawLine(cx - 4 * density, cy + 7 * density, cx + 4 * density, cy + 7 * density, iconPaint);
                break;

            case ICON_CALL_HANGUP:
                // Hangup X / Stop octagon
                iconPaint.setStyle(Paint.Style.STROKE);
                iconPaint.setStrokeWidth(2.5f * density);
                canvas.drawLine(cx - 5.5f * density, cy - 5.5f * density, cx + 5.5f * density, cy + 5.5f * density, iconPaint);
                canvas.drawLine(cx + 5.5f * density, cy - 5.5f * density, cx - 5.5f * density, cy + 5.5f * density, iconPaint);
                break;
        }
    }
}
