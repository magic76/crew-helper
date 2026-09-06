package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 0015: tiny long-press menu for the floating bubble.
 * Deliberately smaller than the existing Live control dock.
 */
final class BubbleQuickActionsOverlay {
    interface Actions {
        void onInterrupt();
        void onToggleMute();
        void onOpenControls();
        void onHangup();
    }

    private final Context context;
    private final WindowManager windowManager;
    private View view;

    BubbleQuickActionsOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() {
        return view != null;
    }

    void show(int anchorX, int anchorY, final Actions actions) {
        dismiss();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(244, 15, 23, 42));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(90, 148, 163, 184));
        root.setBackground(bg);
        root.setElevation(dp(12));

        if (NativeLiveService.isAiSpeaking()) {
            root.addView(action("打斷 AI", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismiss();
                    if (actions != null) actions.onInterrupt();
                }
            }));
        }

        root.addView(action(
                NativeLiveService.isAgentMuted() ? "開啟麥克風" : "麥克風靜音",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismiss();
                        if (actions != null) actions.onToggleMute();
                    }
                }));

        root.addView(action("完整控制", new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                if (actions != null) actions.onOpenControls();
            }
        }));

        if (NativeLiveService.isActive()) {
            root.addView(action("結束通話", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismiss();
                    if (actions != null) actions.onHangup();
                }
            }));
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(154),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        lp.x = Math.max(dp(8), Math.min(screenW - dp(162), anchorX));
        lp.y = Math.max(dp(48), Math.min(screenH - dp(250), anchorY));

        try {
            windowManager.addView(root, lp);
            view = root;
        } catch (Exception ignored) {
            view = null;
        }
    }

    void dismiss() {
        if (view == null) return;
        View old = view;
        view = null;
        try { windowManager.removeViewImmediate(old); } catch (Exception ignored) {}
    }

    private TextView action(String label, View.OnClickListener listener) {
        TextView v = new TextView(context);
        v.setText(label);
        v.setTextColor(Color.WHITE);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setOnClickListener(listener);
        return v;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
