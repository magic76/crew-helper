package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

/** Tiny reusable View backed by the shared 0111 CrewIcons renderer. */
class CrewIconView extends View {
    private int icon = CrewIcons.NONE;
    private int color = Color.WHITE;
    private float scale = 0.72f;

    CrewIconView(Context context) { super(context); }

    void setIcon(int icon, int color) { this.icon = icon; this.color = color; invalidate(); }
    void setIconScale(float scale) { this.scale = Math.max(0.35f, Math.min(0.95f, scale)); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) * scale;
        CrewIcons.draw(canvas, icon, color, getWidth()/2f, getHeight()/2f, size,
                Math.max(2f, size * 0.075f));
    }
}
