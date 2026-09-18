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
    private LinearLayout bubbleContainer = null;
    private TextView bubbleRemoveTargetView = null;
    private WindowManager.LayoutParams bubbleRemoveTargetParams = null;
    private boolean bubbleRemoveTargetActive = false;
    private ValueAnimator bubbleExpandAnimator = null;
    private int bubbleExpandAnimationGeneration = 0;
    private View voiceControlView = null;
    private boolean voiceControlsOpening = false;
    private WindowManager.LayoutParams voiceControlParams = null;
    private FloatingPanelController voiceControlController = null;
    private BubbleActionStripOverlay bubbleActionStrip = null;
    private View compactStatusView = null;
    private WindowManager.LayoutParams compactStatusParams = null;
    private FloatingPanelController compactStatusController = null;
    private Runnable compactStatusAutoHideRunnable = null;
    private String lastShownAgentStage = "";
    private ScreenSelectionOverlay screenSelectionOverlay = null;
    private static final int BUBBLE_SIZE_DP = 48;

    private DockIconButton voiceCallButton = null;
    private DockIconButton voiceCameraButton = null;
    private DockIconButton voiceScreenButton = null;
    private DockIconButton voiceMuteButton = null;
    private TextView voiceInterruptionButton = null;
    private TextView voiceWakeButton = null;
    private TextView voiceSensitivityButton = null;
    private TextView voicePresetButton = null;
    private TextView voiceOutputButton = null;
    private TextView voiceTeachSendButton = null;
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
    private RuntimeUiState latestLiveUiState = RuntimeUiState.idle("待命");
    private double latestMicDbfs = -96d;
    private boolean latestMicSending = false;
    private String latestLiveTranscript = "等待對話開始…";
    private String previousLiveTranscript = "";
    private String latestLiveTranscriptRole = "";
    private Runnable transcriptRefreshRunnable = null;
    private TextView dialogStatusText = null;
    private Button dialogStopButton = null;
    // Legacy screenshot buffer kept only for source compatibility; external server mode is removed.
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


    /**
     * 0098 "Point at this" UX.
     *
     * The explicit Region action opens a selector. Selection freezes the
     * current pixels as context but never starts Live or authorizes a tap.
     */
    public void startRegionSelection() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!canDrawOverlays()) {
                    Toast.makeText(
                            context,
                            "請先允許 Crew Helper 顯示懸浮視窗",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                collapseBubbleActions(false);
                hideVoiceControls();

                if (screenSelectionOverlay != null) {
                    screenSelectionOverlay.dismiss();
                    screenSelectionOverlay = null;
                }

                final ScreenSelectionOverlay overlay =
                        new ScreenSelectionOverlay(context);
                screenSelectionOverlay = overlay;

                boolean shown = overlay.show(
                        new ScreenSelectionOverlay.Callback() {
                            @Override
                            public void onSelected(
                                    android.graphics.Rect region,
                                    int screenWidth,
                                    int screenHeight) {
                                if (screenSelectionOverlay == overlay) {
                                    screenSelectionOverlay = null;
                                }

                                SelectedRegionContext selected =
                                        CrewAccessibilityService
                                                .describeSelectedRegion(
                                                        region,
                                                        screenWidth,
                                                        screenHeight);

                                if (selected.hardSensitive) {
                                    showCompactStatus(
                                            "無法使用這個區域",
                                            "框選內容包含密碼或敏感輸入");
                                    return;
                                }

                                showCompactStatus(
                                        "正在保存選取",
                                        "只記住這個區域，不會自動操作");

                                SelectedRegionSnapshotStore.capture(
                                        context,
                                        selected,
                                        new SelectedRegionSnapshotStore.Callback() {
                                            @Override
                                            public void onResult(
                                                    SelectedRegionContext frozen,
                                                    String error) {
                                                if (frozen == null) {
                                                    showCompactStatus(
                                                            "無法保存選取",
                                                            error == null || error.trim().isEmpty()
                                                                    ? "請確認輔助使用服務已啟用"
                                                                    : error);
                                                    return;
                                                }

                                                boolean accepted =
                                                        NativeLiveService.submitSelectedRegion(
                                                                context,
                                                                frozen);
                                                if (!accepted) {
                                                    showCompactStatus(
                                                            "選取未保存",
                                                            "請重新選取一次");
                                                    return;
                                                }

                                                wakeBubbleFromDock();


                                                if (bubbleView != null) {


                                                    bubbleView.flashContextReady();


                                                }


                                                showCompactStatus(


                                                        "已框選",


                                                        NativeLiveService.isActive()


                                                                ? "直接說：這是什麼、翻譯這段、幫我記下來"


                                                                : "兩分鐘內開始語音，再說你想怎麼處理");
                                            }
                                        });
                            }

                            @Override
                            public void onCancelled() {
                                if (screenSelectionOverlay == overlay) {
                                    screenSelectionOverlay = null;
                                }
                            }
                        });

                if (!shown) {
                    screenSelectionOverlay = null;
                    Toast.makeText(
                            context,
                            "框選視窗無法開啟，請重新允許懸浮視窗權限",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public void onAppTeachStateChanged(final String title, final String detail) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                refreshBubbleActionStripIfShowing();
            }
        });
        showCompactStatus(title, detail);
    }

    public void showCompactStatus(final String title, final String detail) {
        showRuntimeUiState(RuntimeUiState.fromLegacy(title, detail));
    }

    public void showRuntimeUiState(final RuntimeUiState state) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!canDrawOverlays()) return;

                if (compactStatusAutoHideRunnable != null) {
                    mainHandler.removeCallbacks(compactStatusAutoHideRunnable);
                    compactStatusAutoHideRunnable = null;
                }

                RuntimeUiState resolved = state == null
                        ? RuntimeUiState.info("", "")
                        : state;
                String heading = resolved.title;
                String body = resolved.detail;
                String primary = heading.isEmpty() ? body : heading;
                String secondary = heading.isEmpty()
                        || body.isEmpty()
                        || body.equals(heading)
                        || body.startsWith(heading)
                        ? "" : body;
                if (primary.isEmpty()) return;

                String message = secondary.isEmpty()
                        ? primary : primary + " · " + secondary;
                boolean error = resolved.isError();
                boolean attention = resolved.needsAttention();
                boolean contextReady =
                        resolved.phase == RuntimeUiState.Phase.CONTEXT_READY;
                boolean done = resolved.isSuccess();

                if (compactStatusView != null) {
                    try { windowManager.removeViewImmediate(compactStatusView); }
                    catch (Exception ignored) {}
                    compactStatusView = null;
                }

                int screenW = windowManager.getDefaultDisplay().getWidth();
                int screenH = windowManager.getDefaultDisplay().getHeight();
                int maxCardWidth = Math.min(dp(236), screenW - dp(24));
                int maxCardContentWidth = Math.max(dp(48), maxCardWidth - dp(24));

                final LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(6), dp(12), dp(6));
                card.setContentDescription(message);

                TextView headingView = new TextView(context);
                headingView.setText(primary);
                headingView.setSingleLine(true);
                headingView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                headingView.setTextSize(12.5f);
                headingView.setTextColor(Color.parseColor("#F8FAFC"));
                headingView.setTypeface(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD);
                headingView.setMaxWidth(maxCardContentWidth);
                card.addView(
                        headingView,
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                if (!secondary.isEmpty()) {
                    TextView detailView = new TextView(context);
                    detailView.setText(secondary);
                    detailView.setMaxLines(2);
                    detailView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    detailView.setTextSize(11f);
                    detailView.setTextColor(Color.parseColor("#CBD5E1"));
                    detailView.setMaxWidth(maxCardContentWidth);
                    LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    detailLp.topMargin = dp(1);
                    card.addView(detailView, detailLp);
                }

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.argb(242, 30, 41, 59));
                bg.setCornerRadius(dp(16));
                String stroke = error ? "#E11D48"
                        : attention ? "#D97706"
                        : contextReady ? "#0EA5E9"
                        : done ? "#0F766E"
                        : "#475569";
                bg.setStroke(dp(1), Color.parseColor(stroke));
                card.setBackground(bg);
                card.setElevation(dp(10));

                // Measure the content first so short status messages stay compact,
                // while long messages remain bounded and ellipsized near the edge.
                card.measure(
                        View.MeasureSpec.makeMeasureSpec(
                                maxCardWidth, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(
                                screenH, View.MeasureSpec.AT_MOST));
                int cardWidth = Math.max(
                        dp(48),
                        Math.min(maxCardWidth, card.getMeasuredWidth()));
                int cardHeight = Math.max(
                        secondary.isEmpty() ? dp(40) : dp(58),
                        card.getMeasuredHeight());

                final WindowManager.LayoutParams lp =
                        new WindowManager.LayoutParams(
                                cardWidth,
                                cardHeight,
                                Build.VERSION.SDK_INT >= 26
                                        ? 2038
                                        : WindowManager.LayoutParams.TYPE_PHONE,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;

                if (bubbleView != null && bubbleParams != null) {
                    int bubbleSize = bubbleParams.width > 0
                            ? bubbleParams.width
                            : dp(BUBBLE_SIZE_DP);
                    boolean bubbleOnLeft =
                            bubbleParams.x + bubbleSize / 2 < screenW / 2;
                    int targetX = bubbleOnLeft
                            ? bubbleParams.x + bubbleSize + dp(8)
                            : bubbleParams.x - cardWidth - dp(8);
                    lp.x = Math.max(
                            dp(8),
                            Math.min(screenW - cardWidth - dp(8), targetX));

                    int targetY =
                            bubbleParams.y + (bubbleSize - cardHeight) / 2;
                    int top = getStatusBarHeight() + dp(4);
                    int bottom = screenH - cardHeight - dp(64);
                    lp.y = Math.max(top, Math.min(bottom, targetY));
                } else {
                    lp.x = dp(16);
                    lp.y = dp(96);
                }

                try {
                    card.setAlpha(0f);
                    card.setScaleX(0.96f);
                    card.setScaleY(0.96f);
                    windowManager.addView(card, lp);
                    compactStatusView = card;
                    compactStatusParams = lp;
                    compactStatusController = null;
                    card.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140L)
                            .start();
                } catch (Exception ignored) {
                    compactStatusView = null;
                    compactStatusParams = null;
                    return;
                }

                final long autoHideMs = resolved.recommendedAutoHideMs();
                compactStatusAutoHideRunnable = new Runnable() {
                    @Override public void run() {
                        hideCompactStatus();
                    }
                };
                mainHandler.postDelayed(
                        compactStatusAutoHideRunnable,
                        autoHideMs);
            }
        });
    }

    /**
     * 0090 Bubble State Feedback.
     *
     * Ordinary Agent progress is represented by the bubble ring itself:
     * a bright cyan spinner arc while the task is active.
     *
     * Text is reserved only for states that require user intervention or
     * explain a real failure. Agent Inspector remains the detailed timeline.
     */
    public void updateAgentTaskStatus(final String rawStatus,
                                      final boolean activeTask) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                String important =
                        AgentInspectorStore.quietFeedbackLabel(
                                rawStatus, activeTask);

                if (bubbleView == null) return;

                if (activeTask) {
                    boolean needsAttention =
                            important != null && !important.isEmpty();

                    bubbleView.setAgentNeedsAttention(needsAttention);
                    bubbleView.setAgentWorking(!needsAttention);

                    if (needsAttention) {
                        lastShownAgentStage = "";
                        String detail = "需要你選擇".equals(important)
                                ? "直接說「第一個」或選項名稱"
                                : ("需要權限".equals(important)
                                        ? "完成權限設定後，再回來繼續"
                                        : "");
                        showRuntimeUiState(
                                RuntimeUiState.waitingUser(important, detail));
                        return;
                    }

                    // Normal progress remains quiet. If the user has explicitly
                    // expanded the bubble rail, show one compact human-readable
                    // step beside it instead of exposing Runtime/debug text.
                    if (bubbleActionStrip != null && bubbleActionStrip.isShowing()) {
                        String stage = AgentInspectorStore.friendlyStage(
                                rawStatus, true);
                        if (stage != null
                                && !stage.isEmpty()
                                && !stage.equals(lastShownAgentStage)) {
                            lastShownAgentStage = stage;
                            showRuntimeUiState(
                                    RuntimeUiState.working("Crew 正在處理", stage));
                        }
                    }
                    return;
                }

                lastShownAgentStage = "";
                bubbleView.setAgentWorking(false);
                bubbleView.setAgentNeedsAttention(false);

                if (AgentInspectorStore.isSuccessfulTaskEnd(rawStatus)) {
                    bubbleView.flashAgentResult(true);
                    return;
                }

                if ("操作失敗".equals(important)) {
                    bubbleView.flashAgentResult(false);
                    showRuntimeUiState(
                            RuntimeUiState.error(
                                    important,
                                    "可以再說一次，或打開控制台查看狀態"));
                    return;
                }

                if (important != null && !important.isEmpty()) {
                    showRuntimeUiState(
                            RuntimeUiState.info(important, ""));
                }
            }
        });
    }

    public void hideCompactStatus() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (compactStatusAutoHideRunnable != null) {
                    mainHandler.removeCallbacks(compactStatusAutoHideRunnable);
                    compactStatusAutoHideRunnable = null;
                }
                try {
                    if (compactStatusView != null) windowManager.removeViewImmediate(compactStatusView);
                } catch (Exception ignored) {}
                compactStatusView = null;
                compactStatusParams = null;
                compactStatusController = null;
            }
        });
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
        dismissBubbleRemoveTarget();
        autoDockHandler.removeCallbacks(autoDockRunnable);
        if (dockAnimator != null) {
            dockAnimator.cancel();
            dockAnimator = null;
        }
        bubbleExpandAnimationGeneration++;
        if (bubbleExpandAnimator != null) {
            bubbleExpandAnimator.cancel();
            bubbleExpandAnimator = null;
        }
        if (bubbleContainer != null) {
            try { windowManager.removeView(bubbleContainer); } catch (Exception ignored) {}
        }
        bubbleContainer = null;
        bubbleView = null;
        bubbleActionStrip = null;
    }

    public boolean isBubbleShowing() {
        return bubbleView != null;
    }

    public void scheduleAutoDock() {
        // The 48dp Listening Core is intentionally small enough to remain
        // available. Do not fade or push it half off-screen after idle.
        autoDockHandler.removeCallbacks(autoDockRunnable);
    }

    public void wakeBubbleFromDock() {
        autoDockHandler.removeCallbacks(autoDockRunnable);
        if (bubbleView == null || bubbleContainer == null || bubbleParams == null) return;
        if (dockAnimator != null && dockAnimator.isRunning()) {
            dockAnimator.cancel();
        }
        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        int bSize = dp(BUBBLE_SIZE_DP);
        int targetX = (bubbleParams.x < screenWidth / 2)
                ? dp(4)
                : (screenWidth - bSize - dp(4));

        bubbleParams.x = targetX;
        bubbleView.setAlpha(1.0f);
        try { windowManager.updateViewLayout(bubbleContainer, bubbleParams); }
        catch (Exception ignored) {}
        isDocked = false;
    }

    public void autoDockBubble() {
        // Kept as a harmless compatibility entry point for older callers.
        // Docking is disabled so the floating assistant remains visible.
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

                    final int size = dp(BUBBLE_SIZE_DP);
                    bubbleParams = new WindowManager.LayoutParams(
                            size,
                            size,
                            overlayType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    bubbleParams.gravity = Gravity.TOP | Gravity.START;

                    int screenH = windowManager.getDefaultDisplay().getHeight();
                    int safeTop = getStatusBarHeight() + dp(12);
                    bubbleParams.x = dp(4);
                    bubbleParams.y = Math.max(safeTop, screenH / 3);

                    bubbleContainer = new LinearLayout(context);
                    bubbleContainer.setOrientation(LinearLayout.VERTICAL);
                    bubbleContainer.setGravity(Gravity.CENTER_HORIZONTAL);
                    bubbleContainer.setClipChildren(true);
                    bubbleContainer.setClipToPadding(false);

                    bubbleView = new FluidBubbleView(context);
                    bubbleView.setElevation(16f);
                    bubbleContainer.addView(
                            bubbleView,
                            new LinearLayout.LayoutParams(size, size));

                    bubbleActionStrip = new BubbleActionStripOverlay(context);
                    bubbleContainer.addView(
                            bubbleActionStrip,
                            new LinearLayout.LayoutParams(
                                    size,
                                    LinearLayout.LayoutParams.WRAP_CONTENT));

                    bubbleView.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float initialTouchX, initialTouchY;
                        private boolean moved = false;
                        private boolean removeTargetEntered = false;

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
                                    moved = false;
                                    removeTargetEntered = false;
                                    dismissBubbleRemoveTarget();
                                    if (isDocked) {
                                        wakeBubbleFromDock();
                                    } else {
                                        autoDockHandler.removeCallbacks(autoDockRunnable);
                                    }
                                    return true;

                                case MotionEvent.ACTION_MOVE:
                                    float moveDist = (float) Math.hypot(
                                            event.getRawX() - initialTouchX,
                                            event.getRawY() - initialTouchY);
                                    if (moveDist > dp(32) && !moved) {
                                        moved = true;
                                        collapseBubbleActions(false);
                                        showBubbleRemoveTarget();
                                        initialX = bubbleParams.x;
                                        initialY = bubbleParams.y;
                                        initialTouchX = event.getRawX();
                                        initialTouchY = event.getRawY();
                                    }

                                    // Do not update an overlay's window position for
                                    // tiny finger jitter. Updating WindowManager during
                                    // an active touch can emit ACTION_CANCEL and would
                                    // cancel the 3-second hold timer.
                                    if (!moved) return true;

                                    int targetX = initialX
                                            + (int) (event.getRawX() - initialTouchX);
                                    int targetY = initialY
                                            + (int) (event.getRawY() - initialTouchY);
                                    bubbleParams.x = Math.max(
                                            leftLimit,
                                            Math.min(rightLimit, targetX));
                                    bubbleParams.y = Math.max(
                                            topLimit,
                                            Math.min(bottomLimit, targetY));
                                    boolean insideRemoveTarget = isInsideBubbleRemoveTarget(
                                            event.getRawX(), event.getRawY());
                                    if (insideRemoveTarget != removeTargetEntered) {
                                        removeTargetEntered = insideRemoveTarget;
                                        setBubbleRemoveTargetActive(insideRemoveTarget);
                                        if (insideRemoveTarget) {
                                            vibrateShort();
                                        }
                                    }
                                    bubbleView.setAlpha(insideRemoveTarget ? 0.72f : 1.0f);
                                    bubbleView.setScaleX(insideRemoveTarget ? 0.88f : 1.0f);
                                    bubbleView.setScaleY(insideRemoveTarget ? 0.88f : 1.0f);
                                    isDocked = false;
                                    windowManager.updateViewLayout(
                                            bubbleContainer,
                                            bubbleParams);
                                    return true;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    boolean shouldHide = moved
                                            && removeTargetEntered
                                            && event.getActionMasked() == MotionEvent.ACTION_UP;
                                    dismissBubbleRemoveTarget();

                                    if (shouldHide) {
                                        vibrateSuccess();
                                        hideBubble();
                                        Toast.makeText(
                                                context,
                                                "浮動泡泡已隱藏 · 可從通知或 App 顯示",
                                                Toast.LENGTH_SHORT).show();
                                        return true;
                                    }

                                    if (bubbleView != null) {
                                        bubbleView.setAlpha(1.0f);
                                        bubbleView.setScaleX(1.0f);
                                        bubbleView.setScaleY(1.0f);
                                    }

                                    if (!moved) {
                                        float dx = Math.abs(
                                                event.getRawX() - initialTouchX);
                                        float dy = Math.abs(
                                                event.getRawY() - initialTouchY);
                                        if (dx < dp(14)
                                                && dy < dp(14)
                                                && event.getActionMasked() == MotionEvent.ACTION_UP) {
                                            vibrateShort();
                                            toggleBubbleActionStrip();
                                        }
                                    }
                                    snapBubbleToEdge();
                                    scheduleAutoDock();
                                    return true;
                            }
                            return false;
                        }
                    });

                    windowManager.addView(bubbleContainer, bubbleParams);
                    isDocked = false;
                    scheduleAutoDock();
                    if (onShown != null) onShown.run();
                } catch (Exception e) {
                    bubbleContainer = null;
                    bubbleView = null;
                    bubbleActionStrip = null;
                    e.printStackTrace();
                }
            }
        });
    }

    private int getNavigationBarHeight() {
        try {
            int resId = context.getResources().getIdentifier(
                    "navigation_bar_height", "dimen", "android");
            if (resId > 0) {
                return context.getResources().getDimensionPixelSize(resId);
            }
        } catch (Exception ignored) {}
        return dp(24);
    }

    private void showBubbleRemoveTarget() {
        if (bubbleRemoveTargetView != null || !canDrawOverlays()) return;
        try {
            TextView target = new TextView(context);
            target.setText("✕  放這裡隱藏");
            target.setTextSize(14f);
            target.setTextColor(Color.parseColor("#F8FAFC"));
            target.setGravity(Gravity.CENTER);
            target.setTypeface(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD);
            target.setPadding(dp(18), 0, dp(18), 0);
            target.setAlpha(0f);
            target.setScaleX(0.92f);
            target.setScaleY(0.92f);

            GradientDrawable bg = bubbleRemoveTargetBackground(false);
            target.setBackground(bg);
            target.setElevation(dp(16));

            int overlayType = Build.VERSION.SDK_INT >= 26
                    ? 2038
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(144),
                    dp(56),
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            lp.y = getNavigationBarHeight() + dp(12);

            windowManager.addView(target, lp);
            bubbleRemoveTargetView = target;
            bubbleRemoveTargetParams = lp;
            bubbleRemoveTargetActive = false;

            target.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start();
        } catch (Exception ignored) {
            bubbleRemoveTargetView = null;
            bubbleRemoveTargetParams = null;
            bubbleRemoveTargetActive = false;
        }
    }

    private void dismissBubbleRemoveTarget() {
        if (bubbleRemoveTargetView != null) {
            try { windowManager.removeViewImmediate(bubbleRemoveTargetView); }
            catch (Exception ignored) {}
        }
        bubbleRemoveTargetView = null;
        bubbleRemoveTargetParams = null;
        bubbleRemoveTargetActive = false;
    }

    private boolean isInsideBubbleRemoveTarget(float rawX, float rawY) {
        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        int screenHeight = windowManager.getDefaultDisplay().getHeight();
        float centerX = screenWidth / 2f;
        float centerY = screenHeight
                - getNavigationBarHeight()
                - dp(12)
                - dp(28);
        float dx = rawX - centerX;
        float dy = rawY - centerY;
        return Math.hypot(dx, dy) <= dp(92);
    }

    private void setBubbleRemoveTargetActive(boolean active) {
        if (bubbleRemoveTargetView == null
                || bubbleRemoveTargetActive == active) {
            return;
        }
        bubbleRemoveTargetActive = active;
        bubbleRemoveTargetView.setBackground(
                bubbleRemoveTargetBackground(active));
        bubbleRemoveTargetView.animate()
                .scaleX(active ? 1.10f : 1.0f)
                .scaleY(active ? 1.10f : 1.0f)
                .setDuration(90L)
                .start();
    }

    private GradientDrawable bubbleRemoveTargetBackground(boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        if (active) {
            bg.setColor(Color.argb(245, 190, 24, 93));
            bg.setStroke(dp(2), Color.parseColor("#FDA4AF"));
        } else {
            bg.setColor(Color.argb(235, 39, 39, 42));
            bg.setStroke(dp(1), Color.parseColor("#71717A"));
        }
        return bg;
    }

    private int getStatusBarHeight() {
        try {
            int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) return context.getResources().getDimensionPixelSize(resId);
        } catch (Exception ignored) {}
        return dp(32);
    }

    private void snapBubbleToEdge() {
        if (bubbleView == null || bubbleContainer == null || bubbleParams == null) return;
        try {
            int screenWidth = windowManager.getDefaultDisplay().getWidth();
            int screenHeight = windowManager.getDefaultDisplay().getHeight();
            int bSize = dp(BUBBLE_SIZE_DP);
            int topLimit = getStatusBarHeight() + dp(4);
            int visibleHeight = Math.max(bSize, bubbleParams.height);
            int bottomLimit = Math.max(
                    topLimit,
                    screenHeight - visibleHeight - dp(16));

            bubbleParams.x = (bubbleParams.x < screenWidth / 2)
                    ? dp(4)
                    : (screenWidth - bSize - dp(4));
            bubbleParams.y = Math.max(
                    topLimit,
                    Math.min(bottomLimit, bubbleParams.y));
            windowManager.updateViewLayout(bubbleContainer, bubbleParams);
        } catch (Exception ignored) {}
    }

    private void ensureShortcutRoomBelow(int bubbleSize) {
        if (bubbleContainer == null || bubbleParams == null) return;
        try {
            int screenHeight = windowManager.getDefaultDisplay().getHeight();
            int shortcutHeight = bubbleActionStrip == null
                    ? dp(94)
                    : bubbleActionStrip.desiredHeightPx();
            int requiredBottom =
                    bubbleParams.y + bubbleSize + shortcutHeight + dp(16);
            if (requiredBottom <= screenHeight) return;

            int delta = requiredBottom - screenHeight;
            int topLimit = getStatusBarHeight() + dp(4);
            bubbleParams.y = Math.max(
                    topLimit,
                    bubbleParams.y - delta);
            windowManager.updateViewLayout(bubbleContainer, bubbleParams);
        } catch (Exception ignored) {}
    }

    private void toggleBubbleActionStrip() {
        if (bubbleView == null
                || bubbleContainer == null
                || bubbleParams == null
                || bubbleActionStrip == null) {
            return;
        }

        if (bubbleActionStrip.isShowing()) {
            collapseBubbleActions(true);
        } else {
            expandBubbleActions();
        }
    }

    private void expandBubbleActions() {
        if (bubbleContainer == null
                || bubbleParams == null
                || bubbleActionStrip == null) {
            return;
        }

        bubbleActionStrip.show(bubbleActionStripActions());
        ensureShortcutRoomBelow(dp(BUBBLE_SIZE_DP));
        setBubbleContainerExpandedStyle(true);

        int targetHeight =
                dp(BUBBLE_SIZE_DP)
                        + bubbleActionStrip.desiredHeightPx();
        animateBubbleContainerHeight(targetHeight, 160L, null);
    }

    private void collapseBubbleActions(boolean animated) {
        if (bubbleContainer == null
                || bubbleParams == null
                || bubbleActionStrip == null
                || !bubbleActionStrip.isShowing()) {
            return;
        }

        Runnable finish = new Runnable() {
            @Override public void run() {
                if (bubbleActionStrip != null) {
                    bubbleActionStrip.dismiss();
                }
                setBubbleContainerExpandedStyle(false);
            }
        };

        if (animated) {
            animateBubbleContainerHeight(
                    dp(BUBBLE_SIZE_DP),
                    140L,
                    finish);
        } else {
            bubbleExpandAnimationGeneration++;
            if (bubbleExpandAnimator != null) {
                bubbleExpandAnimator.cancel();
                bubbleExpandAnimator = null;
            }
            bubbleParams.height = dp(BUBBLE_SIZE_DP);
            try {
                windowManager.updateViewLayout(
                        bubbleContainer,
                        bubbleParams);
            } catch (Exception ignored) {}
            finish.run();
        }
    }

    private void setBubbleContainerExpandedStyle(boolean expanded) {
        if (bubbleContainer == null) return;
        if (!expanded) {
            bubbleContainer.setBackground(null);
            return;
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(238, 58, 58, 60));
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), Color.parseColor("#666B7280"));
        bubbleContainer.setBackground(bg);
    }

    private void animateBubbleContainerHeight(
            int targetHeight,
            long durationMs,
            final Runnable endAction) {
        if (bubbleContainer == null || bubbleParams == null) return;

        final int generation = ++bubbleExpandAnimationGeneration;
        if (bubbleExpandAnimator != null) {
            bubbleExpandAnimator.cancel();
        }

        final int startHeight =
                bubbleParams.height > 0
                        ? bubbleParams.height
                        : dp(BUBBLE_SIZE_DP);
        if (startHeight == targetHeight) {
            if (endAction != null) endAction.run();
            return;
        }

        bubbleExpandAnimator =
                ValueAnimator.ofInt(startHeight, targetHeight);
        bubbleExpandAnimator.setDuration(durationMs);
        bubbleExpandAnimator.setInterpolator(
                new DecelerateInterpolator());
        bubbleExpandAnimator.addUpdateListener(animation -> {
            if (bubbleContainer == null || bubbleParams == null) return;
            bubbleParams.height = (Integer) animation.getAnimatedValue();
            try {
                windowManager.updateViewLayout(
                        bubbleContainer,
                        bubbleParams);
            } catch (Exception ignored) {}
        });
        bubbleExpandAnimator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (generation != bubbleExpandAnimationGeneration) return;
                        bubbleExpandAnimator = null;
                        if (endAction != null) endAction.run();
                    }
                });
        bubbleExpandAnimator.start();
    }

    private BubbleActionStripOverlay.Actions bubbleActionStripActions() {
        return new BubbleActionStripOverlay.Actions() {
            @Override public void onToggleCall() {
                collapseBubbleActions(false);
                toggleNativeLive();
                refreshVoiceControls();
            }

            @Override public void onToggleMute() {
                collapseBubbleActions(false);
                boolean muted = NativeLiveService.toggleAgentMute();
                showCompactStatus(
                        muted ? "已靜音" : "已取消靜音",
                        "");
                refreshVoiceControls();
            }

            @Override public void onOpenConsole() {
                collapseBubbleActions(false);
                showVoiceControls();
            }

            @Override public void onInterrupt() {
                collapseBubbleActions(false);
                if (NativeLiveService.interruptForCorrection()) {
                    showCompactStatus("已打斷", "");
                }
                refreshVoiceControls();
            }

            @Override public void onTeachCurrentApp() {
                NativeLiveService.toggleAppTeachMode();
                refreshBubbleActionStripIfShowing();
            }

            @Override public void onRegionSelection() {
                collapseBubbleActions(false);
                startRegionSelection();
            }
        };
    }

    private void refreshBubbleActionStripIfShowing() {
        if (bubbleActionStrip == null
                || !bubbleActionStrip.isShowing()
                || bubbleContainer == null
                || bubbleParams == null) {
            return;
        }

        bubbleActionStrip.refresh(bubbleActionStripActions());
        ensureShortcutRoomBelow(dp(BUBBLE_SIZE_DP));
        int targetHeight =
                dp(BUBBLE_SIZE_DP)
                        + bubbleActionStrip.desiredHeightPx();
        animateBubbleContainerHeight(
                targetHeight,
                100L,
                null);
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
            if (!wasBusy) {
                vibrateShort();
                showCompactStatus("正在處理", text);
            }
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
            showCompactStatus("操作失敗", text);
        } else if ("DONE".equalsIgnoreCase(state) || "COMPLETED".equalsIgnoreCase(state)) {
            setThinkingState(false);
            vibrateSuccess();
            String doneStatus = text == null || text.isEmpty() ? "已完成" : "已完成 · " + text;
            updateDialogStatus(doneStatus);
            showCompactStatus("已完成", text);
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
                previousLiveTranscript = "";
                latestLiveTranscript = "等待對話開始…";
                latestLiveTranscriptRole = "";
                nativeLiveRequested = true;
                NativeLiveService.start(context);
                // Give immediate visual feedback; the service will replace it
                // with its real connection status moments later.
                updateNativeLiveStatus("正在連線 Gemini Live", true);
                // Revised 0015: do not auto-open the full console.
            }
        } catch (Exception error) {
        }
    }

    /** Called by the foreground voice service; intentionally does not open a panel. */
    public void updateNativeLiveStatus(final String text, final boolean active) {
        updateNativeLiveState(
                RuntimeUiState.fromLiveStatus(text, active),
                active);
    }

    public void updateNativeLiveState(
            final RuntimeUiState state,
            final boolean active) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                nativeLiveRequested = active;
                latestLiveUiState = state == null
                        ? RuntimeUiState.fromLiveStatus("", active)
                        : state;
                latestLiveStatus = latestLiveUiState.title.isEmpty()
                        ? (active ? "語音通話中" : "待命")
                        : latestLiveUiState.title;
                if (bubbleView != null) {
                    int voiceState = latestLiveUiState.isError()
                            ? 3
                            : (latestLiveUiState.phase
                                            == RuntimeUiState.Phase.SPEAKING
                                    ? 2
                                    : (active ? 1 : 0));
                    bubbleView.setNativeVoiceState(voiceState);
                    if (!active) {
                        bubbleView.setMicrophoneActivity(-96d, false);
                    }
                }
                refreshVoiceControls();
                refreshBubbleActionStripIfShowing();
            }
        });
    }

    /** Lightweight telemetry from the foreground voice service for the expanded dock. */
    public void updateLiveMicrophoneLevel(final double dbfs, final boolean sending) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                latestMicDbfs = dbfs;
                latestMicSending = sending;
                if (bubbleView != null) {
                    bubbleView.setMicrophoneActivity(
                            dbfs,
                            sending && NativeLiveService.isActive());
                }
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
                    if (!latestLiveTranscriptRole.isEmpty()
                            && latestLiveTranscript != null
                            && !latestLiveTranscript.trim().isEmpty()
                            && !"等待對話開始…".equals(latestLiveTranscript.trim())) {
                        previousLiveTranscript = latestLiveTranscript;
                    }
                    latestLiveTranscriptRole = speaker;
                    latestLiveTranscript = speaker + "：" + fragment;
                } else {
                    String prefix = speaker + "：";
                    String existing = latestLiveTranscript.startsWith(prefix)
                            ? latestLiveTranscript.substring(prefix.length()) : latestLiveTranscript;
                    if (fragment.startsWith(existing)) {
                        latestLiveTranscript = prefix + fragment;
                    } else if (existing.endsWith(fragment)) {
                        // Duplicate fragment, do nothing
                    } else {
                        // Check if boundary needs a space (e.g. between English words/alphanumeric)
                        boolean needSpace = false;
                        if (!existing.isEmpty() && !fragment.isEmpty()) {
                            char lastC = existing.charAt(existing.length() - 1);
                            char firstC = fragment.charAt(0);
                            boolean lastIsAlpha = Character.isLetterOrDigit(lastC);
                            boolean firstIsAlpha = Character.isLetterOrDigit(firstC);
                            boolean lastIsPunct = (lastC == '.' || lastC == ',' || lastC == '!' || lastC == '?' || lastC == ';' || lastC == ':');
                            boolean lastIsCjk = (lastC >= 0x4E00 && lastC <= 0x9FFF) || (lastC >= 0x3400 && lastC <= 0x4DBF);
                            boolean firstIsCjk = (firstC >= 0x4E00 && firstC <= 0x9FFF) || (firstC >= 0x3400 && firstC <= 0x4DBF);

                            if (!lastIsCjk && !firstIsCjk && (lastIsAlpha || lastIsPunct) && firstIsAlpha) {
                                needSpace = true;
                            }
                        }
                        latestLiveTranscript = prefix + existing + (needSpace ? " " : "") + fragment;
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

    private void updateVoiceTelemetryUi() {
        boolean liveRequested = nativeLiveRequested || NativeLiveService.isActive();
        boolean error = latestLiveUiState != null && latestLiveUiState.isError();
        boolean activeTask = NativeLiveService.hasActiveAgentTask();
        boolean speaking = NativeLiveService.isAiSpeaking();
        boolean muted = NativeLiveService.isAgentMuted();

        String statusText;
        int statusColor;
        if (error) {
            statusText = latestLiveStatus == null || latestLiveStatus.trim().isEmpty()
                    ? "連線發生錯誤"
                    : latestLiveStatus.trim();
            statusColor = Color.parseColor("#FB7185");
        } else if (activeTask) {
            statusText = "正在執行任務…";
            statusColor = Color.parseColor("#FBBF24");
        } else if (speaking) {
            statusText = "Gemini 正在回覆";
            statusColor = Color.parseColor("#FCD34D");
        } else if (muted) {
            statusText = "麥克風已靜音";
            statusColor = Color.parseColor("#FDA4AF");
        } else if (liveRequested) {
            if (latestLiveUiState != null
                    && latestLiveUiState.phase
                            == RuntimeUiState.Phase.CONNECTING) {
                statusText = "正在連線…";
                statusColor = Color.parseColor("#93C5FD");
            } else {
                statusText = "正在聆聽";
                statusColor = Color.parseColor("#5EEAD4");
            }
        } else {
            statusText = "待命";
            statusColor = Color.parseColor("#A1A1AA");
        }

        if (voiceStatusText != null) {
            voiceStatusText.setText("● " + statusText);
            voiceStatusText.setTextColor(statusColor);
        }
        if (voiceMeterText != null) {
            voiceMeterText.setText(liveRequested ? "Gemini Live" : "語音助理");
            voiceMeterText.setTextColor(Color.parseColor("#A1A1AA"));
        }
        if (voiceStopAgentButton != null) {
            boolean showStop = activeTask || speaking;
            voiceStopAgentButton.setVisibility(showStop ? View.VISIBLE : View.GONE);
            if (showStop) {
                voiceStopAgentButton.setText(
                        activeTask ? "■ 停止目前任務" : "■ 停止 Gemini 回覆");
            }
        }
    }

    private void updateVoiceTranscriptUi() {
        if (voiceTranscriptText == null) return;

        boolean liveRequested = nativeLiveRequested || NativeLiveService.isActive();
        String current = latestLiveTranscript == null ? "" : latestLiveTranscript.trim();
        boolean hasCurrent = !current.isEmpty()
                && !"等待對話開始…".equals(current);

        if (!liveRequested || !hasCurrent) {
            voiceTranscriptText.setText("");
            voiceTranscriptText.setVisibility(View.GONE);
            return;
        }

        String previous = previousLiveTranscript == null
                ? ""
                : previousLiveTranscript.trim();
        String display = current;
        if (!previous.isEmpty()
                && !"等待對話開始…".equals(previous)
                && !previous.equals(current)) {
            display = previous + "\n" + current;
        }
        voiceTranscriptText.setText(display);
        voiceTranscriptText.setVisibility(View.VISIBLE);
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

    private LinearLayout makeConsoleActionCell(
            DockIconButton button,
            String label) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2), 0, dp(2), 0);

        cell.addView(
                button,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48)));

        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextSize(9.5f);
        caption.setTextColor(Color.parseColor("#D4D4D8"));
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        cell.addView(
                caption,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(18)));
        return cell;
    }

    private void showVoiceControls() {
        if (!canDrawOverlays() || voiceControlView != null || voiceControlsOpening) return;
        voiceControlsOpening = true;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!voiceControlsOpening) return;
                try {
                    if (dialogView != null) hideDialog();
                    int overlayType = Build.VERSION.SDK_INT >= 26
                            ? 2038
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    int screenWidth = windowManager.getDefaultDisplay().getWidth();
                    int screenHeight = windowManager.getDefaultDisplay().getHeight();

                    int dockWidth = Math.min(dp(316), screenWidth - dp(24));
                    voiceControlParams = new WindowManager.LayoutParams(
                            dockWidth,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            overlayType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    voiceControlParams.gravity = Gravity.TOP | Gravity.START;
                    voiceControlParams.x = Math.max(
                            dp(12),
                            (screenWidth - dockWidth) / 2);
                    voiceControlParams.y = Math.max(
                            getStatusBarHeight() + dp(18),
                            screenHeight - dp(350));

                    LinearLayout dock = new LinearLayout(context);
                    dock.setOrientation(LinearLayout.VERTICAL);
                    dock.setPadding(dp(14), dp(12), dp(14), dp(14));
                    dock.setClipToPadding(false);
                    dock.setClipChildren(false);

                    GradientDrawable dockBg = new GradientDrawable();
                    dockBg.setColor(Color.parseColor("#F23A3A3C"));
                    dockBg.setCornerRadius(dp(22));
                    dockBg.setStroke(dp(1), Color.parseColor("#666B7280"));
                    dock.setBackground(dockBg);
                    dock.setElevation(dp(16));

                    // Human-readable state is the visual anchor of the console.
                    LinearLayout headerRow = new LinearLayout(context);
                    headerRow.setOrientation(LinearLayout.HORIZONTAL);
                    headerRow.setGravity(Gravity.CENTER_VERTICAL);
                    headerRow.setPadding(dp(2), 0, 0, dp(8));

                    LinearLayout statusStack = new LinearLayout(context);
                    statusStack.setOrientation(LinearLayout.VERTICAL);
                    statusStack.setGravity(Gravity.CENTER_VERTICAL);

                    voiceStatusText = new TextView(context);
                    voiceStatusText.setTextSize(15);
                    voiceStatusText.setTypeface(
                            android.graphics.Typeface.DEFAULT_BOLD);
                    voiceStatusText.setSingleLine(true);
                    statusStack.addView(
                            voiceStatusText,
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT));

                    // Reuse the old meter field as a quiet subtitle. Raw dB is
                    // intentionally removed from the primary UI.
                    voiceMeterText = new TextView(context);
                    voiceMeterText.setTextSize(10.5f);
                    voiceMeterText.setTextColor(Color.parseColor("#A1A1AA"));
                    voiceMeterText.setPadding(0, dp(2), 0, 0);
                    statusStack.addView(
                            voiceMeterText,
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT));

                    headerRow.addView(
                            statusStack,
                            new LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f));

                    voiceSettingsToggleButton = makeVoiceSettingButton();
                    voiceSettingsToggleButton.setText("⚙");
                    voiceSettingsToggleButton.setTextSize(19);
                    voiceSettingsToggleButton.setContentDescription("進階設定");
                    voiceSettingsToggleButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    if (voiceSettingsPanel == null) return;
                                    boolean show =
                                            voiceSettingsPanel.getVisibility()
                                                    != View.VISIBLE;
                                    voiceSettingsPanel.setVisibility(
                                            show ? View.VISIBLE : View.GONE);
                                    voiceSettingsPanel.requestLayout();
                                    if (voiceControlView != null) {
                                        voiceControlView.requestLayout();
                                    }
                                    if (!show && voiceSettingsChoices != null) {
                                        voiceSettingsChoices.removeAllViews();
                                    }
                                }
                            });
                    headerRow.addView(
                            voiceSettingsToggleButton,
                            new LinearLayout.LayoutParams(dp(38), dp(38)));

                    TextView close = new TextView(context);
                    close.setText("✕");
                    close.setTextSize(18);
                    close.setGravity(Gravity.CENTER);
                    close.setTextColor(Color.parseColor("#A1A1AA"));
                    close.setContentDescription("關閉控制台");
                    close.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            hideVoiceControls();
                        }
                    });
                    LinearLayout.LayoutParams closeLp =
                            new LinearLayout.LayoutParams(dp(38), dp(38));
                    closeLp.setMargins(dp(4), 0, 0, 0);
                    headerRow.addView(close, closeLp);
                    dock.addView(headerRow);

                    // Primary controls: keep the existing polished Canvas icons.
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER);
                    row.setClipChildren(false);
                    row.setClipToPadding(false);
                    row.setPadding(0, dp(3), 0, dp(3));

                    voiceCameraButton = makeDockIconButton();
                    voiceScreenButton = makeDockIconButton();
                    voiceMuteButton = makeDockIconButton();
                    voiceCallButton = makeDockIconButton();

                    voiceCameraButton.setContentDescription("切換相機分享");
                    voiceScreenButton.setContentDescription("讓 Gemini 看目前畫面");
                    voiceMuteButton.setContentDescription("麥克風靜音或打斷 Gemini");
                    voiceCallButton.setContentDescription("開始或結束 Live 通話");

                    voiceCameraButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) {
                                Toast.makeText(
                                        context,
                                        "請先開始通話並等待連線",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            NativeLiveService.toggleCameraSharing();
                            refreshVoiceControls();
                        }
                    });

                    voiceScreenButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) {
                                Toast.makeText(
                                        context,
                                        "請先開始通話並等待連線",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            boolean sent = NativeLiveService.sendScreenSnapshot();
                            showCompactStatus(
                                    sent ? "已送出目前畫面" : "無法取得畫面",
                                    sent ? "Gemini 正在查看" : "請確認 Live 已連線");
                            refreshVoiceControls();
                        }
                    });

                    voiceMuteButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!NativeLiveService.isActive()) return;
                            if (NativeLiveService.isAiSpeaking()) {
                                NativeLiveService.interruptAiSpeech();
                            } else {
                                NativeLiveService.toggleAgentMute();
                            }
                            refreshVoiceControls();
                        }
                    });

                    voiceCallButton.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            toggleNativeLive();
                            refreshVoiceControls();
                        }
                    });

                    LinearLayout.LayoutParams cameraLp =
                            new LinearLayout.LayoutParams(0, dp(70), 1f);
                    LinearLayout.LayoutParams screenLp =
                            new LinearLayout.LayoutParams(0, dp(70), 1f);
                    LinearLayout.LayoutParams micLp =
                            new LinearLayout.LayoutParams(0, dp(70), 1f);
                    LinearLayout.LayoutParams callLp =
                            new LinearLayout.LayoutParams(0, dp(70), 1f);
                    cameraLp.setMargins(dp(3), 0, dp(3), 0);
                    screenLp.setMargins(dp(3), 0, dp(3), 0);
                    micLp.setMargins(dp(3), 0, dp(3), 0);
                    callLp.setMargins(dp(3), 0, dp(3), 0);

                    row.addView(
                            makeConsoleActionCell(voiceCameraButton, "相機"),
                            cameraLp);
                    row.addView(
                            makeConsoleActionCell(voiceScreenButton, "看畫面"),
                            screenLp);
                    row.addView(
                            makeConsoleActionCell(voiceMuteButton, "麥克風"),
                            micLp);
                    row.addView(
                            makeConsoleActionCell(voiceCallButton, "通話"),
                            callLp);
                    dock.addView(row);

                    // Contextual Stop appears only when there is actually
                    // something useful to stop.
                    voiceStopAgentButton = makeVoiceSettingButton();
                    voiceStopAgentButton.setText("■ 停止目前任務");
                    voiceStopAgentButton.setTextSize(11);
                    voiceStopAgentButton.setTextColor(
                            Color.parseColor("#FDA4AF"));
                    applyVoiceSettingStyle(
                            voiceStopAgentButton,
                            Color.parseColor("#3F1D25"),
                            Color.parseColor("#7F1D3A"),
                            Color.parseColor("#FDA4AF"));
                    voiceStopAgentButton.setVisibility(View.GONE);
                    voiceStopAgentButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    boolean handled = false;
                                    if (NativeLiveService.hasActiveAgentTask()) {
                                        handled = NativeLiveService.stopAgentTask();
                                        if (handled) {
                                            Toast.makeText(
                                                    context,
                                                    "Agent 任務已停止",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    } else if (NativeLiveService.isAiSpeaking()) {
                                        handled =
                                                NativeLiveService.interruptAiSpeech();
                                        if (handled) {
                                            Toast.makeText(
                                                    context,
                                                    "已停止 Gemini 回覆",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                    if (!handled) {
                                        Toast.makeText(
                                                context,
                                                "目前沒有可停止的任務",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                    refreshVoiceControls();
                                }
                            });
                    LinearLayout.LayoutParams stopLp =
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(38));
                    stopLp.setMargins(0, dp(3), 0, dp(3));
                    dock.addView(voiceStopAgentButton, stopLp);

                    // Latest one or two conversation turns. Hidden until there
                    // is real transcript content.
                    voiceTranscriptText = new TextView(context);
                    voiceTranscriptText.setTextSize(11.5f);
                    voiceTranscriptText.setTextColor(
                            Color.parseColor("#D4D4D8"));
                    voiceTranscriptText.setMaxLines(3);
                    voiceTranscriptText.setPadding(
                            dp(10), dp(8), dp(10), dp(8));
                    voiceTranscriptText.setVisibility(View.GONE);

                    GradientDrawable transcriptBg = new GradientDrawable();
                    transcriptBg.setColor(Color.parseColor("#CC2C2C2E"));
                    transcriptBg.setCornerRadius(dp(12));
                    transcriptBg.setStroke(
                            dp(1), Color.parseColor("#52525B"));
                    voiceTranscriptText.setBackground(transcriptBg);

                    LinearLayout.LayoutParams transcriptLp =
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
                    transcriptLp.setMargins(0, dp(5), 0, 0);
                    dock.addView(voiceTranscriptText, transcriptLp);

                    // Advanced controls stay behind the gear.
                    voiceSettingsPanel = new LinearLayout(context);
                    voiceSettingsPanel.setOrientation(LinearLayout.VERTICAL);
                    voiceSettingsPanel.setPadding(
                            dp(10), dp(9), dp(10), dp(10));
                    voiceSettingsPanel.setVisibility(View.GONE);

                    GradientDrawable settingsBg = new GradientDrawable();
                    settingsBg.setColor(Color.parseColor("#E62C2C2E"));
                    settingsBg.setCornerRadius(dp(14));
                    settingsBg.setStroke(
                            dp(1), Color.parseColor("#52525B"));
                    voiceSettingsPanel.setBackground(settingsBg);

                    TextView settingsTitle = new TextView(context);
                    settingsTitle.setText("進階設定");
                    settingsTitle.setTextSize(11);
                    settingsTitle.setTextColor(
                            Color.parseColor("#A1A1AA"));
                    settingsTitle.setPadding(
                            dp(2), 0, dp(2), dp(7));
                    voiceSettingsPanel.addView(settingsTitle);

                    LinearLayout modeRow = new LinearLayout(context);
                    modeRow.setOrientation(LinearLayout.HORIZONTAL);
                    modeRow.setGravity(Gravity.CENTER_VERTICAL);

                    voiceInterruptionButton = makeVoiceSettingButton();
                    voiceInterruptionButton.setTextSize(10);
                    voiceInterruptionButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    if (!NativeLiveService.isActive()) return;
                                    boolean enabled =
                                            NativeLiveService
                                                    .toggleVoiceInterruption();
                                    Toast.makeText(
                                            context,
                                            enabled
                                                    ? "已開啟自由說話打斷"
                                                    : "已開啟防插話模式",
                                            Toast.LENGTH_SHORT).show();
                                    refreshVoiceControls();
                                }
                            });
                    modeRow.addView(
                            voiceInterruptionButton,
                            new LinearLayout.LayoutParams(
                                    0, dp(40), 1f));

                    voiceWakeButton = new TextView(context);
                    voiceWakeButton.setTextSize(10);
                    voiceWakeButton.setGravity(Gravity.CENTER);
                    voiceWakeButton.setPadding(
                            dp(6), 0, dp(6), 0);
                    voiceWakeButton.setClickable(true);
                    updateWakeButtonUi(
                            voiceWakeButton,
                            isKeepAwakeActive());
                    voiceWakeButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    vibrateSuccess();
                                    boolean next =
                                            toggleKeepAwake(context);
                                    updateWakeButtonUi(
                                            voiceWakeButton,
                                            next);
                                }
                            });
                    LinearLayout.LayoutParams wakeLp =
                            new LinearLayout.LayoutParams(
                                    0, dp(40), 1f);
                    wakeLp.setMargins(dp(6), 0, 0, 0);
                    modeRow.addView(voiceWakeButton, wakeLp);
                    voiceSettingsPanel.addView(modeRow);

                    LinearLayout settingsRow = new LinearLayout(context);
                    settingsRow.setOrientation(LinearLayout.HORIZONTAL);
                    settingsRow.setGravity(Gravity.CENTER_VERTICAL);
                    settingsRow.setPadding(0, dp(6), 0, 0);

                    voiceSensitivityButton = makeVoiceSettingButton();
                    voicePresetButton = makeVoiceSettingButton();
                    voiceOutputButton = makeVoiceSettingButton();

                    voiceSensitivityButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    showVoiceSettingChoices("sensitivity");
                                }
                            });
                    voicePresetButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    showVoiceSettingChoices("personality");
                                }
                            });
                    voiceOutputButton.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    showVoiceSettingChoices("output");
                                }
                            });

                    settingsRow.addView(
                            voiceSensitivityButton,
                            new LinearLayout.LayoutParams(
                                    0, dp(40), 1f));
                    LinearLayout.LayoutParams presetLp =
                            new LinearLayout.LayoutParams(
                                    0, dp(40), 1f);
                    presetLp.setMargins(dp(5), 0, dp(5), 0);
                    settingsRow.addView(voicePresetButton, presetLp);
                    settingsRow.addView(
                            voiceOutputButton,
                            new LinearLayout.LayoutParams(
                                    0, dp(40), 1f));
                    voiceSettingsPanel.addView(settingsRow);

                    voiceSettingsChoices = new LinearLayout(context);
                    voiceSettingsChoices.setOrientation(
                            LinearLayout.HORIZONTAL);
                    voiceSettingsChoices.setGravity(Gravity.CENTER_VERTICAL);
                    voiceSettingsChoices.setPadding(0, dp(5), 0, 0);
                    voiceSettingsPanel.addView(voiceSettingsChoices);

                    // Teach Send is intentionally not a normal setting.
                    // Its lower-level runtime remains available for a targeted
                    // SEND_TARGET_NOT_FOUND recovery flow.

                    LinearLayout.LayoutParams settingsPanelLp =
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
                    settingsPanelLp.setMargins(0, dp(7), 0, 0);
                    dock.addView(
                            voiceSettingsPanel,
                            settingsPanelLp);

                    voiceControlView = dock;
                    voiceControlController = new FloatingPanelController(
                            context,
                            "voice_control_console",
                            windowManager,
                            dock,
                            voiceControlParams);
                    voiceControlController.restorePosition();
                    voiceControlController.attachDragHandle(voiceStatusText);

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
                voiceControlController = null;
                voiceCallButton = null;
                voiceCameraButton = null;
                voiceScreenButton = null;
                voiceMuteButton = null;
                voiceInterruptionButton = null;
                voiceWakeButton = null;
                voiceSensitivityButton = null;
                voicePresetButton = null;
                voiceOutputButton = null;
                voiceTeachSendButton = null;
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
                    if (latestLiveUiState != null
                            && latestLiveUiState.isError()) {
                        wakeBubbleFromDock();
                        bubbleView.setNativeVoiceState(3);
                    } else if (isAiSpeaking) {
                        wakeBubbleFromDock();
                        bubbleView.setNativeVoiceState(2);
                    } else if (isLiveActive) {
                        wakeBubbleFromDock();
                        bubbleView.setNativeVoiceState(1);
                    } else {
                        bubbleView.setNativeVoiceState(0);
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
                refreshBubbleActionStripIfShowing();
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
            voicePresetButton.setText("◌ 個性 ›");
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
            final String[] ids = {"brief", "work", "chat", "teacher"};
            final String[] labels = {"簡潔助手", "工作拍檔", "聊天型", "老師型"};
            for (int i = 0; i < ids.length; i++) {
                final int index = i;
                addVoiceChoice(labels[i], new Runnable() {
                    @Override public void run() {
                        AppConfig.applyPersonalityTemplate(context, ids[index]);
                        Toast.makeText(
                                context,
                                "個性將於下次通話套用",
                                Toast.LENGTH_SHORT).show();
                    }
                });
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
        // 0072: the old Crew Pocket server composer was removed.
        // Keep this entry point source-compatible and open the native Live console instead.
        showVoiceControls();
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
        // 0072 compatibility shim: external Crew Pocket server mode no longer exists.
        mainHandler.post(new Runnable() {
            @Override public void run() {
                setThinkingState(false);
                updateDialogStatus("伺服器模式已移除，請使用 Gemini Live");
                if (callback != null) callback.onResult(false, "SERVER_MODE_REMOVED");
            }
        });
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
        // 0072 compatibility shim: there is no external generation server to stop.
        setThinkingState(false);
        currentState = "IDLE";
        updateDialogStatus("已停止");
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

}
