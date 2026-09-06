package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Adds drag + persisted position + compact/minimized state to an existing
 * WindowManager overlay without owning the overlay's visual design.
 */
final class FloatingPanelController {
    private static final String PREFS = "crew_floating_panel_positions";

    private final Context context;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;
    private final String key;
    private final View view;
    private final WindowManager.LayoutParams params;

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean moved;

    FloatingPanelController(Context context,
                            String key,
                            WindowManager windowManager,
                            View view,
                            WindowManager.LayoutParams params) {
        this.context = context.getApplicationContext();
        this.key = key == null ? "default" : key;
        this.windowManager = windowManager;
        this.view = view;
        this.params = params;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void restorePosition() {
        if (prefs.contains(key + ".x")) params.x = prefs.getInt(key + ".x", params.x);
        if (prefs.contains(key + ".y")) params.y = prefs.getInt(key + ".y", params.y);
        clampToDisplay();
    }

    void attachDragHandle(View handle) {
        if (handle == null) handle = view;
        handle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                    params.x = downX + Math.round(dx);
                    params.y = downY + Math.round(dy);
                    clampToDisplay();
                    safeUpdate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) savePosition();
                    return moved;
            }
            return false;
        });
    }

    boolean wasLastGestureDrag() {
        return moved;
    }

    void savePosition() {
        prefs.edit()
                .putInt(key + ".x", params.x)
                .putInt(key + ".y", params.y)
                .apply();
    }

    void setCompact(boolean compact) {
        prefs.edit().putBoolean(key + ".compact", compact).apply();
    }

    boolean isCompact() {
        return prefs.getBoolean(key + ".compact", false);
    }

    void clampToDisplay() {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int margin = dp(8);
        int viewW = view.getWidth() > 0 ? view.getWidth() : Math.max(dp(160), params.width);
        int viewH = view.getHeight() > 0 ? view.getHeight() : Math.max(dp(48), params.height);

        // Assumes TOP|START-style overlay params. If existing overlay uses a
        // different gravity, normalize it before attaching this controller.
        int maxX = Math.max(margin, dm.widthPixels - viewW - margin);
        int maxY = Math.max(margin, dm.heightPixels - viewH - margin);
        params.x = Math.max(margin, Math.min(maxX, params.x));
        params.y = Math.max(margin, Math.min(maxY, params.y));
    }

    private void safeUpdate() {
        try { windowManager.updateViewLayout(view, params); }
        catch (Exception ignored) {}
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
