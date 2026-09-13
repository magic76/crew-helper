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
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Keyboard companion overlay.
 *
 * It appears immediately above the active IME when Accessibility confirms that
 * a non-sensitive editable field owns focus. It does not replace Gboard/Samsung
 * Keyboard and never submits text.
 */
final class VoiceInputCompanion {
    private static VoiceInputCompanion instance;

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View bar;
    private WindowManager.LayoutParams params;
    private int lastImeTop = -1;
    private String lastPackage = "";

    static synchronized VoiceInputCompanion getInstance(Context context) {
        if (instance == null) {
            instance = new VoiceInputCompanion(
                    context.getApplicationContext());
        }
        return instance;
    }

    private VoiceInputCompanion(Context context) {
        this.context = context;
        this.windowManager = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
    }

    void update(
            final boolean shouldShow,
            final int imeTop,
            final String packageName) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!shouldShow
                        || !AppConfig.isVoiceInputCompanionEnabled(context)
                        || !canDrawOverlays()) {
                    hideInternal();
                    return;
                }

                int safeTop = Math.max(dp(48), imeTop);
                String pkg = packageName == null ? "" : packageName;

                if (bar == null) {
                    showInternal(safeTop, pkg);
                    return;
                }

                if (safeTop != lastImeTop) {
                    lastImeTop = safeTop;
                    params.y = Math.max(0, safeTop - dp(52));
                    try {
                        windowManager.updateViewLayout(bar, params);
                    } catch (Exception ignored) {}
                }
                lastPackage = pkg;
            }
        });
    }

    void hide() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                hideInternal();
            }
        });
    }

    private void showInternal(int imeTop, String packageName) {
        if (windowManager == null || !canDrawOverlays()) return;

        hideInternal();
        lastImeTop = imeTop;
        lastPackage = packageName == null ? "" : packageName;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(8), dp(4), dp(8), dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(244, 24, 24, 27));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#3F3F46"));
        root.setBackground(bg);

        TextView brand = makeLabel("Crew");
        brand.setTextColor(Color.parseColor("#67E8F9"));
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(dp(10), 0, dp(12), 0);
        root.addView(
                brand,
                new LinearLayout.LayoutParams(
                        dp(58),
                        dp(40)));

        TextView dictate = makeAction(
                "🎙 說話",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        begin("dictate");
                    }
                });
        root.addView(
                dictate,
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1f));

        TextView rewrite = makeAction(
                "✨ 潤飾",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        begin("rewrite");
                    }
                });
        root.addView(
                rewrite,
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1f));

        TextView translate = makeAction(
                "🌐 翻譯",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        begin("translate");
                    }
                });
        root.addView(
                translate,
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1f));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(48),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = Math.max(0, imeTop - dp(52));

        bar = root;
        try {
            windowManager.addView(bar, params);
        } catch (Exception error) {
            bar = null;
            params = null;
        }
    }

    private void begin(String mode) {
        JSONObjectSafe armed = JSONObjectSafe.from(
                FocusedInputRuntime.arm(context, mode));
        if (!armed.success) {
            Toast.makeText(
                    context,
                    armed.message(),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        boolean accepted = NativeLiveService.beginFocusedInputCompanion(
                context,
                mode);

        if (!accepted) {
            FocusedInputRuntime.clear();
            Toast.makeText(
                    context,
                    "請確認 Gemini API Key 與麥克風權限",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        FloatingBubbleManager manager =
                FloatingBubbleManager.getInstance(context);

        if ("dictate".equals(mode)) {
            manager.showCompactStatus(
                    "AI 語音輸入",
                    "請直接說你想輸入的內容");
        } else if ("rewrite".equals(mode)) {
            manager.showCompactStatus(
                    "AI 語音輸入",
                    "正在潤飾目前文字");
        } else {
            manager.showCompactStatus(
                    "AI 語音輸入",
                    "正在翻譯目前文字");
        }
    }

    private TextView makeAction(
            String text,
            View.OnClickListener listener) {
        TextView view = makeLabel(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setClickable(true);
        view.setOnClickListener(listener);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(30, 255, 255, 255));
        bg.setCornerRadius(dp(11));
        view.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0,
                dp(40),
                1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView makeLabel(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12.5f);
        view.setSingleLine(true);
        return view;
    }

    private void hideInternal() {
        if (bar != null) {
            try {
                windowManager.removeViewImmediate(bar);
            } catch (Exception ignored) {}
        }
        bar = null;
        params = null;
        lastImeTop = -1;
        lastPackage = "";
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context);
    }

    private int dp(float value) {
        return (int) (
                value
                        * context.getResources()
                                .getDisplayMetrics()
                                .density
                        + 0.5f);
    }

    /**
     * Tiny wrapper to keep UI code independent from org.json exception noise.
     */
    private static final class JSONObjectSafe {
        final boolean success;
        final String error;

        private JSONObjectSafe(boolean success, String error) {
            this.success = success;
            this.error = error == null ? "" : error;
        }

        static JSONObjectSafe from(org.json.JSONObject value) {
            if (value == null) {
                return new JSONObjectSafe(false, "INPUT_UNAVAILABLE");
            }
            return new JSONObjectSafe(
                    value.optBoolean("success", false),
                    value.optString("error", ""));
        }

        String message() {
            if ("SENSITIVE_INPUT_BLOCKED".equals(error)) {
                return "敏感輸入欄位不提供 AI 語音輸入";
            }
            if ("FOCUSED_INPUT_TARGET_CHANGED".equals(error)) {
                return "輸入欄位已改變，請重新點一下";
            }
            return "目前沒有可用的輸入欄位";
        }
    }
}
