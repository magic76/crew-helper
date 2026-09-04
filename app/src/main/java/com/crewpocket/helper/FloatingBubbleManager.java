package com.crewpocket.helper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

public class FloatingBubbleManager {
    public interface SendCallback {
        void onResult(boolean success, String detail);
    }
    public interface CaptureCallback {
        void onResult(boolean success, String detail);
    }
    private static FloatingBubbleManager instance;
    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler;
    private final Vibrator vibrator;

    private FluidBubbleView bubbleView = null;
    private View voiceControlView = null;
    private boolean voiceControlsOpening = false;
    private WindowManager.LayoutParams voiceControlParams = null;
    private static class DockIconButton extends View {
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

    private DockIconButton voiceCallButton = null;
    private DockIconButton voiceCameraButton = null;
    private DockIconButton voiceScreenButton = null;
    private DockIconButton voiceMuteButton = null;
    private TextView voiceInterruptionButton = null;
    private TextView voiceWakeButton = null;
    private TextView voiceSensitivityButton = null;
    private TextView voicePresetButton = null;
    private TextView voiceOutputButton = null;
    private TextView voiceSettingsToggleButton = null;
    private LinearLayout voiceSettingsPanel = null;
    private TextView voiceStopAgentButton = null;
    private LinearLayout voiceSettingsChoices = null;
    private TextView voiceStatusText = null;
    private TextView voiceMeterText = null;
    private TextView voiceTranscriptText = null;
    private View dialogView = null;
    private WindowManager.LayoutParams bubbleParams = null;
    private WindowManager.LayoutParams dialogParams = null;
    private boolean isDialogShowing = false;
    private String currentState = "IDLE";
    private boolean nativeLiveRequested = false;
    private String latestLiveStatus = "待命";
    private double latestMicDbfs = -96d;
    private boolean latestMicSending = false;
    private String latestLiveTranscript = "等待對話開始…";
    private String latestLiveTranscriptRole = "";
    private Runnable transcriptRefreshRunnable = null;
    private TextView dialogStatusText = null;
    private Button dialogStopButton = null;
    // Image data remains in Helper until the user explicitly sends it to the
    // connected Crew Pocket server; no server-side screenshot command is used.
    private String pendingImageData = null;
    private Runnable safetyTimeoutRunnable = null;

    private FloatingBubbleManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.vibrator = (Vibrator) this.context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static synchronized FloatingBubbleManager getInstance(Context context) {
        if (instance == null) {
            instance = new FloatingBubbleManager(context);
        }
        return instance;
    }

    public static synchronized FloatingBubbleManager getInstance() {
        return instance;
    }

    private static android.os.PowerManager.WakeLock appWakeLock = null;
    private static boolean isKeepAwakeActive = false;

    public static synchronized boolean isKeepAwakeActive() {
        return appWakeLock != null && appWakeLock.isHeld();
    }

    public static synchronized boolean toggleKeepAwake(Context ctx) {
        isKeepAwakeActive = !isKeepAwakeActive;
        try {
            if (isKeepAwakeActive) {
                if (appWakeLock == null && ctx != null) {
                    android.os.PowerManager pm = (android.os.PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        appWakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ON_AFTER_RELEASE,
                            "CrewPocket:ScreenKeepAwake"
                        );
                        appWakeLock.setReferenceCounted(false);
                    }
                }
                if (appWakeLock != null && !appWakeLock.isHeld()) {
                    appWakeLock.acquire(4 * 60 * 60 * 1000L); // Max 4h safe limit
                }
            } else {
                if (appWakeLock != null && appWakeLock.isHeld()) {
                    appWakeLock.release();
                }
            }
        } catch (SecurityException error) {
            android.util.Log.e("FloatingBubble", "Keep Awake requires WAKE_LOCK permission", error);
            isKeepAwakeActive = false;
        } catch (Exception error) {
            android.util.Log.e("FloatingBubble", "Unable to change Keep Awake state", error);
            isKeepAwakeActive = appWakeLock != null && appWakeLock.isHeld();
        }
        isKeepAwakeActive = appWakeLock != null && appWakeLock.isHeld();
        return isKeepAwakeActive;
    }

    public void updateWakeButtonUi(TextView btn, boolean active) {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (active) {
            bg.setColor(Color.parseColor("#F59E0B")); // High-contrast Solid Amber 500
            bg.setStroke(dp(1.5f), Color.parseColor("#FEF08A")); // Yellow 200
            btn.setText(I18n.get(context, "☀️ 常亮 (ON)", "☀️ Awake (ON)"));
            btn.setTextColor(Color.parseColor("#0F172A")); // Bold Slate 950
            btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(Color.parseColor("#1E293B")); // Slate 800
            bg.setStroke(dp(1), Color.parseColor("#475569")); // Slate 600
            btn.setText(I18n.get(context, "☀️ 常亮 (OFF)", "☀️ Awake (OFF)"));
            btn.setTextColor(Color.parseColor("#94A3B8")); // Slate 400
            btn.setTypeface(android.graphics.Typeface.DEFAULT);
        }
        btn.setBackground(bg);
    }

    public Context getContext() {
        return context;
    }

