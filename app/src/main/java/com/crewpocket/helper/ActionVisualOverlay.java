package com.crewpocket.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;

/**
 * Lightweight, non-interactive visual feedback for Runtime-owned phone actions.
 *
 * This is intentionally an observation layer only: callers fire-and-forget an
 * effect immediately before the real Accessibility action. No action waits for
 * an animation and no model/runtime decision depends on this overlay.
 */
final class ActionVisualOverlay {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WindowManager windowManager;
    private static ActionVisualView view;
    private static long hideToken = 0L;

    private ActionVisualOverlay() {}

    static void showTap(Context context, float x, float y) {
        show(context, Event.tap(x, y, null), false);
    }

    static void showTapTarget(Context context, Rect bounds) {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;
        Rect copy = new Rect(bounds);
        show(
                context,
                Event.tap(copy.centerX(), copy.centerY(), new RectF(copy)),
                false);
    }

    static void showSwipe(
            Context context,
            float x1,
            float y1,
            float x2,
            float y2,
            long durationMs) {
        show(
                context,
                Event.swipe(
                        x1,
                        y1,
                        x2,
                        y2,
                        durationMs),
                false);
    }

    /**
     * Replays a clear user-facing swipe trace after Runtime takes its clean
     * post-action screenshot. This keeps Vision free of Crew's own overlay
     * while leaving the human enough time to see what just happened.
     */
    static void showSwipeFeedback(
            Context context,
            String rawDirection,
            String rawDistance) {
        if (context == null) return;
        android.util.DisplayMetrics metrics =
                context.getResources().getDisplayMetrics();
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);

        String direction = rawDirection == null
                ? "up"
                : rawDirection.trim().toLowerCase();
        String distance = rawDistance == null
                ? "normal"
                : rawDistance.trim().toLowerCase();

        float x1 = width * 0.50f;
        float y1 = height * 0.74f;
        float x2 = width * 0.50f;
        float y2 = height * 0.22f;

        if ("down".equals(direction)) {
            y1 = height * 0.22f;
            y2 = height * 0.74f;
        } else if ("left".equals(direction)) {
            x1 = width * 0.87f;
            y1 = height * 0.50f;
            x2 = width * 0.13f;
            y2 = height * 0.50f;
        } else if ("right".equals(direction)) {
            x1 = width * 0.13f;
            y1 = height * 0.50f;
            x2 = width * 0.87f;
            y2 = height * 0.50f;
        }

        if ("long".equals(distance)
                || "page".equals(distance)
                || "fast".equals(distance)) {
            if ("up".equals(direction)) {
                y1 = height * 0.87f;
                y2 = height * 0.13f;
            } else if ("down".equals(direction)) {
                y1 = height * 0.13f;
                y2 = height * 0.87f;
            } else if ("left".equals(direction)) {
                x1 = width * 0.94f;
                x2 = width * 0.06f;
            } else if ("right".equals(direction)) {
                x1 = width * 0.06f;
                x2 = width * 0.94f;
            }
        } else if ("short".equals(distance)
                || "little".equals(distance)) {
            if ("up".equals(direction)) {
                y1 = height * 0.58f;
                y2 = height * 0.38f;
            } else if ("down".equals(direction)) {
                y1 = height * 0.38f;
                y2 = height * 0.58f;
            } else if ("left".equals(direction)) {
                x1 = width * 0.66f;
                x2 = width * 0.34f;
            } else if ("right".equals(direction)) {
                x1 = width * 0.34f;
                x2 = width * 0.66f;
            }
        }

