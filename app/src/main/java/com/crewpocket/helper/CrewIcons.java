package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** 0111 shared monochrome outline icon language for Crew UI surfaces. */
final class CrewIcons {
    static final int NONE = 0;
    static final int VOICE = 1;
    static final int MIC = 2;
    static final int MIC_MUTED = 3;
    static final int SPEAKING = 4;
    static final int INTERRUPT = 5;
    static final int HANGUP = 6;
    static final int CAMERA = 7;
    static final int SCREEN = 8;
    static final int SETTINGS = 9;
    static final int PRESENTATION = 10;
    static final int NOTEBOOK = 11;
    static final int PERSONALITY = 12;
    static final int SENSITIVITY = 13;
    static final int AUDIO = 14;
    static final int CLOCK = 15;
    static final int WAKE = 16;
    static final int ALWAYS_ON = 17;
    static final int BELL = 18;
    static final int PHONE_ACTIONS = 19;
    static final int BUBBLE = 20;
    static final int SUN = 21;
    static final int KEY = 22;
    static final int GLOBE = 23;
    static final int DIAGNOSTICS = 24;
    static final int BRAIN = 25;
    static final int INSPECTOR = 26;
    static final int PLUS = 27;
    static final int MORE = 28;
    static final int REGION = 29;
    static final int SPARKLE = 30;

    private CrewIcons() {}

    static int fromLegacyToken(String token) {
        if (token == null) return NONE;
        String t = token.trim();
        if (t.contains("🎙") || "🗣".equals(t)) return VOICE;
        if ("▣".equals(t)) return PRESENTATION;
        if ("⚙".equals(t) || "⚙️".equals(t)) return SETTINGS;
        if ("📝".equals(t)) return NOTEBOOK;
        if ("◌".equals(t)) return PERSONALITY;
        if ("↯".equals(t)) return INTERRUPT;
        if ("🔊".equals(t)) return AUDIO;
        if (t.contains("⏱")) return CLOCK;
        if ("⌁".equals(t)) return WAKE;
        if ("🎧".equals(t)) return ALWAYS_ON;
        if ("◉".equals(t)) return SENSITIVITY;
        if ("🔔".equals(t)) return BELL;
        if ("🛡".equals(t) || t.contains("🛡️")) return PHONE_ACTIONS;
        if ("📸".equals(t)) return CAMERA;
        if ("☀".equals(t)) return SUN;
        if ("🔐".equals(t)) return KEY;
        if ("🌐".equals(t)) return GLOBE;
        if ("⌘".equals(t)) return DIAGNOSTICS;
        if ("🧠".equals(t)) return BRAIN;
        if ("➕".equals(t)) return PLUS;
        if ("⋯".equals(t)) return MORE;
        return NONE;
    }

    static Drawable drawable(Context context, int icon, int color, int sizePx) {
        return new IconDrawable(icon, color, Math.max(1, sizePx));
    }

