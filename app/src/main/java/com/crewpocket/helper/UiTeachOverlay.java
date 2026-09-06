package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * One-shot full-screen teaching overlay.
 *
 * It consumes the user's tap, reports raw screen coordinates and removes itself,
 * so teaching a destructive/submit button does not actually execute that button.
 *
 * 0012-0014 integration update:
 * - always shows an explicit Cancel button
 * - Cancel consumes the touch and closes only the overlay
 * - Cancel never forwards the tap to the underlying app
 */
final class UiTeachOverlay {
    interface Callback {
        void onPicked(int screenX, int screenY);
        void onCancelled();
    }

    private final Context context;
    private final WindowManager windowManager;
    private View overlay;

    UiTeachOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean show(String instruction, Callback callback) {
        if (overlay != null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false;
        }

        final android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        root.setBackgroundColor(Color.argb(18, 15, 23, 42));

        TextView chip = new TextView(context);
        chip.setText(instruction == null || instruction.isEmpty()
                ? "點一下你要教我的 UI 元素"
                : instruction);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        int padH = dp(16), padV = dp(10);
        chip.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 15, 23, 42));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(180, 148, 163, 184));
        chip.setBackground(bg);

        android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cp.topMargin = dp(64);
        root.addView(chip, cp);

        // Explicit escape hatch. This prevents accidental Teach/Settings entry
        // from trapping the user in a full-screen overlay.
        TextView cancel = new TextView(context);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(14), dp(8), dp(14), dp(8));

        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(Color.argb(238, 30, 41, 59));
        cancelBg.setCornerRadius(dp(16));
        cancelBg.setStroke(dp(1), Color.argb(210, 248, 113, 113));
        cancel.setBackground(cancelBg);

        android.widget.FrameLayout.LayoutParams cancelParams =
                new android.widget.FrameLayout.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
        cancelParams.gravity = Gravity.TOP | Gravity.END;
        cancelParams.topMargin = dp(56);
        cancelParams.rightMargin = dp(16);
        root.addView(cancel, cancelParams);

        // IMPORTANT: consume cancel touch here so root's generic teaching tap
        // handler cannot treat the Cancel button itself as the taught element.
        cancel.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                dismiss();
                if (callback != null) callback.onCancelled();
            }
            return true;
        });

        root.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            dismiss();
            if (callback != null) callback.onPicked(x, y);
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            overlay = root;
            windowManager.addView(root, lp);
            return true;
        } catch (Exception e) {
            overlay = null;
            return false;
        }
    }

    void dismiss() {
        if (overlay == null) return;
        View target = overlay;
        overlay = null;
        try { windowManager.removeView(target); } catch (Exception ignored) {}
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