        show(context, Event.swipe(x1, y1, x2, y2, 320L), false);
    }

    static void showScroll(Context context, String direction) {
        show(context, Event.scroll(direction), false);
    }

    static void showTyping(Context context, Rect bounds) {
        RectF target = null;
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            target = new RectF(bounds);
        }
        show(context, Event.typing(target), false);
    }

    static void showLooking(Context context) {
        show(context, Event.looking(), true);
    }

    static void dismiss() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    hideToken++;
                    dismissLocked();
                }
            }
        });
    }

    private static void show(
            Context context,
            final Event event,
            final boolean lowPriority) {
        if (context == null || event == null) return;
        final Context app = context.getApplicationContext();

        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (!canShow(app)) return;
                    try {
                        ensureAttachedLocked(app);
                        if (view == null) return;
                        if (!view.setEvent(event, lowPriority)) return;

                        final long token = ++hideToken;
                        MAIN.postDelayed(new Runnable() {
                            @Override public void run() {
                                synchronized (LOCK) {
                                    if (token == hideToken) {
                                        dismissLocked();
                                    }
                                }
                            }
                        }, event.durationMs + 80L);
                    } catch (Exception ignored) {
                        dismissLocked();
                    }
                }
            }
        });
    }

    private static boolean canShow(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            return Settings.canDrawOverlays(context);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void ensureAttachedLocked(Context context) {
        if (view != null && windowManager != null) return;

        windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        view = new ActionVisualView(context);
        view.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(view, params);
    }

    private static void dismissLocked() {
        if (windowManager != null && view != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Exception ignored) {}
        }
        view = null;
        windowManager = null;
    }

    private enum Kind {
        TAP,
        SWIPE,
        SCROLL,
        TYPING,
        LOOKING
    }

    private static final class Event {
        final Kind kind;
        final long startedAtMs;
        final long durationMs;
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final RectF target;
        final String direction;

        private Event(
                Kind kind,
                long durationMs,
                float x1,
                float y1,
                float x2,
                float y2,
                RectF target,
                String direction) {
            this.kind = kind;
            this.startedAtMs = SystemClock.uptimeMillis();
            this.durationMs = durationMs;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.target = target == null ? null : new RectF(target);
            this.direction = direction == null ? "" : direction;
        }

        static Event tap(float x, float y, RectF target) {
            return new Event(Kind.TAP, 520L, x, y, x, y, target, "");
        }

        static Event swipe(
                float x1,
                float y1,
                float x2,
                float y2,
                long durationMs) {
            // Visual feedback deliberately outlives the physical gesture.
            // The action itself remains ~260-650 ms; the overlay gets a slower
            // travel plus a short end-state hold so it is actually readable.
            long visualDuration = Math.max(
                    980L,
                    Math.min(1_150L, durationMs + 720L));
            return new Event(
                    Kind.SWIPE,
                    visualDuration,
                    x1,
                    y1,
                    x2,
                    y2,
                    null,
                    "");
        }

        static Event scroll(String direction) {
            return new Event(
                    Kind.SCROLL,
                    620L,
                    0f,
                    0f,
                    0f,
                    0f,
                    null,
                    direction == null ? "up" : direction);
        }

        static Event typing(RectF target) {
            return new Event(
                    Kind.TYPING,
                    720L,
                    0f,
                    0f,
                    0f,
                    0f,
                    target,
                    "");
        }

        static Event looking() {
            return new Event(
                    Kind.LOOKING,
                    460L,
                    0f,
                    0f,
                    0f,
                    0f,
                    null,
                    "");
        }
    }

    private static final class ActionVisualView extends View {
        private final Paint primary = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint secondary = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] screenOrigin = new int[2];

        private Event event;

        ActionVisualView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(false);
            setFocusable(false);

            primary.setStrokeCap(Paint.Cap.ROUND);
            primary.setStrokeJoin(Paint.Join.ROUND);
            secondary.setStrokeCap(Paint.Cap.ROUND);
            secondary.setStrokeJoin(Paint.Join.ROUND);

            text.setTypeface(Typeface.create(
                    "sans-serif-medium",
                    Typeface.NORMAL));
            text.setTextSize(sp(12.5f));
            text.setColor(Color.WHITE);

            chip.setStyle(Paint.Style.FILL);
            chip.setColor(Color.argb(205, 10, 18, 28));
        }

        boolean setEvent(Event next, boolean lowPriority) {
            long now = SystemClock.uptimeMillis();
            if (lowPriority && event != null) {
                long age = now - event.startedAtMs;
                if (age < event.durationMs && event.kind != Kind.LOOKING) {
                    return false;
                }
                if (event.kind == Kind.LOOKING && age < 180L) {
                    return false;
                }
            }
            event = next;
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Event current = event;
            if (current == null) return;

            long now = SystemClock.uptimeMillis();
            float progress = clamp01(
                    (float) (now - current.startedAtMs)
                            / Math.max(1f, (float) current.durationMs));

            try {
                getLocationOnScreen(screenOrigin);
            } catch (Exception ignored) {
                screenOrigin[0] = 0;
                screenOrigin[1] = 0;
            }

            if (current.kind == Kind.TAP) {
                drawTap(canvas, current, progress);
            } else if (current.kind == Kind.SWIPE) {
                drawSwipe(canvas, current, progress);
            } else if (current.kind == Kind.SCROLL) {
                drawScroll(canvas, current, progress);
            } else if (current.kind == Kind.TYPING) {
                drawTyping(canvas, current, progress);
            } else if (current.kind == Kind.LOOKING) {
                drawLooking(canvas, progress);
            }

            if (progress < 1f) {
                postInvalidateOnAnimation();
            }
        }

        private void drawTap(Canvas canvas, Event e, float p) {
            float cx = e.x1 - screenOrigin[0];
            float cy = e.y1 - screenOrigin[1];

            if (e.target != null) {
                RectF target = localRect(e.target);
                float pulse = 0.55f + 0.45f
                        * (float) Math.sin(Math.min(1f, p) * Math.PI);
                primary.setStyle(Paint.Style.STROKE);
                primary.setStrokeWidth(dp(2.2f));
                primary.setColor(Color.argb(
                        Math.round(235f * pulse),
                        34,
                        211,
                        238));
                canvas.drawRoundRect(target, dp(9), dp(9), primary);
            }

            float eased = 1f - (1f - p) * (1f - p);
            float radius = dp(10f + 34f * eased);
            int alpha = Math.max(0, Math.round(220f * (1f - p)));

            primary.setStyle(Paint.Style.STROKE);
            primary.setStrokeWidth(dp(2.4f));
            primary.setColor(Color.argb(alpha, 34, 211, 238));
            canvas.drawCircle(cx, cy, radius, primary);

            secondary.setStyle(Paint.Style.FILL);
            secondary.setColor(Color.argb(
                    Math.max(0, Math.round(245f * (1f - p * 0.75f))),
                    255,
                    255,
                    255));
            canvas.drawCircle(cx, cy, dp(4.2f), secondary);
        }

        private void drawSwipe(Canvas canvas, Event e, float p) {
            float x1 = e.x1 - screenOrigin[0];
            float y1 = e.y1 - screenOrigin[1];
            float x2 = e.x2 - screenOrigin[0];
            float y2 = e.y2 - screenOrigin[1];

            // Travel for the first ~62%, then hold the completed trace long
            // enough for the user to register direction and endpoint.
            float motion = clamp01(p / 0.62f);
            float hold = clamp01((p - 0.62f) / 0.38f);
            float eased = (float) Math.sin(
                    motion * (Math.PI / 2.0));
            float cx = x1 + (x2 - x1) * eased;
            float cy = y1 + (y2 - y1) * eased;
            float fade = 1f - hold * 0.68f;

            // Soft halo makes the trail readable on Maps and other visually
            // busy screens without covering the underlying UI.
            secondary.setStyle(Paint.Style.STROKE);
            secondary.setStrokeWidth(dp(12f));
            secondary.setColor(Color.argb(
                    Math.max(22, Math.round(76f * fade)),
                    34,
                    211,
                    238));
            canvas.drawLine(x1, y1, cx, cy, secondary);

            primary.setStyle(Paint.Style.STROKE);
            primary.setStrokeWidth(dp(6.2f));
            primary.setColor(Color.argb(
                    Math.max(105, Math.round(238f * fade)),
                    34,
                    211,
                    238));
            canvas.drawLine(x1, y1, cx, cy, primary);

            // Large moving pointer + halo.
            secondary.setStyle(Paint.Style.FILL);
            secondary.setColor(Color.argb(
                    Math.max(24, Math.round(82f * fade)),
                    34,
                    211,
                    238));
            canvas.drawCircle(cx, cy, dp(14f), secondary);

            secondary.setColor(Color.argb(
                    Math.max(120, Math.round(250f * fade)),
                    255,
                    255,
                    255));
            canvas.drawCircle(cx, cy, dp(7.2f), secondary);

            primary.setColor(Color.argb(
                    Math.max(105, Math.round(238f * fade)),
                    34,
                    211,
                    238));
            drawArrowHead(canvas, x1, y1, cx, cy);

            if (motion >= 1f) {
                primary.setStyle(Paint.Style.STROKE);
                primary.setStrokeWidth(dp(2.6f));
                primary.setColor(Color.argb(
                        Math.max(20, Math.round(150f * (1f - hold))),
                        34,
                        211,
                        238));
                canvas.drawCircle(
                        x2,
                        y2,
                        dp(18f + 10f * hold),
                        primary);
            }

            drawSwipeLabel(canvas, x1, y1, x2, y2, fade);
        }

        private void drawSwipeLabel(
                Canvas canvas,
                float x1,
                float y1,
                float x2,
                float y2,
                float fade) {
            float dx = x2 - x1;
            float dy = y2 - y1;
            String arrow;
            if (Math.abs(dx) >= Math.abs(dy)) {
                arrow = dx >= 0f ? "→" : "←";
            } else {
                arrow = dy >= 0f ? "↓" : "↑";
            }

            String label = "Swipe " + arrow;
            float padX = dp(10f);
            float padY = dp(6f);
            float width = text.measureText(label) + padX * 2f;
            float height = text.getTextSize() + padY * 2f;
            float centerX = (x1 + x2) * 0.5f;
            float centerY = (y1 + y2) * 0.5f - dp(30f);
            float left = Math.max(
                    dp(8f),
                    Math.min(
                            getWidth() - width - dp(8f),
                            centerX - width * 0.5f));
            float top = Math.max(
                    dp(8f),
                    Math.min(
                            getHeight() - height - dp(8f),
                            centerY - height * 0.5f));

            chip.setColor(Color.argb(
                    Math.max(75, Math.round(218f * fade)),
                    10,
                    18,
                    28));
            RectF box = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(box, dp(10f), dp(10f), chip);

            text.setColor(Color.argb(
                    Math.max(125, Math.round(255f * fade)),
                    255,
                    255,
                    255));
            canvas.drawText(
                    label,
                    box.left + padX,
                    box.top + padY + text.getTextSize() * 0.82f,
                    text);

            // Shared paints are reused by the next event.
            text.setColor(Color.WHITE);
            chip.setColor(Color.argb(205, 10, 18, 28));
        }

        private void drawScroll(Canvas canvas, Event e, float p) {
            String direction = e.direction == null
                    ? "up"
                    : e.direction.toLowerCase();
            boolean vertical =
                    !"left".equals(direction) && !"right".equals(direction);
            float inset = dp(28f);
            float cx = getWidth() - inset;
            float cy = getHeight() * 0.52f;
            float travel = dp(72f);
            float phase = p < 0.5f ? p * 2f : (1f - p) * 2f;

            float sx = cx;
            float sy = cy;
            float ex = cx;
            float ey = cy;
            String arrow = "↑";
            if ("down".equals(direction) || "backward".equals(direction)) {
                sy = cy - travel * 0.5f;
                ey = cy + travel * (phase - 0.5f);
                arrow = "↓";
            } else if ("left".equals(direction)) {
                sx = cx + travel * 0.5f;
                ex = cx - travel * (phase - 0.5f);
                arrow = "←";
            } else if ("right".equals(direction)) {
                sx = cx - travel * 0.5f;
                ex = cx + travel * (phase - 0.5f);
                arrow = "→";
            } else {
                sy = cy + travel * 0.5f;
                ey = cy - travel * (phase - 0.5f);
            }

            primary.setStyle(Paint.Style.STROKE);
            primary.setStrokeWidth(dp(3.2f));
            primary.setColor(Color.argb(205, 34, 211, 238));
            canvas.drawLine(sx, sy, ex, ey, primary);

            secondary.setStyle(Paint.Style.FILL);
            secondary.setColor(Color.WHITE);
            canvas.drawCircle(ex, ey, dp(4f), secondary);

            String label = "Searching " + arrow;
            float padX = dp(9f);
            float padY = dp(6f);
            float width = text.measureText(label) + padX * 2f;
            float height = text.getTextSize() + padY * 2f;
            float right = getWidth() - dp(14f);
            float top = vertical
                    ? cy - travel - height
                    : cy + dp(22f);
            RectF box = new RectF(
                    right - width,
                    top,
                    right,
                    top + height);
            canvas.drawRoundRect(box, dp(9f), dp(9f), chip);
            canvas.drawText(
                    label,
                    box.left + padX,
                    box.top + padY + text.getTextSize() * 0.82f,
                    text);
        }

        private void drawTyping(Canvas canvas, Event e, float p) {
            RectF target = e.target == null ? null : localRect(e.target);
            if (target != null) {
                float pulse = 0.65f + 0.35f
                        * (float) Math.sin(p * Math.PI * 2.0);
                primary.setStyle(Paint.Style.STROKE);
                primary.setStrokeWidth(dp(2.2f));
                primary.setColor(Color.argb(
                        Math.round(225f * pulse),
                        34,
                        211,
                        238));
                canvas.drawRoundRect(target, dp(9f), dp(9f), primary);
            }

            String label = "Typing";
            float padX = dp(10f);
            float padY = dp(6f);
            float width = text.measureText(label) + padX * 2f;
            float height = text.getTextSize() + padY * 2f;
            float left = target == null
                    ? dp(18f)
                    : Math.max(
                            dp(8f),
                            Math.min(
                                    getWidth() - width - dp(8f),
                                    target.left));
            float top = target == null
                    ? getHeight() * 0.42f
                    : Math.max(dp(8f), target.top - height - dp(7f));
            RectF box = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(box, dp(9f), dp(9f), chip);
            canvas.drawText(
                    label,
                    box.left + padX,
                    box.top + padY + text.getTextSize() * 0.82f,
                    text);
        }

        private void drawLooking(Canvas canvas, float p) {
            float left = getWidth() * 0.08f;
            float right = getWidth() * 0.92f;
            float top = getHeight() * 0.18f;
            float bottom = getHeight() * 0.82f;
            float eased = p * p * (3f - 2f * p);
            float y = top + (bottom - top) * eased;

            primary.setStyle(Paint.Style.STROKE);
            primary.setStrokeWidth(dp(2f));
            primary.setColor(Color.argb(
                    Math.max(20, Math.round(170f * (1f - p * 0.5f))),
                    34,
                    211,
                    238));
            canvas.drawLine(left, y, right, y, primary);

            secondary.setStyle(Paint.Style.FILL);
            secondary.setColor(Color.argb(45, 34, 211, 238));
            canvas.drawRect(left, y - dp(5f), right, y + dp(5f), secondary);
        }

        private void drawArrowHead(
                Canvas canvas,
                float fromX,
                float fromY,
                float toX,
                float toY) {
            float dx = toX - fromX;
            float dy = toY - fromY;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < dp(12f)) return;

            float ux = dx / len;
            float uy = dy / len;
            float size = dp(14f);
            float px = -uy;
            float py = ux;

            float bx = toX - ux * size;
            float by = toY - uy * size;
            primary.setStrokeWidth(dp(2.8f));
            canvas.drawLine(
                    toX,
                    toY,
                    bx + px * size * 0.55f,
                    by + py * size * 0.55f,
                    primary);
            canvas.drawLine(
                    toX,
                    toY,
                    bx - px * size * 0.55f,
                    by - py * size * 0.55f,
                    primary);
        }

        private RectF localRect(RectF screenRect) {
            RectF out = new RectF(screenRect);
            out.offset(-screenOrigin[0], -screenOrigin[1]);
            return out;
        }

        private float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float sp(float value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }
    }
}