    static void draw(Canvas canvas, int icon, int color, float cx, float cy,
                     float size, float strokeWidth) {
        if (canvas == null || icon == NONE) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(strokeWidth);
        float s = size / 24f;
        float l = cx - 12f * s;
        float t = cy - 12f * s;
        Path path = new Path();

        switch (icon) {
            case VOICE:
            case MIC:
                roundedMic(canvas, p, cx, cy, s, false);
                return;
            case MIC_MUTED:
                roundedMic(canvas, p, cx, cy, s, true);
                return;
            case SPEAKING:
                speaker(canvas, p, cx, cy, s);
                return;
            case INTERRUPT:
                speaker(canvas, p, cx - 1.2f*s, cy, s * 0.9f);
                p.setStrokeWidth(strokeWidth * 1.15f);
                canvas.drawLine(cx + 5*s, cy - 6*s, cx + 5*s, cy + 6*s, p);
                canvas.drawLine(cx + 9*s, cy - 6*s, cx + 9*s, cy + 6*s, p);
                return;
            case HANGUP:
                p.setStyle(Paint.Style.STROKE);
                // Keep the original compact X control; this is not a phone
                // handset/hang-up glyph.
                canvas.drawLine(cx - 5.5f*s, cy - 5.5f*s,
                        cx + 5.5f*s, cy + 5.5f*s, p);
                canvas.drawLine(cx + 5.5f*s, cy - 5.5f*s,
                        cx - 5.5f*s, cy + 5.5f*s, p);
                return;
            case CAMERA:
                canvas.drawRoundRect(new RectF(cx-9*s, cy-6*s, cx+5*s, cy+7*s), 2.5f*s, 2.5f*s, p);
                path.moveTo(cx+5*s, cy-2*s); path.lineTo(cx+10*s, cy-5*s); path.lineTo(cx+10*s, cy+6*s); path.lineTo(cx+5*s, cy+3*s); path.close();
                p.setStyle(Paint.Style.FILL); canvas.drawPath(path,p); return;
            case SCREEN:
                canvas.drawRoundRect(new RectF(cx-9*s,cy-7*s,cx+9*s,cy+4*s),2*s,2*s,p);
                canvas.drawLine(cx,cy+4*s,cx,cy+8*s,p); canvas.drawLine(cx-5*s,cy+8*s,cx+5*s,cy+8*s,p); return;
            case SETTINGS:
                canvas.drawCircle(cx,cy,3*s,p);
                for (int i=0;i<8;i++){ double a=Math.PI*i/4.0; float x1=cx+(6.5f*s)*(float)Math.cos(a), y1=cy+(6.5f*s)*(float)Math.sin(a); float x2=cx+(9*s)*(float)Math.cos(a), y2=cy+(9*s)*(float)Math.sin(a); canvas.drawLine(x1,y1,x2,y2,p);} return;
            case PRESENTATION:
                canvas.drawRoundRect(new RectF(cx-9*s,cy-8*s,cx+9*s,cy+4*s),2*s,2*s,p);
                canvas.drawLine(cx,cy+4*s,cx,cy+8*s,p); canvas.drawLine(cx-5*s,cy+8*s,cx+5*s,cy+8*s,p);
                canvas.drawLine(cx-5*s,cy+0*s,cx-1*s,cy-3*s,p); canvas.drawLine(cx-1*s,cy-3*s,cx+4*s,cy+1*s,p); return;
            case NOTEBOOK:
                canvas.drawRoundRect(new RectF(cx-8*s,cy-9*s,cx+8*s,cy+9*s),2*s,2*s,p); canvas.drawLine(cx-4*s,cy-4*s,cx+4*s,cy-4*s,p); canvas.drawLine(cx-4*s,cy,cx+4*s,cy,p); canvas.drawLine(cx-4*s,cy+4*s,cx+2*s,cy+4*s,p); return;
            case PERSONALITY:
                canvas.drawCircle(cx,cy,8*s,p); canvas.drawCircle(cx-3*s,cy-2*s,0.7f*s,p); canvas.drawCircle(cx+3*s,cy-2*s,0.7f*s,p); canvas.drawArc(new RectF(cx-4*s,cy-1*s,cx+4*s,cy+5*s),20,140,false,p); return;
            case SENSITIVITY:
                canvas.drawLine(cx-8*s,cy-5*s,cx+8*s,cy-5*s,p); canvas.drawCircle(cx-2*s,cy-5*s,1.7f*s,p); canvas.drawLine(cx-8*s,cy,cx+8*s,cy,p); canvas.drawCircle(cx+4*s,cy,1.7f*s,p); canvas.drawLine(cx-8*s,cy+5*s,cx+8*s,cy+5*s,p); canvas.drawCircle(cx-4*s,cy+5*s,1.7f*s,p); return;
            case AUDIO:
                speaker(canvas,p,cx-1*s,cy,s); return;
            case CLOCK:
                canvas.drawCircle(cx,cy,8*s,p); canvas.drawLine(cx,cy,cx,cy-4*s,p); canvas.drawLine(cx,cy,cx+4*s,cy+2*s,p); return;
            case WAKE:
                canvas.drawCircle(cx,cy,2*s,p); canvas.drawArc(new RectF(cx-6*s,cy-6*s,cx+6*s,cy+6*s),-55,110,false,p); canvas.drawArc(new RectF(cx-10*s,cy-10*s,cx+10*s,cy+10*s),-50,100,false,p); return;
            case ALWAYS_ON:
                canvas.drawArc(new RectF(cx-8*s,cy-8*s,cx+8*s,cy+8*s),200,280,false,p); canvas.drawCircle(cx,cy,2*s,p); return;
            case BELL:
                path.moveTo(cx-6*s,cy+4*s); path.quadTo(cx-6*s,cy-6*s,cx,cy-7*s); path.quadTo(cx+6*s,cy-6*s,cx+6*s,cy+4*s); canvas.drawPath(path,p); canvas.drawLine(cx-7*s,cy+4*s,cx+7*s,cy+4*s,p); canvas.drawArc(new RectF(cx-2*s,cy+4*s,cx+2*s,cy+8*s),0,180,false,p); return;
            case PHONE_ACTIONS:
                canvas.drawRoundRect(new RectF(cx-7*s,cy-10*s,cx+7*s,cy+10*s),2*s,2*s,p); path.moveTo(cx-2*s,cy+1*s); path.lineTo(cx+1*s,cy+4*s); path.lineTo(cx+6*s,cy-3*s); canvas.drawPath(path,p); return;
            case BUBBLE:
                canvas.drawCircle(cx,cy,8*s,p); canvas.drawCircle(cx+5*s,cy-5*s,2*s,p); return;
            case SUN:
                canvas.drawCircle(cx,cy,4*s,p); for(int i=0;i<8;i++){double a=Math.PI*i/4.0;canvas.drawLine(cx+6*s*(float)Math.cos(a),cy+6*s*(float)Math.sin(a),cx+9*s*(float)Math.cos(a),cy+9*s*(float)Math.sin(a),p);} return;
            case KEY:
                canvas.drawCircle(cx-4*s,cy,4*s,p); canvas.drawLine(cx,cy,cx+8*s,cy,p); canvas.drawLine(cx+5*s,cy,cx+5*s,cy+3*s,p); canvas.drawLine(cx+8*s,cy,cx+8*s,cy+3*s,p); return;
            case GLOBE:
                canvas.drawCircle(cx,cy,9*s,p); canvas.drawOval(new RectF(cx-4*s,cy-9*s,cx+4*s,cy+9*s),p); canvas.drawLine(cx-9*s,cy,cx+9*s,cy,p); return;
            case DIAGNOSTICS:
            case INSPECTOR:
                path.moveTo(cx-9*s,cy+2*s); path.lineTo(cx-5*s,cy+2*s); path.lineTo(cx-2*s,cy-5*s); path.lineTo(cx+2*s,cy+6*s); path.lineTo(cx+5*s,cy-2*s); path.lineTo(cx+9*s,cy-2*s); canvas.drawPath(path,p); return;
            case BRAIN:
                canvas.drawCircle(cx-3*s,cy,5*s,p); canvas.drawCircle(cx+3*s,cy,5*s,p); canvas.drawLine(cx,cy-6*s,cx,cy+6*s,p); return;
            case PLUS:
                canvas.drawLine(cx-6*s,cy,cx+6*s,cy,p); canvas.drawLine(cx,cy-6*s,cx,cy+6*s,p); return;
            case MORE:
                p.setStyle(Paint.Style.FILL); canvas.drawCircle(cx-6*s,cy,1.5f*s,p); canvas.drawCircle(cx,cy,1.5f*s,p); canvas.drawCircle(cx+6*s,cy,1.5f*s,p); return;
            case REGION:
                canvas.drawRect(cx-5*s,cy-5*s,cx+5*s,cy+5*s,p); canvas.drawLine(cx-9*s,cy-3*s,cx-9*s,cy-9*s,p); canvas.drawLine(cx-9*s,cy-9*s,cx-3*s,cy-9*s,p); canvas.drawLine(cx+3*s,cy+9*s,cx+9*s,cy+9*s,p); canvas.drawLine(cx+9*s,cy+9*s,cx+9*s,cy+3*s,p); return;
            case SPARKLE:
                canvas.drawLine(cx,cy-8*s,cx,cy+8*s,p); canvas.drawLine(cx-8*s,cy,cx+8*s,cy,p); canvas.drawLine(cx-5*s,cy-5*s,cx+5*s,cy+5*s,p); canvas.drawLine(cx+5*s,cy-5*s,cx-5*s,cy+5*s,p); return;
        }
    }