    public boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    // 📳 Haptic Vibrations
    public void vibrateShort() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                
                    vibrator.vibrate(35);
                
            }
        } catch (Exception ignored) {}
    }

    public void vibrateSuccess() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    long[] timings = new long[]{0, 25, 50, 25};
                    int[] amplitudes = new int[]{0, 160, 0, 200};
                    vibrator.vibrate(timings, -1);
                } else {
                    vibrator.vibrate(new long[]{0, 25, 50, 25}, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isDocked = false;
    private ValueAnimator dockAnimator = null;
    private final Handler autoDockHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoDockRunnable = new Runnable() {
        @Override
        public void run() {
            autoDockBubble();
        }
    };

    // 🌟 Show Floating Ball with Smart Auto-Dock & Ghost Opacity
    public void hideBubble() {
        autoDockHandler.removeCallbacks(autoDockRunnable);
        if (dockAnimator != null) {
            dockAnimator.cancel();
            dockAnimator = null;
        }
        if (bubbleView != null) {
            try { windowManager.removeView(bubbleView); } catch(Exception e){}
            bubbleView = null;
        }
    }

    public boolean isBubbleShowing() {
        return bubbleView != null;
    }

    public void scheduleAutoDock() {
        autoDockHandler.removeCallbacks(autoDockRunnable);
        if (bubbleView != null && !isDocked) {
            autoDockHandler.postDelayed(autoDockRunnable, 3000);
        }
    }

    public void wakeBubbleFromDock() {
        autoDockHandler.removeCallbacks(autoDockRunnable);
        if (bubbleView == null || bubbleParams == null) return;
        if (dockAnimator != null && dockAnimator.isRunning()) {
            dockAnimator.cancel();
        }
        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        int bSize = bubbleParams.width > 0 ? bubbleParams.width : dp(40);
        int targetX = (bubbleParams.x < screenWidth / 2) ? dp(4) : (screenWidth - bSize - dp(4));

        bubbleParams.x = targetX;
        bubbleView.setAlpha(1.0f);
        try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) {}
        isDocked = false;
    }

    public void autoDockBubble() {
        if (bubbleView == null || bubbleParams == null || isDocked) return;
        if (NativeLiveService.isActive() || nativeLiveRequested) return;

        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        int bSize = bubbleParams.width > 0 ? bubbleParams.width : dp(40);

        final int startX = bubbleParams.x;
        // Slide 58% off-screen, leaving 42% (approx 17dp) as a sleek glowing edge tab
        final int endX = (startX < screenWidth / 2) ? - (bSize * 58 / 100) : (screenWidth - (bSize * 42 / 100));

        if (dockAnimator != null && dockAnimator.isRunning()) {
            dockAnimator.cancel();
        }

        dockAnimator = ValueAnimator.ofFloat(0f, 1f);
        dockAnimator.setDuration(350);
        dockAnimator.setInterpolator(new DecelerateInterpolator());
        dockAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float frac = (float) animation.getAnimatedValue();
                if (bubbleView == null || bubbleParams == null) return;
                bubbleParams.x = (int) (startX + (endX - startX) * frac);
                bubbleView.setAlpha(1.0f - 0.65f * frac); // Smoothly fades from 1.0 to 0.35 (Ghost Mode)
                try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) {}
            }
        });
        dockAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isDocked = true;
            }
        });
        dockAnimator.start();
    }

    public void showBubble() {
        showBubble(null);
    }

    /** Invokes onShown only after the overlay has been attached successfully. */
    public void showBubble(final Runnable onShown) {
        if (!canDrawOverlays()) return;
        if (bubbleView != null) return;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int overlayType = Build.VERSION.SDK_INT >= 26 
                        ? 2038 
                        : WindowManager.LayoutParams.TYPE_PHONE;

                    int size = dp(40);
                    bubbleParams = new WindowManager.LayoutParams(
                        size, size,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    );
                    bubbleParams.gravity = Gravity.TOP | Gravity.START;
                    int screenW = windowManager.getDefaultDisplay().getWidth();
                    int screenH = windowManager.getDefaultDisplay().getHeight();
                    int safeTop = getStatusBarHeight() + dp(12);
                    bubbleParams.x = dp(4);
                    bubbleParams.y = Math.max(safeTop, screenH / 3);

                    bubbleView = new FluidBubbleView(context);
                    bubbleView.setElevation(16f);

                    bubbleView.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float initialTouchX, initialTouchY;
                        private long touchStartTime;
                        private boolean longPressTriggered = false;
                        private final Runnable longPressRunnable = new Runnable() {
                            @Override public void run() {
                                longPressTriggered = true;
                                vibrateSuccess();
                                toggleNativeLive();
                            }
                        };

                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            int screenWidth = windowManager.getDefaultDisplay().getWidth();
                            int screenHeight = windowManager.getDefaultDisplay().getHeight();
                            int topLimit = getStatusBarHeight() + dp(4);
                            int bottomLimit = screenHeight - dp(64);
                            int leftLimit = dp(2);
                            int rightLimit = screenWidth - size - dp(2);

                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = bubbleParams.x;
                                    initialY = bubbleParams.y;
                                    initialTouchX = event.getRawX();
                                    initialTouchY = event.getRawY();
                                    touchStartTime = System.currentTimeMillis();
                                    longPressTriggered = false;
                                    mainHandler.postDelayed(longPressRunnable, 450);
                                    if (isDocked) {
                                        wakeBubbleFromDock();
                                    } else {
                                        autoDockHandler.removeCallbacks(autoDockRunnable);
                                    }
                                    return true;

                                case MotionEvent.ACTION_MOVE:
                                    float moveDist = (float) Math.hypot(event.getRawX() - initialTouchX, event.getRawY() - initialTouchY);
                                    if (moveDist > 18) {
                                        mainHandler.removeCallbacks(longPressRunnable);
                                    }
                                    int targetX = initialX + (int) (event.getRawX() - initialTouchX);
                                    int targetY = initialY + (int) (event.getRawY() - initialTouchY);
                                    bubbleParams.x = Math.max(leftLimit, Math.min(rightLimit, targetX));
                                    bubbleParams.y = Math.max(topLimit, Math.min(bottomLimit, targetY));
                                    bubbleView.setAlpha(1.0f);
                                    isDocked = false;
                                    windowManager.updateViewLayout(bubbleView, bubbleParams);
                                    return true;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    mainHandler.removeCallbacks(longPressRunnable);
                                    if (!longPressTriggered) {
                                        float dx = Math.abs(event.getRawX() - initialTouchX);
                                        float dy = Math.abs(event.getRawY() - initialTouchY);
                                        long duration = System.currentTimeMillis() - touchStartTime;
                                        if (dx < 18 && dy < 18 && duration < 450) {
                                            vibrateShort();
                                            toggleVoiceControls();
                                        }
                                    }
                                    snapBubbleToEdge();
                                    scheduleAutoDock();
                                    return true;
                            }
                            return false;
                        }
                    });

                    windowManager.addView(bubbleView, bubbleParams);
                    isDocked = false;
                    scheduleAutoDock();
                    if (onShown != null) onShown.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private int getStatusBarHeight() {
        try {
            int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) return context.getResources().getDimensionPixelSize(resId);
        } catch (Exception ignored) {}
        return dp(32);
    }

    private void snapBubbleToEdge() {
        if (bubbleView == null || bubbleParams == null) return;
        try {
            int screenWidth = windowManager.getDefaultDisplay().getWidth();
            int screenHeight = windowManager.getDefaultDisplay().getHeight();
            int bSize = bubbleParams.width > 0 ? bubbleParams.width : dp(40);
            int topLimit = getStatusBarHeight() + dp(4);
            int bottomLimit = screenHeight - dp(64);

            // Snap X to left or right margin
            bubbleParams.x = (bubbleParams.x < screenWidth / 2) ? dp(4) : (screenWidth - bSize - dp(4));
            // Clamp Y inside safe screen area
            bubbleParams.y = Math.max(topLimit, Math.min(bottomLimit, bubbleParams.y));
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (Exception ignored) {}
    }

    // 🌊 Set Water Flow / Thinking State
    public void setThinkingState(final boolean thinking) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (safetyTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(safetyTimeoutRunnable);
                    safetyTimeoutRunnable = null;
                }

                if (thinking) {
                    if (bubbleView != null) bubbleView.startWaterFlow();

                    // 40s Safety Auto-Reset
                    safetyTimeoutRunnable = new Runnable() {
                        @Override
                        public void run() {
                            currentState = "IDLE";
                            setThinkingState(false);
                            updateDialogStatus("待命");
                        }
                    };
                    mainHandler.postDelayed(safetyTimeoutRunnable, 40000);
                } else {
                    if (bubbleView != null) bubbleView.stopWaterFlow();
                }
            }
        });
    }

    // 🔔 Real-time Notify Dispatcher from Backend
    public void handleNotify(String state, String text) {
        boolean wasBusy = "THINKING".equals(currentState) || "TOOL".equals(currentState);
        currentState = state == null ? "IDLE" : state.toUpperCase();
        if ("THINKING".equalsIgnoreCase(state)) {
            // Server heartbeats refresh the 40s safety timer. They are not new
            // tasks, so do not vibrate repeatedly while already busy.
            if (!wasBusy) vibrateShort();
            setThinkingState(true);
            String thinkingStatus = text == null || text.isEmpty() ? "AI 回覆中" : "AI 回覆中 · " + text;
            updateDialogStatus(thinkingStatus);
        } else if ("TOOL".equalsIgnoreCase(state)) {
            setThinkingState(true);
            String toolStatus = text == null || text.isEmpty() ? "正在執行工具" : text;
            updateDialogStatus(toolStatus);
        } else if ("ERROR".equalsIgnoreCase(state)) {
            setThinkingState(false);
            updateDialogStatus("執行失敗");
        } else if ("DONE".equalsIgnoreCase(state) || "COMPLETED".equalsIgnoreCase(state)) {
            setThinkingState(false);
            vibrateSuccess();
            String doneStatus = text == null || text.isEmpty() ? "已完成" : "已完成 · " + text;
            updateDialogStatus(doneStatus);
        } else if ("IDLE".equalsIgnoreCase(state)) {
            setThinkingState(false);
            updateDialogStatus("待命");
        }
    }

    private void updateDialogStatus(final String status) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (dialogStatusText != null) dialogStatusText.setText(status);
                if (dialogStopButton != null) dialogStopButton.setVisibility(
                    ("THINKING".equals(currentState) || "TOOL".equals(currentState)) ? View.VISIBLE : View.GONE);
            }
        });
    }

    private String friendlyState(String state) {
        if ("THINKING".equals(state)) return "AI 回覆中";
        if ("TOOL".equals(state)) return "正在執行工具";
        if ("DONE".equals(state) || "COMPLETED".equals(state)) return "已完成";
        if ("ERROR".equals(state)) return "執行失敗";
        return "待命";
    }

    public void toggleDialog() {
        if (isDialogShowing) {
            hideDialog();
        } else {
            showDialog();
        }
    }

    private void toggleNativeLive() {
        try {
            if (nativeLiveRequested || NativeLiveService.isActive()) {
                nativeLiveRequested = false;
                NativeLiveService.stop(context);
                updateNativeLiveStatus("正在結束語音通話", false);
            } else {
                nativeLiveRequested = true;
                NativeLiveService.start(context);
                // Give immediate visual feedback; the service will replace it
                // with its real connection status moments later.
                updateNativeLiveStatus("正在連線 Gemini Live", true);
                // A new call must explain itself: reveal the controls once so
                // users do not have to infer that a lone bubble is listening.
                mainHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (nativeLiveRequested || NativeLiveService.isActive()) showVoiceControls();
                    }
                }, 280);
            }
        } catch (Exception error) {
        }
    }

    /** Called by the foreground voice service; intentionally does not open a panel. */
    public void updateNativeLiveStatus(final String text, final boolean active) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                nativeLiveRequested = active;
                latestLiveStatus = text == null || text.trim().isEmpty() ? (active ? "語音通話中" : "待命") : text.trim();
                if (bubbleView != null) {
                    bubbleView.setNativeVoiceState(isLiveError(latestLiveStatus) ? 3 : (active ? 1 : 0));
                }
                refreshVoiceControls();
            }
        });
    }

    /** Lightweight telemetry from the foreground voice service for the expanded dock. */
    public void updateLiveMicrophoneLevel(final double dbfs, final boolean sending) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                latestMicDbfs = dbfs;
                latestMicSending = sending;
                updateVoiceTelemetryUi();
            }
        });
    }

    /**
     * Gemini sends a spoken sentence in several streaming fragments.  The dock
     * must merge them first; rendering every fragment makes Chinese appear as
     * a succession of one- or two-character lines.
     */
    public void updateLiveTranscript(final String role, final String text) {
        if (text == null || text.trim().isEmpty()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                String speaker = "Gemini".equalsIgnoreCase(role) ? "助理" : "你";
                String fragment = text.trim().replaceAll("\\s+", " ");
                if (!speaker.equals(latestLiveTranscriptRole)) {
                    latestLiveTranscriptRole = speaker;
                    latestLiveTranscript = speaker + "：" + fragment;
                } else {
                    String prefix = speaker + "：";
                    String existing = latestLiveTranscript.startsWith(prefix)
                            ? latestLiveTranscript.substring(prefix.length()) : latestLiveTranscript;
                    // Input/output transcription can be cumulative, whereas
                    // modelTurn text is token-like.  Cover both without
                    // duplicating the same words.
                    if (fragment.startsWith(existing)) {
                        latestLiveTranscript = prefix + fragment;
                    } else if (!existing.endsWith(fragment)) {
                        latestLiveTranscript = prefix + existing + fragment;
                    }
                }
                if (latestLiveTranscript.length() > 180) {
                    latestLiveTranscript = latestLiveTranscript.substring(0, 177) + "…";
                }
                if (transcriptRefreshRunnable != null) mainHandler.removeCallbacks(transcriptRefreshRunnable);
                transcriptRefreshRunnable = new Runnable() {
                    @Override public void run() {
                        transcriptRefreshRunnable = null;
                        updateVoiceTranscriptUi();
                    }
                };
                mainHandler.postDelayed(transcriptRefreshRunnable, 220);
            }
        });
    }

    private boolean isLiveError(String status) {
        String lower = status == null ? "" : status.toLowerCase();
        return lower.contains("失敗") || lower.contains("錯誤") || lower.contains("未取得")
                || lower.contains("尚未設定") || lower.contains("無法");
    }

    private void updateVoiceTelemetryUi() {
        if (voiceStatusText != null) {
            boolean error = isLiveError(latestLiveStatus);
            voiceStatusText.setText((error ? "● " : "● ") + latestLiveStatus);
            voiceStatusText.setTextColor(Color.parseColor(error ? "#FDA4AF" : "#93C5FD"));
        }
        if (voiceMeterText != null) {
            long db = Math.round(Math.max(-96d, Math.min(0d, latestMicDbfs)));
            String state = !NativeLiveService.isActive() ? "等待通話" : (latestMicSending ? "正在送出" : "靜音中");
            voiceMeterText.setText("🎙 收音 " + db + " dB · " + state);
        }
        if (voiceStopAgentButton != null) {
            boolean activeTask = NativeLiveService.hasActiveAgentTask();
            voiceStopAgentButton.setVisibility(activeTask ? View.VISIBLE : View.GONE);
        }
    }

    private void updateVoiceTranscriptUi() {
        if (voiceTranscriptText == null) return;
        voiceTranscriptText.setText(latestLiveTranscript);
    }

    private void toggleVoiceControls() {
        if (voiceControlView != null || voiceControlsOpening) hideVoiceControls(); else showVoiceControls();
    }

    private DockIconButton makeDockIconButton() {
        DockIconButton button = new DockIconButton(context);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void showVoiceControls() {
        if (!canDrawOverlays() || voiceControlView != null || voiceControlsOpening) return;
        voiceControlsOpening = true;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!voiceControlsOpening) return;
                try {
                    if (dialogView != null) hideDialog();
                    int overlayType = Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE;
                    int screenWidth = windowManager.getDefaultDisplay().getWidth();

                    // 📱 Ergonomic Bottom Dock (matching Web UI style)
                    int dockWidth = Math.min(dp(360), screenWidth - dp(24));
                    voiceControlParams = new WindowManager.LayoutParams(
                            dockWidth,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            overlayType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT
                    );
                    voiceControlParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    voiceControlParams.y = dp(42); // Elevated above navigation bar / gesture bar

                    LinearLayout dock = new LinearLayout(context);
                    dock.setOrientation(LinearLayout.VERTICAL);
                    dock.setPadding(dp(16), dp(14), dp(16), dp(20));
                    dock.setClipToPadding(false);
                    dock.setClipChildren(false);

                    GradientDrawable dockBg = new GradientDrawable();
                    dockBg.setColor(Color.parseColor("#F50F172A")); // Luxury Slate 900
                    dockBg.setCornerRadius(dp(24));
                    dockBg.setStroke(dp(1.5f), Color.parseColor("#33818CF8")); // Indigo 400 @ 20%
                    dock.setBackground(dockBg);
                    dock.setElevation(dp(16));

                    // Title / Status header
                    LinearLayout headerRow = new LinearLayout(context);
                    headerRow.setOrientation(LinearLayout.HORIZONTAL);
                    headerRow.setGravity(Gravity.CENTER_VERTICAL);
                    headerRow.setPadding(dp(4), 0, dp(4), dp(8));

                    TextView title = new TextView(context);
                    title.setText("Live 控制台");
                    title.setTextSize(13);
                    title.setTextColor(Color.parseColor("#38BDF8"));
                    headerRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    voiceInterruptionButton = new TextView(context);
                    voiceInterruptionButton.setTextSize(11);
                    voiceInterruptionButton.setPadding(dp(8), dp(3), dp(8), dp(3));
                    voiceInterruptionButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) return;
                            boolean enabled = NativeLiveService.toggleVoiceInterruption();
                            Toast.makeText(context, enabled ? "已開啟自由說話打斷" : "已開啟防插話模式（避免喇叭打斷 AI）", Toast.LENGTH_SHORT).show();
                            refreshVoiceControls();
                        }
                    });
                    LinearLayout.LayoutParams interLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    interLp.setMargins(0, 0, dp(6), 0);
                    headerRow.addView(voiceInterruptionButton, interLp);

                    voiceWakeButton = new TextView(context);
                    voiceWakeButton.setTextSize(11);
                    voiceWakeButton.setPadding(dp(8), dp(3), dp(8), dp(3));
                    updateWakeButtonUi(voiceWakeButton, isKeepAwakeActive());
                    voiceWakeButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            vibrateSuccess();
                            boolean next = toggleKeepAwake(context);
                            updateWakeButtonUi(voiceWakeButton, next);
                            Toast.makeText(context, next ? "☀️ 螢幕常亮已開啟（防止休眠）" : "🌙 螢幕常亮已關閉", Toast.LENGTH_SHORT).show();
                        }
                    });
                    headerRow.addView(voiceWakeButton);

                    TextView close = new TextView(context);
                    close.setText("✕");
                    close.setTextSize(18);
                    close.setTextColor(Color.parseColor("#94A3B8"));
                    close.setPadding(dp(12), 0, dp(4), 0);
                    close.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { hideVoiceControls(); }
                    });
                    headerRow.addView(close);
                    dock.addView(headerRow);

                    LinearLayout statusRow = new LinearLayout(context);
                    statusRow.setOrientation(LinearLayout.HORIZONTAL);
                    statusRow.setGravity(Gravity.CENTER_VERTICAL);
                    voiceStatusText = new TextView(context);
                    voiceStatusText.setTextSize(12);
                    voiceStatusText.setSingleLine(true);
                    voiceStatusText.setPadding(dp(4), 0, dp(4), dp(4));
                    statusRow.addView(voiceStatusText, new LinearLayout.LayoutParams(
                            0, dp(40), 1f));

                    // A compact icon sits directly below the close action and
                    // keeps infrequent settings out of the call-control flow.
                    voiceSettingsToggleButton = makeVoiceSettingButton();
                    voiceSettingsToggleButton.setText("⚙");
                    voiceSettingsToggleButton.setTextSize(20);
                    voiceSettingsToggleButton.setContentDescription("開啟語音設定");
                    voiceSettingsToggleButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (voiceSettingsPanel == null) return;
                            voiceSettingsPanel.setVisibility(voiceSettingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                        }
                    });
                    statusRow.addView(voiceSettingsToggleButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
                    dock.addView(statusRow);

                    voiceMeterText = new TextView(context);
                    voiceMeterText.setTextSize(11);
                    voiceMeterText.setTextColor(Color.parseColor("#CBD5E1"));
                    voiceMeterText.setPadding(dp(4), dp(4), dp(4), dp(8));
                    dock.addView(voiceMeterText, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                    voiceSettingsPanel = new LinearLayout(context);
                    voiceSettingsPanel.setOrientation(LinearLayout.VERTICAL);
                    voiceSettingsPanel.setVisibility(View.GONE);
                    LinearLayout settingsRow = new LinearLayout(context);
                    settingsRow.setOrientation(LinearLayout.HORIZONTAL);
                    settingsRow.setGravity(Gravity.CENTER_VERTICAL);
                    settingsRow.setPadding(0, 0, 0, dp(6));
                    voiceSensitivityButton = makeVoiceSettingButton();
                    voicePresetButton = makeVoiceSettingButton();
                    voiceOutputButton = makeVoiceSettingButton();
                    voiceSensitivityButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showVoiceSettingChoices("sensitivity"); }
                    });
                    voicePresetButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showVoiceSettingChoices("preset"); }
                    });
                    voiceOutputButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showVoiceSettingChoices("output"); }
                    });
                    LinearLayout.LayoutParams settingLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                    settingsRow.addView(voiceSensitivityButton, settingLp);
                    LinearLayout.LayoutParams presetLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                    presetLp.setMargins(dp(6), 0, dp(6), 0);
                    settingsRow.addView(voicePresetButton, presetLp);
                    settingsRow.addView(voiceOutputButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
                    voiceSettingsPanel.addView(settingsRow);
                    voiceSettingsChoices = new LinearLayout(context);
                    voiceSettingsChoices.setOrientation(LinearLayout.HORIZONTAL);
                    voiceSettingsChoices.setGravity(Gravity.CENTER_VERTICAL);
                    voiceSettingsChoices.setPadding(0, 0, 0, dp(8));
                    voiceSettingsPanel.addView(voiceSettingsChoices);
                    dock.addView(voiceSettingsPanel);

                    voiceStopAgentButton = makeVoiceSettingButton();
                    voiceStopAgentButton.setText("■ 停止任務");
                    voiceStopAgentButton.setTextColor(Color.parseColor("#FDA4AF"));
                    voiceStopAgentButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (NativeLiveService.stopAgentTask()) Toast.makeText(context, "Agent 任務已停止", Toast.LENGTH_SHORT).show();
                            else Toast.makeText(context, "目前沒有執行中的 Agent 任務", Toast.LENGTH_SHORT).show();
                            refreshVoiceControls();
                        }
                    });
                    LinearLayout.LayoutParams stopTaskLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                    stopTaskLp.setMargins(0, 0, 0, dp(5));
                    dock.addView(voiceStopAgentButton, stopTaskLp);

                    // 📱 Ergonomic Bottom Dock matching Web UI:
                    // Layout: [Camera Icon] [Screen Icon] [Center Large Mute/Interrupt Icon] [Hangup/Call Icon]
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setClipToPadding(false);
                    row.setClipChildren(false);
                    row.setPadding(0, dp(4), 0, dp(6));

                    voiceCameraButton = makeDockIconButton();
                    voiceScreenButton = makeDockIconButton();
                    voiceMuteButton = makeDockIconButton();
                    voiceCallButton = makeDockIconButton();
                    voiceCameraButton.setContentDescription("切換相機分享");
                    voiceScreenButton.setContentDescription("切換螢幕分享");
                    voiceMuteButton.setContentDescription("靜音或打斷助理");
                    voiceCallButton.setContentDescription("開始或結束 Live 通話");

                    voiceCameraButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) {
                                Toast.makeText(context, "請先開始通話並等待連線", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            NativeLiveService.toggleCameraSharing();
                            refreshVoiceControls();
                        }
                    });

                    voiceScreenButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) {
                                Toast.makeText(context, "請先開始通話並等待連線", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            NativeLiveService.toggleScreenSharing();
                            refreshVoiceControls();
                        }
                    });

                    voiceMuteButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) return;
                            NativeLiveService.toggleAgentMute();
                            refreshVoiceControls();
                        }
                    });

                    voiceCallButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            toggleNativeLive();
                            refreshVoiceControls();
                        }
                    });

                    LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(54), dp(44));
                    sideLp.setMargins(dp(3), 0, dp(3), 0);

                    LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                    centerLp.setMargins(dp(4), 0, dp(4), 0);

                    // 1. Camera (Left)
                    row.addView(voiceCameraButton, sideLp);
                    // 2. Screen (Left-Center)
                    row.addView(voiceScreenButton, sideLp);
                    // 3. Main Center Mute/Interrupt (Large Hero Pill)
                    row.addView(voiceMuteButton, centerLp);
                    // 4. Hangup (Right)
                    row.addView(voiceCallButton, sideLp);

                    dock.addView(row);

                    voiceTranscriptText = new TextView(context);
                    voiceTranscriptText.setTextSize(11);
                    voiceTranscriptText.setTextColor(Color.parseColor("#94A3B8"));
                    voiceTranscriptText.setMaxLines(2);
                    voiceTranscriptText.setPadding(dp(4), dp(7), dp(4), 0);
                    dock.addView(voiceTranscriptText, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    voiceControlView = dock;
                    windowManager.addView(dock, voiceControlParams);
                    refreshVoiceControls();
                    updateVoiceTelemetryUi();
                    updateVoiceTranscriptUi();
                } catch (Exception error) {
                    voiceControlView = null;
                } finally {
                    voiceControlsOpening = false;
                }
            }
        });
    }

    private void hideVoiceControls() {
        voiceControlsOpening = false;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (voiceControlView != null) windowManager.removeViewImmediate(voiceControlView);
                } catch (Exception ignored) {}
                voiceControlView = null;
                voiceCallButton = null;
                voiceCameraButton = null;
                voiceScreenButton = null;
                voiceMuteButton = null;
                voiceWakeButton = null;
                voiceSensitivityButton = null;
                voicePresetButton = null;
                voiceOutputButton = null;
                voiceSettingsToggleButton = null;
                voiceSettingsPanel = null;
                voiceStopAgentButton = null;
                voiceSettingsChoices = null;
                voiceStatusText = null;
                voiceMeterText = null;
                voiceTranscriptText = null;
            }
        });
    }

    public void refreshVoiceControls() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                boolean isLiveActive = nativeLiveRequested || NativeLiveService.isActive();
                boolean isAiSpeaking = NativeLiveService.isAiSpeaking();
                
                if (bubbleView != null) {
                    if (isLiveError(latestLiveStatus)) {
                        wakeBubbleFromDock();
                        bubbleView.setNativeVoiceState(3);
                    } else if (isAiSpeaking) {
                        wakeBubbleFromDock();
                        bubbleView.setNativeVoiceState(2); // Amber = AI speaking
                    } else if (isLiveActive) {
                        wakeBubbleFromDock();
                        bubbleView.setNativeVoiceState(1); // Red = Live call active
                    } else {
                        bubbleView.setNativeVoiceState(0); // Idle
                        scheduleAutoDock();
                    }
                }

                if (voiceCameraButton != null) {
                    boolean isCamActive = NativeLiveService.isCameraSharing();
                    GradientDrawable camBg = new GradientDrawable();
                    camBg.setCornerRadius(dp(16));
                    if (isCamActive) {
                        camBg.setColor(Color.parseColor("#4F46E5")); // Indigo 600
                        camBg.setStroke(dp(1), Color.parseColor("#818CF8")); // Indigo 400
                        voiceCameraButton.setIcon(DockIconButton.ICON_CAMERA, Color.WHITE);
                    } else {
                        camBg.setColor(Color.parseColor("#E61E293B")); // Slate 800/90
                        camBg.setStroke(dp(1), Color.parseColor("#334155")); // Slate 700
                        voiceCameraButton.setIcon(DockIconButton.ICON_CAMERA, Color.parseColor("#94A3B8"));
                    }
                    voiceCameraButton.setBackground(camBg);
                }
                if (voiceScreenButton != null) {
                    boolean isScreenActive = NativeLiveService.isScreenSharing();
                    GradientDrawable screenBg = new GradientDrawable();
                    screenBg.setCornerRadius(dp(16));
                    if (isScreenActive) {
                        screenBg.setColor(Color.parseColor("#0891B2")); // Cyan 600
                        screenBg.setStroke(dp(1), Color.parseColor("#67E8F9")); // Cyan 300
                        voiceScreenButton.setIcon(DockIconButton.ICON_SCREEN, Color.WHITE);
                    } else {
                        screenBg.setColor(Color.parseColor("#E61E293B")); // Slate 800/90
                        screenBg.setStroke(dp(1), Color.parseColor("#334155")); // Slate 700
                        voiceScreenButton.setIcon(DockIconButton.ICON_SCREEN, Color.parseColor("#94A3B8"));
                    }
                    voiceScreenButton.setBackground(screenBg);
                }
                if (voiceCallButton != null) {
                    GradientDrawable callBg = new GradientDrawable();
                    callBg.setCornerRadius(dp(16));
                    if (isLiveActive) {
                        // 🛑 In Call -> Rose Red Hangup Button
                        callBg.setColor(Color.parseColor("#E11D48")); // Rose 600
                        callBg.setStroke(dp(1), Color.parseColor("#FDA4AF"));
                        voiceCallButton.setIcon(DockIconButton.ICON_CALL_HANGUP, Color.WHITE);
                    } else {
                        // 🎙️ Idle -> Slate 800 Start Call Button
                        callBg.setColor(Color.parseColor("#E61E293B")); // Slate 800
                        callBg.setStroke(dp(1), Color.parseColor("#4F46E5")); // Indigo border
                        voiceCallButton.setIcon(DockIconButton.ICON_CALL_START, Color.parseColor("#A5B4FC"));
                    }
                    voiceCallButton.setBackground(callBg);
                }
                if (voiceMuteButton != null) {
                    boolean isMuted = NativeLiveService.isAgentMuted();
                    GradientDrawable muteBg = new GradientDrawable();
                    muteBg.setCornerRadius(dp(16));

                    if (!isLiveActive) {
                        // 📴 State 0: Call Inactive / Idle -> Slate 800 Standby (Mute button disabled/idle)
                        muteBg.setColor(Color.parseColor("#E61E293B")); // Slate 800
                        muteBg.setStroke(dp(1), Color.parseColor("#334155")); // Slate 700
                        voiceMuteButton.setIcon(DockIconButton.ICON_MIC_ACTIVE, Color.parseColor("#64748B")); // Dim Slate
                    } else if (isAiSpeaking) {
                        // 🔊 State 1: AI is speaking -> Amber 600 Hero (Tap to interrupt)
                        muteBg.setColor(Color.parseColor("#D97706")); // Amber 600
                        muteBg.setStroke(dp(2), Color.parseColor("#FDE68A")); // Amber 300
                        voiceMuteButton.setIcon(DockIconButton.ICON_SPEAKER, Color.WHITE);
                    } else if (isMuted) {
                        // 🔇 State 2: Muted -> Rose 900 (Tap to unmute)
                        muteBg.setColor(Color.parseColor("#881337")); // Rose 900
                        muteBg.setStroke(dp(2), Color.parseColor("#F43F5E")); // Rose 500
                        voiceMuteButton.setIcon(DockIconButton.ICON_MIC_MUTED, Color.parseColor("#FECDD3"));
                    } else {
                        // 🎙️ State 3: Listening / Active -> Teal 600 (Tap to mute)
                        muteBg.setColor(Color.parseColor("#0D9488")); // Teal 600
                        muteBg.setStroke(dp(2), Color.parseColor("#2DD4BF")); // Teal 400
                        voiceMuteButton.setIcon(DockIconButton.ICON_MIC_ACTIVE, Color.WHITE);
                    }
                    voiceMuteButton.setBackground(muteBg);
                }

                if (voiceInterruptionButton != null) {
                    boolean allowInterruption = NativeLiveService.isVoiceInterruptionAllowed();
                    GradientDrawable pillBg = new GradientDrawable();
                    pillBg.setCornerRadius(dp(12));
                    if (allowInterruption) {
                        pillBg.setColor(Color.parseColor("#064E3B")); // Emerald 900
                        pillBg.setStroke(dp(1), Color.parseColor("#10B981")); // Emerald 500
                        voiceInterruptionButton.setText("🎙️ 允許插話");
                        voiceInterruptionButton.setTextColor(Color.parseColor("#6EE7B7")); // Emerald 300
                    } else {
                        pillBg.setColor(Color.parseColor("#78350F")); // Amber 900
                        pillBg.setStroke(dp(1), Color.parseColor("#F59E0B")); // Amber 500
                        voiceInterruptionButton.setText("🛡️ 防插話");
                        voiceInterruptionButton.setTextColor(Color.parseColor("#FCD34D")); // Amber 300
                    }
                    voiceInterruptionButton.setBackground(pillBg);
                }

                if (voiceWakeButton != null) {
                    updateWakeButtonUi(voiceWakeButton, isKeepAwakeActive());
                }
                updateVoiceQuickSettingsUi();
                updateVoiceTelemetryUi();
            }
        });
    }

    private TextView makeVoiceSettingButton() {
        TextView button = new TextView(context);
        button.setTextSize(10);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void updateVoiceQuickSettingsUi() {
        int sensitivity = NativeLiveService.getInterruptionSensitivity(context);
        if (voiceSensitivityButton != null) {
            voiceSensitivityButton.setText("🎙 插話 ›");
            applyVoiceSettingStyle(voiceSensitivityButton, Color.parseColor("#064E3B"), Color.parseColor("#10B981"), Color.parseColor("#6EE7B7"));
        }
        if (voicePresetButton != null) {
            voicePresetButton.setText("🎭 角色 ›");
            applyVoiceSettingStyle(voicePresetButton, Color.parseColor("#312E81"), Color.parseColor("#818CF8"), Color.parseColor("#C7D2FE"));
        }
        if (voiceOutputButton != null) {
            boolean media = "media".equals(AppConfig.getAudioOutput(context));
            voiceOutputButton.setText("🔊 輸出 ›");
            applyVoiceSettingStyle(voiceOutputButton, media ? Color.parseColor("#164E63") : Color.parseColor("#3F1D5B"), media ? Color.parseColor("#22D3EE") : Color.parseColor("#C084FC"), Color.WHITE);
        }
        if (voiceSettingsToggleButton != null) {
            applyVoiceSettingStyle(voiceSettingsToggleButton, Color.parseColor("#1E293B"), Color.parseColor("#475569"), Color.parseColor("#CBD5E1"));
            voiceSettingsToggleButton.setText("⚙");
        }
    }

    private void applyVoiceSettingStyle(TextView button, int fill, int stroke, int text) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(fill);
        bg.setStroke(dp(1), stroke);
        button.setBackground(bg);
        button.setTextColor(text);
    }

    private String interruptionLabel(int value) {
        return value < 35 ? "插話低" : (value > 70 ? "插話高" : "插話中");
    }

    private String voicePresetLabel(String preset) {
        if ("professional".equals(preset)) return "專業";
        if ("teacher".equals(preset)) return "導師";
        if ("calm".equals(preset)) return "沉穩";
        if ("command".equals(preset)) return "指揮";
        if ("warm".equals(preset)) return "溫暖";
        return "自訂";
    }

    /** All choices stay inside the overlay, avoiding Activity-token dialogs. */
    private void showVoiceSettingChoices(String menu) {
        if (voiceSettingsChoices == null) return;
        voiceSettingsChoices.removeAllViews();
        if ("sensitivity".equals(menu)) {
            addVoiceChoice("低", new Runnable() { @Override public void run() { NativeLiveService.setInterruptionSensitivity(context, 25); Toast.makeText(context, "插話靈敏度：低", Toast.LENGTH_SHORT).show(); } });
            addVoiceChoice("中", new Runnable() { @Override public void run() { NativeLiveService.setInterruptionSensitivity(context, 55); Toast.makeText(context, "插話靈敏度：中", Toast.LENGTH_SHORT).show(); } });
            addVoiceChoice("高", new Runnable() { @Override public void run() { NativeLiveService.setInterruptionSensitivity(context, 85); Toast.makeText(context, "插話靈敏度：高", Toast.LENGTH_SHORT).show(); } });
        } else if ("output".equals(menu)) {
            addVoiceChoice("📞 通話", new Runnable() { @Override public void run() { AppConfig.setAudioOutput(context, "call"); Toast.makeText(context, "下次通話使用通話音訊", Toast.LENGTH_SHORT).show(); } });
            addVoiceChoice("🔊 媒體", new Runnable() { @Override public void run() { AppConfig.setAudioOutput(context, "media"); Toast.makeText(context, "下次通話使用媒體音訊", Toast.LENGTH_SHORT).show(); } });
        } else {
            final String[] ids = {"warm", "professional", "teacher", "calm", "command"};
            final String[] voices = {"Kore", "Charon", "Aoede", "Fenrir", "Puck"};
            final String[] tones = {"warm", "professional", "lively", "calm", "urgent"};
            for (int i = 0; i < ids.length; i++) {
                final int index = i;
                addVoiceChoice(voicePresetLabel(ids[i]), new Runnable() { @Override public void run() {
                    AppConfig.applyVoicePreset(context, ids[index], voices[index], tones[index]);
                    Toast.makeText(context, "角色將於下次通話套用", Toast.LENGTH_SHORT).show();
                }});
            }
        }
    }

    private void addVoiceChoice(String label, final Runnable action) {
        TextView choice = makeVoiceSettingButton();
        choice.setText(label);
        applyVoiceSettingStyle(choice, Color.parseColor("#0F2744"), Color.parseColor("#38BDF8"), Color.parseColor("#E0F2FE"));
        choice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
                if (voiceSettingsChoices != null) voiceSettingsChoices.removeAllViews();
                refreshVoiceControls();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        if (voiceSettingsChoices.getChildCount() > 0) lp.setMargins(dp(5), 0, 0, 0);
        voiceSettingsChoices.addView(choice, lp);
    }

    public void showDialog() {
        if (!canDrawOverlays()) return;
        if (dialogView != null) return;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int overlayType = Build.VERSION.SDK_INT >= 26 
                        ? 2038 
                        : WindowManager.LayoutParams.TYPE_PHONE;

                    int screenWidth = windowManager.getDefaultDisplay().getWidth();
                    dialogParams = new WindowManager.LayoutParams(
                        Math.max(dp(280), screenWidth - dp(30)),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT
                    );
                    dialogParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    dialogParams.y = dp(72);

                    LinearLayout card = new LinearLayout(context);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(16), dp(12), dp(16), dp(16));
                    final boolean connectedMode = !AppConfig.isStandaloneMode(context);

                    GradientDrawable cardBg = new GradientDrawable();
                    cardBg.setColor(Color.parseColor("#F50F172A")); // Luxury Slate 900
                    cardBg.setCornerRadius(dp(20));
                    cardBg.setStroke(dp(1.5f), Color.parseColor("#33818CF8")); // Indigo 400 @ 20%
                    card.setBackground(cardBg);
                    card.setElevation(dp(20));

                    // Header Row
                    LinearLayout header = new LinearLayout(context);
                    header.setOrientation(LinearLayout.HORIZONTAL);
                    header.setGravity(Gravity.CENTER_VERTICAL);
                    header.setPadding(dp(2), dp(2), dp(2), dp(6));

                    TextView title = new TextView(context);
                    title.setText(connectedMode ? "🤖 Crew Pocket" : "🎙️ Crew Helper");
                    title.setTextSize(14);
                    title.setTextColor(Color.WHITE);
                    title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    header.addView(title);

                    TextView badge = new TextView(context);
                    badge.setText(connectedMode ? "隨身指令" : "獨立模式");
                    badge.setTextSize(9);
                    badge.setTextColor(Color.parseColor("#5EEAD4")); // Teal 300
                    badge.setTypeface(android.graphics.Typeface.MONOSPACE);
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(Color.parseColor("#2614B8A6"));
                    badgeBg.setCornerRadius(dp(6));
                    badgeBg.setStroke(dp(1), Color.parseColor("#4D14B8A6"));
                    badge.setBackground(badgeBg);
                    badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    badgeLp.setMargins(dp(8), 0, 0, 0);
                    header.addView(badge, badgeLp);

                    View spacer = new View(context);
                    header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

                    final TextView wakePill = new TextView(context);
                    wakePill.setTextSize(10);
                    wakePill.setPadding(dp(8), dp(3), dp(8), dp(3));
                    updateWakeButtonUi(wakePill, isKeepAwakeActive());
                    wakePill.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            vibrateSuccess();
                            boolean next = toggleKeepAwake(context);
                            updateWakeButtonUi(wakePill, next);
                            Toast.makeText(context, next ? "☀️ 螢幕常亮已開啟（防止休眠）" : "🌙 螢幕常亮已關閉", Toast.LENGTH_SHORT).show();
                        }
                    });
                    LinearLayout.LayoutParams wakeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    wakeLp.setMargins(0, 0, dp(8), 0);
                    header.addView(wakePill, wakeLp);

                    TextView closeBtn = new TextView(context);
                    closeBtn.setText("✕");
                    closeBtn.setTextSize(14);
                    closeBtn.setTextColor(Color.parseColor("#94A3B8"));
                    closeBtn.setGravity(Gravity.CENTER);
                    GradientDrawable closeBg = new GradientDrawable();
                    closeBg.setColor(Color.parseColor("#1E293B"));
                    closeBg.setCornerRadius(dp(12));
                    closeBtn.setBackground(closeBg);
                    closeBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideDialog();
                        }
                    });
                    LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(26), dp(26));
                    header.addView(closeBtn, closeLp);

                    header.setOnTouchListener(new View.OnTouchListener() {
                        private int startX, startY;
                        private float touchX, touchY;
                        @Override public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    startX = dialogParams.x;
                                    startY = dialogParams.y;
                                    touchX = event.getRawX();
                                    touchY = event.getRawY();
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    dialogParams.x = startX + (int) (event.getRawX() - touchX);
                                    dialogParams.y = startY + (int) (event.getRawY() - touchY);
                                    try { windowManager.updateViewLayout(dialogView, dialogParams); } catch (Exception ignored) {}
                                    return true;
                                default:
                                    return true;
                            }
                        }
                    });
                    card.addView(header);

                    dialogStatusText = new TextView(context);
                    dialogStatusText.setText(friendlyState(currentState));
                    dialogStatusText.setTextSize(10);
                    dialogStatusText.setTextColor(Color.parseColor("#94A3B8"));
                    dialogStatusText.setTypeface(android.graphics.Typeface.MONOSPACE);
                    dialogStatusText.setPadding(dp(4), dp(2), dp(4), 0);
                    card.addView(dialogStatusText);

                    final EditText input = new EditText(context);
                    input.setHint("輸入你想給 Crew Pocket AI 的指令...");
                    input.setHintTextColor(Color.parseColor("#64748B"));
                    input.setTextColor(Color.WHITE);
                    input.setTextSize(13);
                    input.setMinLines(2);
                    input.setMaxLines(3);
                    input.setGravity(Gravity.TOP | Gravity.START);
                    input.setPadding(dp(12), dp(10), dp(12), dp(10));

                    GradientDrawable inputBg = new GradientDrawable();
                    inputBg.setColor(Color.parseColor("#020617")); // Slate 950
                    inputBg.setCornerRadius(dp(14));
                    inputBg.setStroke(dp(1), Color.parseColor("#334155")); // Slate 700
                    input.setBackground(inputBg);
                    input.setVisibility(connectedMode ? View.VISIBLE : View.GONE);

                    LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    inputLp.setMargins(0, dp(8), 0, dp(10));
                    card.addView(input, inputLp);

                    LinearLayout actions = new LinearLayout(context);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    actions.setGravity(Gravity.CENTER_VERTICAL);

                    Button btnSnap = new Button(context);
                    btnSnap.setText("📸 截圖");
                    btnSnap.setTextColor(Color.parseColor("#38BDF8"));
                    btnSnap.setTextSize(11);
                    btnSnap.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    btnSnap.setAllCaps(false);
                    btnSnap.setMinHeight(dp(38));
                    btnSnap.setPadding(dp(6), 0, dp(6), 0);
                    GradientDrawable snapBg = new GradientDrawable();
                    snapBg.setColor(Color.parseColor("#1E293B"));
                    snapBg.setCornerRadius(dp(10));
                    snapBg.setStroke(dp(1), Color.parseColor("#334155"));
                    btnSnap.setBackground(snapBg);
                    btnSnap.setVisibility(connectedMode ? View.VISIBLE : View.GONE);
                    btnSnap.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            vibrateShort();
                            btnSnap.setEnabled(false);
                            updateDialogStatus("正在擷取螢幕…");
                            captureScreenshotForPrompt(new CaptureCallback() {
                                @Override public void onResult(boolean success, String detail) {
                                    btnSnap.setEnabled(true);
                                    if (success) {
                                        if (pendingImageData == null || pendingImageData.isEmpty()) {
                                            updateDialogStatus("截圖資料無法使用，請重試");
                                            return;
                                        }
                                        input.setHint("已截圖，輸入你想問的問題…");
                                        updateDialogStatus("截圖已準備，請輸入問題");
                                    } else {
                                        updateDialogStatus("截圖失敗，請重試");
                                    }
                                }
                            });
                        }
                    });
                    LinearLayout.LayoutParams snapLp = new LinearLayout.LayoutParams(
                        0, dp(38), 1f
                    );
                    snapLp.setMargins(0, 0, dp(6), 0);
                    actions.addView(btnSnap, snapLp);

                    Button btnSend = new Button(context);
                    btnSend.setText("💬 傳送執行");
                    btnSend.setTextColor(Color.WHITE);
                    btnSend.setTextSize(12);
                    btnSend.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    btnSend.setAllCaps(false);
                    btnSend.setMinHeight(dp(38));
                    btnSend.setPadding(dp(6), 0, dp(6), 0);
                    GradientDrawable sendBg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{ Color.parseColor("#14B8A6"), Color.parseColor("#4F46E5") }
                    );
                    sendBg.setCornerRadius(dp(10));
                    btnSend.setBackground(sendBg);
                    btnSend.setVisibility(connectedMode ? View.VISIBLE : View.GONE);
                    btnSend.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String msg = input.getText().toString().trim();
                            if (!msg.isEmpty()) {
                                vibrateShort();
                                btnSend.setEnabled(false);
                                updateDialogStatus("正在連線…");
                                final String imageData = pendingImageData;
                                sendMessageToCrewPocket(msg, imageData, new SendCallback() {
                                    @Override public void onResult(boolean success, String detail) {
                                        btnSend.setEnabled(true);
                                        if (success) {
                                            pendingImageData = null;
                                            hideDialog();
                                        }
                                        else updateDialogStatus("傳送失敗，請重試");
                                    }
                                });
                            } else {
                                Toast.makeText(context, "請輸入指令文字", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                    LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, dp(38), 1.4f);
                    sendLp.setMargins(0, 0, dp(6), 0);
                    actions.addView(btnSend, sendLp);

                    final Button btnAwake = new Button(context);
                    final boolean isAwake = CrewAccessibilityService.isKeepAwakeActive();
                    btnAwake.setText(isAwake ? "☀️ 常亮中" : "☀️ 常亮");
                    btnAwake.setTextColor(isAwake ? Color.parseColor("#FDE047") : Color.parseColor("#94A3B8"));
                    btnAwake.setTextSize(11);
                    btnAwake.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    btnAwake.setAllCaps(false);
                    btnAwake.setMinHeight(dp(38));
                    btnAwake.setPadding(dp(4), 0, dp(4), 0);
                    final GradientDrawable awakeBg = new GradientDrawable();
                    awakeBg.setColor(isAwake ? Color.parseColor("#422006") : Color.parseColor("#1E293B"));
                    awakeBg.setCornerRadius(dp(10));
                    awakeBg.setStroke(dp(1), isAwake ? Color.parseColor("#EAB308") : Color.parseColor("#334155"));
                    btnAwake.setBackground(awakeBg);
                    btnAwake.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            vibrateShort();
                            boolean next = CrewAccessibilityService.toggleKeepAwake();
                            btnAwake.setText(next ? "☀️ 常亮中" : "☀️ 常亮");
                            btnAwake.setTextColor(next ? Color.parseColor("#FDE047") : Color.parseColor("#94A3B8"));
                            awakeBg.setColor(next ? Color.parseColor("#422006") : Color.parseColor("#1E293B"));
                            awakeBg.setStroke(dp(1), next ? Color.parseColor("#EAB308") : Color.parseColor("#334155"));
                            Toast.makeText(context, next ? "☀️ 螢幕常亮已開啟（防止休眠）" : "🌙 螢幕常亮已關閉", Toast.LENGTH_SHORT).show();
                        }
                    });
                    LinearLayout.LayoutParams awakeLp = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
                    awakeLp.setMargins(0, 0, dp(6), 0);
                    actions.addView(btnAwake, awakeLp);

                    card.addView(actions);

                    dialogStopButton = new Button(context);
                    dialogStopButton.setText("🛑 停止生成");
                    dialogStopButton.setTextColor(Color.parseColor("#FECACA"));
                    dialogStopButton.setTextSize(11);
                    dialogStopButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    dialogStopButton.setAllCaps(false);
                    dialogStopButton.setMinHeight(dp(36));
                    dialogStopButton.setPadding(dp(4), 0, dp(4), 0);
                    GradientDrawable stopBg = new GradientDrawable();
                    stopBg.setColor(Color.parseColor("#450A0A"));
                    stopBg.setCornerRadius(dp(10));
                    stopBg.setStroke(dp(1), Color.parseColor("#991B1B"));
                    dialogStopButton.setBackground(stopBg);
                    dialogStopButton.setVisibility(connectedMode && ("THINKING".equals(currentState) || "TOOL".equals(currentState)) ? View.VISIBLE : View.GONE);
                    dialogStopButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            stopCrewPocketGeneration();
                            hideDialog();
                        }
                    });
                    LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
                    );
                    stopLp.setMargins(0, dp(8), 0, 0);

                    card.addView(dialogStopButton, stopLp);

                    dialogView = card;
                    windowManager.addView(dialogView, dialogParams);
                    isDialogShowing = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void hideDialog() {
        if (dialogView != null && isDialogShowing) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (dialogView != null) {
                            windowManager.removeView(dialogView);
                            dialogView = null;
                        }
                        isDialogShowing = false;
                        dialogStatusText = null;
                        dialogStopButton = null;
                    } catch (Exception e) {}
                }
            });
        }
    }

    public void sendMessageToCrewPocket(final String message) {
        sendMessageToCrewPocket(message, null, null);
    }

    public void sendMessageToCrewPocket(final String message, final SendCallback callback) {
        sendMessageToCrewPocket(message, null, callback);
    }

    public void sendMessageToCrewPocket(final String message, final String imageData, final SendCallback callback) {
        if (AppConfig.isStandaloneMode(context)) {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    setThinkingState(false);
                    updateDialogStatus("此功能需要 Crew Pocket 連線模式");
                    if (callback != null) callback.onResult(false, "Crew Pocket 未連線");
                }
            });
            return;
        }
        setThinkingState(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                String detail = "無法連線";
                String server = AppConfig.getServerUrl(context);
                if (server == null || server.isEmpty()) {
                    server = AppConfig.DEFAULT_SERVER;
                }
                String endpoint = server.replaceAll("/+$", "") + "/api/inbound/messages";

                try {
                    for (int attempt = 1; attempt <= 3 && !success; attempt++) {
                        HttpURLConnection conn = null;
                        try {
                            URL url = new URL(endpoint);
                            conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                            conn.setDoOutput(true);
                            conn.setConnectTimeout(4000);
                            conn.setReadTimeout(4000);

                            String payload = "{\"message\":\"" + escapeJson(message) + "\",\"source\":\"FloatingBubble\"";
                            if (imageData != null && !imageData.isEmpty()) {
                                payload += ",\"image_base64\":\"" + imageData + "\"";
                            }
                            payload += "}";
                            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                            conn.setFixedLengthStreamingMode(bytes.length);
                            OutputStream os = conn.getOutputStream();
                            os.write(bytes);
                            os.flush();
                            os.close();

                            int code = conn.getResponseCode();
                            success = code >= 200 && code < 300;
                            detail = "HTTP " + code;
                        } catch (Exception attemptError) {
                            detail = attemptError.getMessage() == null ? "連線逾時" : attemptError.getMessage();
                            if (attempt < 3) {
                                try { Thread.sleep(250L * attempt); } catch (InterruptedException ignored) {}
                            }
                        } finally {
                            if (conn != null) conn.disconnect();
                        }
                    }
                } catch (Exception e) {
                    detail = e.getMessage() == null ? "連線失敗" : e.getMessage();
                }
                final boolean result = success;
                final String resultDetail = detail;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!result) {
                            setThinkingState(false);
                            updateDialogStatus("Crew Pocket 尚未連線");
                        }
                        if (callback != null) callback.onResult(result, resultDetail);
                    }
                });
            }
        }).start();
    }

    public void captureScreenshotForPrompt(final CaptureCallback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean success = false;
                String detail = "截圖失敗";
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://127.0.0.1:8766/screenshot");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(8000);
                    int code = conn.getResponseCode();
                    InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                    ByteArrayOutputStream response = new ByteArrayOutputStream();
                    if (stream != null) {
                        byte[] chunk = new byte[4096];
                        int count;
                        while ((count = stream.read(chunk)) != -1) response.write(chunk, 0, count);
                        stream.close();
                    }
                    JSONObject result = new JSONObject(new String(response.toByteArray(), StandardCharsets.UTF_8));
                    String path = result.optString("latestPath", result.optString("path", ""));
                    if (code >= 200 && code < 300 && result.optBoolean("success") && !path.isEmpty()) {
                        pendingImageData = encodeScreenshotForUpload(path);
                        success = true;
                        detail = "本機截圖完成";
                    } else {
                        detail = result.optString("error", "截圖失敗（HTTP " + code + "）");
                    }
                } catch (Exception e) {
                    pendingImageData = null;
                    detail = e.getMessage() == null ? "截圖連線失敗" : e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final boolean result = success;
                final String resultDetail = detail;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (callback != null) callback.onResult(result, resultDetail);
                    }
                });
            }
        }).start();
    }

    private String encodeScreenshotForUpload(String path) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) throw new Exception("無法讀取截圖資料");
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > 1440) {
            float scale = 1440f / Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            bitmap.recycle();
            bitmap = scaled;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
        bitmap.recycle();
        byte[] bytes = output.toByteArray();
        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) throw new Exception("截圖壓縮後大小異常");
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public void stopCrewPocketGeneration() {
        setThinkingState(false);
        currentState = "IDLE";
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("http://127.0.0.1:8000/api/stop");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes); os.flush(); os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    // 🌊 Custom Fluid Bubble View (Exact Web UI Gradient Replica)
    public static class FluidBubbleView extends View {
        private Paint bgPaint;
        private Paint ringPaint;
        private Paint glowPaint;
        private RectF ringBounds = new RectF();
        private SweepGradient idleSweepGradient;
        private SweepGradient activeSweepGradient;
        private SweepGradient speakingSweepGradient;
        private SweepGradient errorSweepGradient;
        private SweepGradient rainbowSweepGradient;
        private Matrix matrix = new Matrix();
        private float rotationAngle = 0f;
        private boolean isFlowing = false;
        private boolean isSuccessFlash = false;
        // 0 idle, 1 connected/listening, 2 AI speaking, 3 connection error
        private int nativeVoiceState = 0;
        private ValueAnimator continuousRotator;

        public FluidBubbleView(Context context) {
            super(context);
            init();
        }

        private void init() {
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setStyle(Paint.Style.FILL);

            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(6.5f);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(12f);

            startContinuousRotation();
        }

        private void startContinuousRotation() {
            if (continuousRotator == null) {
                continuousRotator = ValueAnimator.ofFloat(0f, 360f);
                continuousRotator.setDuration(4000); // 4s full rotation (identical to Web CSS)
                continuousRotator.setRepeatCount(ValueAnimator.INFINITE);
                continuousRotator.setInterpolator(new LinearInterpolator());
                continuousRotator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        rotationAngle = (float) animation.getAnimatedValue();
                        invalidate();
                    }
                });
            }
            if (!continuousRotator.isRunning()) {
                continuousRotator.start();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startContinuousRotation();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (continuousRotator != null) {
                continuousRotator.cancel();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float stroke = ringPaint.getStrokeWidth();
            ringBounds.set(stroke / 2f + 2f, stroke / 2f + 2f, w - stroke / 2f - 2f, h - stroke / 2f - 2f);

            float cx = w / 2f;
            float cy = h / 2f;

            // 1. Idle is deliberately neutral: it should not look as if it is listening.
            int[] idleColors = new int[]{
                Color.parseColor("#64748B"),
                Color.parseColor("#94A3B8"),
                Color.parseColor("#475569"),
                Color.parseColor("#64748B")
            };
            float[] idlePositions = new float[]{0.0f, 0.32f, 0.72f, 1.0f};
            idleSweepGradient = new SweepGradient(cx, cy, idleColors, idlePositions);

            // 2. Blue says "connected and listening".
            int[] activeColors = new int[]{
                Color.parseColor("#38BDF8"),
                Color.parseColor("#2563EB"),
                Color.parseColor("#818CF8"),
                Color.parseColor("#38BDF8")
            };
            activeSweepGradient = new SweepGradient(cx, cy, activeColors, null);

            // 3. Purple is reserved for the assistant speaking.
            int[] speakColors = new int[]{
                Color.parseColor("#A855F7"),
                Color.parseColor("#C084FC"),
                Color.parseColor("#7C3AED"),
                Color.parseColor("#A855F7")
            };
            speakingSweepGradient = new SweepGradient(cx, cy, speakColors, null);

            int[] errorColors = new int[]{
                Color.parseColor("#F43F5E"), Color.parseColor("#EF4444"),
                Color.parseColor("#FB7185"), Color.parseColor("#F43F5E")
            };
            errorSweepGradient = new SweepGradient(cx, cy, errorColors, null);

            // 4. Fast Rainbow Thinking Stream
            int[] rainbowColors = new int[]{
                Color.parseColor("#38BDF8"),
                Color.parseColor("#818CF8"),
                Color.parseColor("#C084FC"),
                Color.parseColor("#F43F5E"),
                Color.parseColor("#38BDF8")
            };
            rainbowSweepGradient = new SweepGradient(cx, cy, rainbowColors, null);
        }

        public void startWaterFlow() {
            isFlowing = true;
            isSuccessFlash = false;
            if (continuousRotator != null) {
                continuousRotator.setDuration(1200); // Speed up rotation during tool execution
            }
            invalidate();
        }

        public void stopWaterFlow() {
            isFlowing = false;
            if (continuousRotator != null) {
                continuousRotator.setDuration(4000); // Return to gentle 4s rotation
            }
            isSuccessFlash = true;
            invalidate();

            postDelayed(new Runnable() {
                @Override
                public void run() {
                    isSuccessFlash = false;
                    invalidate();
                }
            }, 850);
        }

        public void setNativeVoiceState(int state) {
            nativeVoiceState = state;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = (Math.min(getWidth(), getHeight()) / 2f) - 2.5f;

            // ── 1. Deep Glassmorphism Radial Gradient Background (Slate 900 -> Slate 950) ──
            int[] coreColors = new int[]{
                Color.parseColor("#1E293B"), // Slate 800 (Highlight center)
                Color.parseColor("#0F172A"), // Slate 900
                Color.parseColor("#020617")  // Slate 950 (Deep edge)
            };
            float[] corePositions = new float[]{0.0f, 0.65f, 1.0f};
            android.graphics.RadialGradient coreGrad = new android.graphics.RadialGradient(
                cx, cy * 0.9f, radius, coreColors, corePositions, android.graphics.Shader.TileMode.CLAMP
            );
            bgPaint.setShader(coreGrad);
            canvas.drawCircle(cx, cy, radius, bgPaint);

            // ── 2. Rotating Conic/Sweep Gradient Border (Identical to Web) ──
            matrix.setRotate(rotationAngle, cx, cy);
            SweepGradient currentGradient;
            if (nativeVoiceState == 2) {
                currentGradient = speakingSweepGradient;
            } else if (nativeVoiceState == 3) {
                currentGradient = errorSweepGradient;
            } else if (nativeVoiceState == 1) {
                currentGradient = activeSweepGradient;
            } else if (isFlowing) {
                currentGradient = rainbowSweepGradient;
            } else {
                currentGradient = idleSweepGradient;
            }

            if (currentGradient != null) {
                currentGradient.setLocalMatrix(matrix);
                ringPaint.setShader(currentGradient);
                ringPaint.setStrokeWidth(4.2f);
                canvas.drawOval(ringBounds, ringPaint);
            }

            // ── 3. Perfectly Centered Crisp Microphone (Web Style) ──
            Paint mic = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (nativeVoiceState == 2) {
                mic.setColor(Color.parseColor("#F3E8FF"));
            } else if (nativeVoiceState == 3) {
                mic.setColor(Color.parseColor("#FFF1F2"));
            } else if (nativeVoiceState == 1) {
                mic.setColor(Color.parseColor("#FFFFFF")); // Pure White in Call
            } else {
                mic.setColor(Color.parseColor("#FFFFFF")); // Pure Crisp White in Idle
            }

            // Geometry mathematically centered around (cx, cy)
            float halfH = radius * 0.52f;
            float capW = radius * 0.36f;
            float capH = radius * 0.56f;
            float capTop = cy - halfH;
            float capBottom = capTop + capH;

            // 3a. Solid Capsule Body
            mic.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(cx - capW / 2f, capTop, cx + capW / 2f, capBottom, capW / 2f, capW / 2f, mic);

            // 3b. U-Shape Cradle Arc
            mic.setStyle(Paint.Style.STROKE);
            mic.setStrokeWidth(3.4f);
            mic.setStrokeCap(Paint.Cap.ROUND);
            float cradleRadius = radius * 0.34f;
            float cradleTop = capTop + capH * 0.38f;
            float cradleBottom = capBottom + radius * 0.16f;
            RectF cradleRect = new RectF(cx - cradleRadius, cradleTop, cx + cradleRadius, cradleBottom);
            canvas.drawArc(cradleRect, 0, 180, false, mic);

            // 3c. Vertical Stem
            float stemBottom = cy + halfH;
            canvas.drawLine(cx, cradleBottom, cx, stemBottom, mic);

            // 3d. Horizontal Base Foot
            float footSpan = radius * 0.22f;
            canvas.drawLine(cx - footSpan, stemBottom, cx + footSpan, stemBottom, mic);
        }
    }
}
