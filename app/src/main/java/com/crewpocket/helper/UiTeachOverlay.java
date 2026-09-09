package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    // 0023: Teach taps must be delivered only AFTER the overlay window has
    // actually been removed, otherwise getRootInActiveWindow() can resolve
    // Crew Helper's own overlay instead of the underlying app.
    private static final long UNDERLYING_WINDOW_SETTLE_MS = 120L;

    interface Callback {
        void onPicked(int screenX, int screenY);
        void onCancelled();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

        // Explicit escape hatch. Keep it away from the bottom composer and
        // send control, which are exactly what the user is trying to teach.
        TextView cancel = new TextView(context);
        cancel.setText("❌  取消教學");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(15);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(28), dp(12), dp(28), dp(12));

        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(Color.argb(238, 30, 41, 59));
        cancelBg.setCornerRadius(dp(24));
        cancelBg.setStroke(dp(2), Color.argb(210, 248, 113, 113));
        cancel.setBackground(cancelBg);

        android.widget.FrameLayout.LayoutParams cancelParams =
                new android.widget.FrameLayout.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
        // Upper-middle leaves both the bottom composer/send bar and the top
        // app controls unobscured on chat apps.
        cancelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cancelParams.topMargin = Math.round(
                context.getResources().getDisplayMetrics().heightPixels * 0.40f);
        root.addView(cancel, cancelParams);

        // IMPORTANT: consume cancel touch here so root's generic teaching tap
        // handler cannot treat the Cancel button itself as the taught element.
        cancel.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                dismiss();
                if (callback != null) {
                    mainHandler.postDelayed(callback::onCancelled, 32L);
                }
            }
            return true;
        });

        root.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            final int x = Math.round(event.getRawX());
            final int y = Math.round(event.getRawY());
            dismiss();
            if (callback != null) {
                mainHandler.postDelayed(() -> callback.onPicked(x, y), UNDERLYING_WINDOW_SETTLE_MS);
            }
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                // Do not take input focus away from the target app.  If this
                // overlay becomes focused Android hides the IME, making the
                // learned send layout differ from the real post-TYPE layout.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
