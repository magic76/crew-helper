package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/** Non-interactive overlay that labels real Accessibility clickable bounds. */
final class ElementReferenceOverlay {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WindowManager windowManager;
    private static ReferenceView view;

    private ElementReferenceOverlay() {}

    static void show(Context context,
                     List<ElementReferenceRuntime.Option> options,
                     String title) {
        if (context == null || options == null || options.isEmpty()) return;
        final Context app = context.getApplicationContext();
        final ArrayList<ElementReferenceRuntime.Option> snapshot =
                new ArrayList<ElementReferenceRuntime.Option>(options);
        final String safeTitle = clip(title, 72);
        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    dismissLocked();
                    try {
                        windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                        if (windowManager == null) return;

                        view = new ReferenceView(app, snapshot, safeTitle);
                        view.setImportantForAccessibility(
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        params.gravity = Gravity.TOP | Gravity.START;
                        windowManager.addView(view, params);
                    } catch (Exception ignored) {
                        dismissLocked();
                    }
                }
            }
        });
    }

    static void dismiss() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) { dismissLocked(); }
            }
        });
    }

    private static void dismissLocked() {
        if (windowManager != null && view != null) {
            try { windowManager.removeViewImmediate(view); }
            catch (Exception ignored) {}
        }
        view = null;
        windowManager = null;
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static final class ReferenceView extends View {
        private final List<ElementReferenceRuntime.Option> options;
        private final String title;
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leader = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<RectF> placedBadges = new ArrayList<RectF>();
        private final int[] screenOrigin = new int[2];

        ReferenceView(Context context,
                      List<ElementReferenceRuntime.Option> options,
                      String title) {
            super(context);
            this.options = options;
            this.title = title;

            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(dp(2));
            outline.setColor(Color.argb(235, 255, 255, 255));

            chip.setStyle(Paint.Style.FILL);
            chip.setColor(Color.argb(225, 18, 18, 22));

            text.setColor(Color.WHITE);
            text.setTextSize(sp(13));
            text.setTypeface(Typeface.DEFAULT_BOLD);

            leader.setStyle(Paint.Style.STROKE);
            leader.setStrokeWidth(dp(1.4f));
            leader.setColor(Color.argb(190, 255, 255, 255));

            banner.setStyle(Paint.Style.FILL);
            banner.setColor(Color.argb(225, 18, 18, 22));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            placedBadges.clear();
            if (options == null || options.isEmpty()) return;

            updateScreenOrigin();
            for (ElementReferenceRuntime.Option option : options) {
                if (option == null) continue;
                RectF bounds = screenToLocal(
                        option.left, option.top, option.right, option.bottom);
                canvas.drawRoundRect(bounds, dp(6), dp(6), outline);
                drawAdaptiveBadge(canvas, option, bounds);
            }
            drawBanner(canvas);
        }

        /** Accessibility bounds are screen coordinates; Canvas coordinates are local to this overlay. */
        private void updateScreenOrigin() {
            screenOrigin[0] = 0;
            screenOrigin[1] = 0;
            try {
                getLocationOnScreen(screenOrigin);
            } catch (Exception ignored) {
                screenOrigin[0] = 0;
                screenOrigin[1] = 0;
            }
        }

        private RectF screenToLocal(float left, float top, float right, float bottom) {
            return new RectF(
                    left - screenOrigin[0],
                    top - screenOrigin[1],
                    right - screenOrigin[0],
                    bottom - screenOrigin[1]);
        }

        private void drawAdaptiveBadge(
                Canvas canvas,
                ElementReferenceRuntime.Option option,
                RectF target) {
            String label = String.valueOf(option.number);
            float padX = dp(7);
            float padY = dp(5);
            float h = text.getTextSize() + padY * 2;
            float w = Math.max(dp(30), text.measureText(label) + padX * 2);

            float[][] anchors = new float[][] {
                    {target.left + dp(4), target.top + dp(4)},
                    {target.right - w - dp(4), target.top + dp(4)},
                    {target.left + dp(4), target.bottom - h - dp(4)},
                    {target.right - w - dp(4), target.bottom - h - dp(4)},
                    {target.centerX() - w / 2f, target.centerY() - h / 2f}
            };

            RectF badge = null;
            for (float[] anchor : anchors) {
                RectF candidate = clampBadge(anchor[0], anchor[1], w, h);
                if (!overlapsPlaced(candidate)) {
                    badge = candidate;
                    break;
                }
            }

            if (badge == null) {
                float x = target.left + dp(4);
                float y = target.top + dp(4);
                for (int attempt = 0; attempt < 12; attempt++) {
                    RectF candidate = clampBadge(x, y + attempt * (h + dp(3)), w, h);
                    if (!overlapsPlaced(candidate)) {
                        badge = candidate;
                        break;
                    }
                }
            }
            if (badge == null) badge = clampBadge(target.left, target.top, w, h);

            placedBadges.add(new RectF(badge));

            float badgeCx = badge.centerX();
            float badgeCy = badge.centerY();
            float targetCx = target.centerX();
            float targetCy = target.centerY();
            if (Math.abs(badgeCx - targetCx) > dp(20)
                    || Math.abs(badgeCy - targetCy) > dp(20)) {
                canvas.drawLine(badgeCx, badgeCy, targetCx, targetCy, leader);
            }

            canvas.drawRoundRect(badge, dp(9), dp(9), chip);
            canvas.drawText(
                    label,
                    badge.left + (badge.width() - text.measureText(label)) / 2f,
                    badge.top + (badge.height() - text.getTextSize()) / 2f
                            + text.getTextSize() * 0.82f,
                    text);
        }

        private RectF clampBadge(float left, float top, float width, float height) {
            float margin = dp(4);
            float maxLeft = Math.max(margin, getWidth() - width - margin);
            float maxTop = Math.max(margin, getHeight() - height - margin);
            float l = Math.max(margin, Math.min(maxLeft, left));
            float t = Math.max(margin, Math.min(maxTop, top));
            return new RectF(l, t, l + width, t + height);
        }

        private boolean overlapsPlaced(RectF candidate) {
            RectF expanded = new RectF(candidate);
            expanded.inset(-dp(3), -dp(3));
            for (RectF used : placedBadges) {
                if (RectF.intersects(expanded, used)) return true;
            }
            return false;
        }

        private void drawBanner(Canvas canvas) {
            String label = title == null || title.isEmpty()
                    ? "可點擊元素 · 說編號"
                    : title;
            float margin = dp(12);
            float padX = dp(12);
            float padY = dp(8);
            float width = Math.min(
                    Math.max(dp(80), getWidth() - margin * 2),
                    text.measureText(label) + padX * 2);
            float height = text.getTextSize() + padY * 2;
            RectF box = new RectF(margin, margin, margin + width, margin + height);
            canvas.drawRoundRect(box, dp(12), dp(12), banner);

            String draw = label;
            while (draw.length() > 1 && text.measureText(draw) > width - padX * 2) {
                draw = draw.substring(0, draw.length() - 1);
            }
            canvas.drawText(
                    draw,
                    margin + padX,
                    margin + padY + text.getTextSize() * 0.82f,
                    text);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float sp(float value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }
    }
}