    private static void roundedMic(Canvas canvas, Paint p, float cx, float cy, float s, boolean muted) {
        canvas.drawRoundRect(new RectF(cx-3.5f*s,cy-8*s,cx+3.5f*s,cy+1*s),3.5f*s,3.5f*s,p);
        canvas.drawArc(new RectF(cx-6.5f*s,cy-4*s,cx+6.5f*s,cy+3*s),0,180,false,p);
        canvas.drawLine(cx,cy+3*s,cx,cy+7*s,p); canvas.drawLine(cx-4*s,cy+7*s,cx+4*s,cy+7*s,p);
        if (muted) { int old=p.getColor(); p.setColor(Color.parseColor("#F43F5E")); canvas.drawLine(cx-9*s,cy+8*s,cx+9*s,cy-8*s,p); p.setColor(old); }
    }

    private static void speaker(Canvas canvas, Paint p, float cx, float cy, float s) {
        Path sp = new Path(); sp.moveTo(cx-7*s,cy-3*s); sp.lineTo(cx-4*s,cy-3*s); sp.lineTo(cx+1*s,cy-7*s); sp.lineTo(cx+1*s,cy+7*s); sp.lineTo(cx-4*s,cy+3*s); sp.lineTo(cx-7*s,cy+3*s); sp.close();
        Paint.Style old=p.getStyle(); p.setStyle(Paint.Style.FILL); canvas.drawPath(sp,p); p.setStyle(Paint.Style.STROKE);
        canvas.drawArc(new RectF(cx-2*s,cy-4*s,cx+6*s,cy+4*s),-45,90,false,p); canvas.drawArc(new RectF(cx-2*s,cy-8*s,cx+10*s,cy+8*s),-45,90,false,p); p.setStyle(old);
    }

    private static final class IconDrawable extends Drawable {
        private final int icon, color, size;
        IconDrawable(int icon, int color, int size) { this.icon=icon; this.color=color; this.size=size; setBounds(0,0,size,size); }
        @Override public void draw(Canvas canvas) { Rect b=getBounds(); CrewIcons.draw(canvas, icon, color, b.exactCenterX(), b.exactCenterY(), Math.min(b.width(),b.height())*0.82f, Math.max(2f,b.width()*0.075f)); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}
