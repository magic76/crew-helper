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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.List;

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
    private final Handler holdHandler;
    private final Vibrator vibrator;

    private FluidBubbleView bubbleView = null;
    private LinearLayout bubbleContainer = null;
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
    private View pendingChoiceView = null;
    private WindowManager.LayoutParams pendingChoiceParams = null;
    private Runnable pendingChoiceTimeout = null;
    private ScreenSelectionOverlay screenSelectionOverlay = null;
    private static final long MINI_STATUS_AUTO_HIDE_MS = 1800L;
    private static final int BUBBLE_SIZE_DP = 48;
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
        HandlerThread holdThread = new HandlerThread("crew-bubble-hold-timer");
        holdThread.start();
        this.holdHandler = new Handler(holdThread.getLooper());
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
     * 0094 "Point at this" UX.
     *
     * Long-pressing the bubble opens a full-screen selector. The selected
     * rectangle is only context. Runtime still owns all phone execution.
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

                                boolean accepted =
                                        NativeLiveService.submitSelectedRegion(
                                                context,
                                                selected);
                                if (!accepted) {
                                    showCompactStatus(
                                            "框選未送出",
                                            "請確認 Gemini API Key 與麥克風權限");
                                    return;
                                }

                                showCompactStatus(
                                        "正在讀取框選",
                                        "完成後直接說你想怎麼處理");
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

    public void showCompactStatus(final String title, final String detail) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!canDrawOverlays()) return;

                if (compactStatusAutoHideRunnable != null) {
                    mainHandler.removeCallbacks(compactStatusAutoHideRunnable);
                    compactStatusAutoHideRunnable = null;
                }

                String heading = title == null ? "" : title.trim();
                String body = detail == null ? "" : detail.trim();
                String message;
                if (heading.isEmpty()) {
                    message = body;
                } else if (body.isEmpty() || body.startsWith(heading)) {
                    message = body.isEmpty() ? heading : body;
                } else {
                    message = heading + " · " + body;
                }
                if (message.isEmpty()) return;

                if (compactStatusView != null) {
                    try { windowManager.removeViewImmediate(compactStatusView); }
                    catch (Exception ignored) {}
                    compactStatusView = null;
                }

                final TextView pill = new TextView(context);
                pill.setText(message);
                pill.setSingleLine(true);
                pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                pill.setTextSize(12.5f);
                pill.setTextColor(Color.parseColor("#F8FAFC"));
                pill.setPadding(dp(12), 0, dp(12), 0);

                String lower = message.toLowerCase();
                boolean error = lower.contains("失敗")
                        || lower.contains("錯誤")
                        || lower.contains("無法");
                boolean done = lower.contains("完成")
                        || lower.contains("已開")
                        || lower.contains("已送")
                        || lower.contains("找到");

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.argb(238, 58, 58, 60));
                bg.setCornerRadius(dp(18));
                bg.setStroke(
                        dp(1),
                        Color.parseColor(
                                error ? "#9F1239"
                                        : (done ? "#0F766E" : "#334155")));
                pill.setBackground(bg);
                pill.setElevation(dp(10));

                int screenW = windowManager.getDefaultDisplay().getWidth();
                int screenH = windowManager.getDefaultDisplay().getHeight();
                int pillWidth = Math.min(dp(196), screenW - dp(24));
                int pillHeight = dp(36);

                final WindowManager.LayoutParams lp =
                        new WindowManager.LayoutParams(
                                pillWidth,
                                pillHeight,
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
                            : bubbleParams.x - pillWidth - dp(8);
                    lp.x = Math.max(
                            dp(8),
                            Math.min(screenW - pillWidth - dp(8), targetX));

                    int targetY =
                            bubbleParams.y + (bubbleSize - pillHeight) / 2;
                    int top = getStatusBarHeight() + dp(4);
                    int bottom = screenH - pillHeight - dp(64);
                    lp.y = Math.max(top, Math.min(bottom, targetY));
                } else {
                    lp.x = dp(16);
                    lp.y = dp(96);
                }

                try {
                    pill.setAlpha(0f);
                    pill.setScaleX(0.96f);
                    pill.setScaleY(0.96f);
                    windowManager.addView(pill, lp);
                    compactStatusView = pill;
                    compactStatusParams = lp;
                    compactStatusController = null;
                    pill.animate()
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

                compactStatusAutoHideRunnable = new Runnable() {
                    @Override public void run() {
                        hideCompactStatus();
                    }
                };
                mainHandler.postDelayed(
                        compactStatusAutoHideRunnable,
                        MINI_STATUS_AUTO_HIDE_MS);
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
                        showCompactStatus(important, "");
                    }
                    return;
                }

                bubbleView.setAgentWorking(false);
                bubbleView.setAgentNeedsAttention(false);

                if (AgentInspectorStore.isSuccessfulTaskEnd(rawStatus)) {
                    // Silent success: a short green/white rim flash only.
                    bubbleView.flashAgentResult(true);
                    return;
                }

                if ("操作失敗".equals(important)) {
                    bubbleView.flashAgentResult(false);
                    showCompactStatus(important, "");
                    return;
                }

                if (important != null && !important.isEmpty()) {
                    showCompactStatus(important, "");
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

    interface PendingChoiceCallback { void onChoice(String elementId); void onCancel(); }

    /** Shows only when automation needs a human decision; it is not a permanent control. */
    public void showPendingChoices(final String title,
                                   final List<PendingUiChoice.Option> options,
                                   final PendingChoiceCallback callback) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                hidePendingChoicesInternal();
                if (!canDrawOverlays() || options == null || options.isEmpty()) return;
                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.argb(248, 15, 23, 42));
                bg.setCornerRadius(dp(20));
                bg.setStroke(dp(1), Color.parseColor("#334155"));
                card.setBackground(bg);
                card.setElevation(dp(14));

                TextView heading = new TextView(context);
                heading.setText(title == null ? "請選擇下一步" : title);
                heading.setTextColor(Color.WHITE);
                heading.setTextSize(14);
                heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                card.addView(heading);
                TextView hint = new TextView(context);
                hint.setText("可直接點選，或說「第一個／取消」");
                hint.setTextColor(Color.parseColor("#94A3B8"));
                hint.setTextSize(11);
                hint.setPadding(0, dp(3), 0, dp(7));
                card.addView(hint);
                for (int i = 0; i < options.size() && i < 4; i++) {
                    final PendingUiChoice.Option option = options.get(i);
                    TextView button = new TextView(context);
                    button.setText((i + 1) + ". " + option.label);
                    button.setTextColor(Color.parseColor("#E0F2FE"));
                    button.setTextSize(14);
                    button.setGravity(Gravity.CENTER_VERTICAL);
                    button.setMinHeight(dp(48));
                    button.setPadding(dp(12), 0, dp(12), 0);
                    GradientDrawable buttonBg = new GradientDrawable();
                    buttonBg.setColor(Color.parseColor("#172554"));
                    buttonBg.setCornerRadius(dp(12));
                    buttonBg.setStroke(dp(1), Color.parseColor("#1D4ED8"));
                    button.setBackground(buttonBg);
                    button.setContentDescription("選擇 " + (i + 1) + "：" + option.label);
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            hidePendingChoicesInternal();
                            if (callback != null) callback.onChoice(option.elementId);
                        }
                    });
                    LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
                    optionLp.setMargins(0, dp(3), 0, 0);
                    card.addView(button, optionLp);
                }
                TextView cancel = new TextView(context);
                cancel.setText("取消");
                cancel.setGravity(Gravity.CENTER);
                cancel.setTextColor(Color.parseColor("#CBD5E1"));
                cancel.setTextSize(12);
                cancel.setMinHeight(dp(40));
                cancel.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        hidePendingChoicesInternal();
                        if (callback != null) callback.onCancel();
                    }
                });
                card.addView(cancel);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        dp(296), WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = dp(16); lp.y = dp(92);
                try {
                    windowManager.addView(card, lp);
                    pendingChoiceView = card; pendingChoiceParams = lp;
                    pendingChoiceTimeout = new Runnable() {
                        @Override public void run() {
                            hidePendingChoicesInternal();
                            if (callback != null) callback.onCancel();
                        }
                    };
                    mainHandler.postDelayed(pendingChoiceTimeout, PendingUiChoice.TTL_MS);
                } catch (Exception ignored) { hidePendingChoicesInternal(); }
            }
        });
    }

    public void hidePendingChoices() { mainHandler.post(new Runnable() { @Override public void run() { hidePendingChoicesInternal(); } }); }

    private void hidePendingChoicesInternal() {
        if (pendingChoiceTimeout != null) mainHandler.removeCallbacks(pendingChoiceTimeout);
        pendingChoiceTimeout = null;
        View old = pendingChoiceView; pendingChoiceView = null; pendingChoiceParams = null;
        if (old != null) try { windowManager.removeViewImmediate(old); } catch (Exception ignored) {}
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
                        private long touchStartTime;
                        private volatile boolean moved = false;
                        private volatile boolean regionLongPressTriggered = false;
                        private Runnable regionLongPressRunnable = null;

                        private void cancelRegionLongPress() {
                            if (regionLongPressRunnable != null) {
                                holdHandler.removeCallbacks(regionLongPressRunnable);
                                regionLongPressRunnable = null;
                            }
                        }

                        private void armRegionLongPress() {
                            cancelRegionLongPress();
                            regionLongPressTriggered = false;
                            regionLongPressRunnable = new Runnable() {
                                @Override public void run() {
                                    regionLongPressRunnable = null;
                                    if (moved || regionLongPressTriggered) return;
                                    regionLongPressTriggered = true;
                                    vibrateShort();
                                    startRegionSelection();
                                }
                            };
                            holdHandler.postDelayed(
                                    regionLongPressRunnable,
                                    1500L);
                        }

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
                                    touchStartTime = SystemClock.elapsedRealtime();
                                    moved = false;
                                    regionLongPressTriggered = false;
                                    armRegionLongPress();
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
                                        cancelRegionLongPress();
                                        collapseBubbleActions(false);
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
                                    bubbleView.setAlpha(1.0f);
                                    isDocked = false;
                                    windowManager.updateViewLayout(
                                            bubbleContainer,
                                            bubbleParams);
                                    return true;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    cancelRegionLongPress();
                                    if (regionLongPressTriggered) {
                                        // The selector is already being opened. Do not
                                        // turn the release into a tap or move event.
                                        snapBubbleToEdge();
                                        scheduleAutoDock();
                                        return true;
                                    }
                                    if (!moved && !regionLongPressTriggered) {
                                        float dx = Math.abs(
                                                event.getRawX() - initialTouchX);
                                        float dy = Math.abs(
                                                event.getRawY() - initialTouchY);
                                        long duration =
                                                SystemClock.elapsedRealtime()
                                                        - touchStartTime;
                                        if (dx < dp(14)
                                                && dy < dp(14)
                                                && duration < 450) {
                                            vibrateShort();
                                            toggleBubbleActionStrip();
                                        }
                                    }
                                    // Normal path fires while the
                                    // finger is still down. This is only a
                                    // fallback for a briefly blocked main loop.
                                    if (!moved
                                            && !regionLongPressTriggered
                                            && System.currentTimeMillis()
                                                    - touchStartTime >= 1500L) {
                                        regionLongPressTriggered = true;
                                        vibrateShort();
                                        startRegionSelection();
                                    }
                                    regionLongPressTriggered = false;
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
        mainHandler.post(new Runnable() {
            @Override public void run() {
                nativeLiveRequested = active;
                latestLiveStatus = text == null || text.trim().isEmpty() ? (active ? "語音通話中" : "待命") : text.trim();
                if (bubbleView != null) {
                    bubbleView.setNativeVoiceState(
                            isLiveError(latestLiveStatus) ? 3 : (active ? 1 : 0));
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

    private boolean isLiveError(String status) {
        String lower = status == null ? "" : status.toLowerCase();
        return lower.contains("失敗") || lower.contains("錯誤") || lower.contains("未取得")
                || lower.contains("尚未設定") || lower.contains("無法");
    }

    private void updateVoiceTelemetryUi() {
        boolean liveRequested = nativeLiveRequested || NativeLiveService.isActive();
        boolean error = isLiveError(latestLiveStatus);
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
            String lower = latestLiveStatus == null
                    ? ""
                    : latestLiveStatus.toLowerCase();
            if (lower.contains("正在連線")
                    || lower.contains("連線中")
                    || lower.contains("connecting")) {
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
                    if (isLiveError(latestLiveStatus)) {
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

    // 🌊 Custom Fluid Bubble View (Exact Web UI Gradient Replica)
    public static class FluidBubbleView extends View {
        private Paint bgPaint;
        private Paint ringPaint;
        private Paint glowPaint;
        private Bitmap logoBitmap;
        private RectF ringBounds = new RectF();
        private SweepGradient idleSweepGradient;
        private SweepGradient activeSweepGradient;
        private SweepGradient speakingSweepGradient;
        private SweepGradient errorSweepGradient;
        private SweepGradient attentionSweepGradient;
        private SweepGradient rainbowSweepGradient;
        private Matrix matrix = new Matrix();
        private float rotationAngle = 0f;
        private boolean isFlowing = false;
        private boolean isSuccessFlash = false;

        // 0090: explicit Agent state, independent of Gemini Live state.
        private boolean agentWorking = false;
        private boolean agentNeedsAttention = false;
        // 0 none, 1 success, 2 failure
        private int agentResultFlash = 0;
        private int agentResultFlashGeneration = 0;

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
            logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.crew_assistant_bubble);

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

            int[] attentionColors = new int[]{
                Color.parseColor("#F59E0B"), Color.parseColor("#FCD34D"),
                Color.parseColor("#FBBF24"), Color.parseColor("#F59E0B")
            };
            attentionSweepGradient =
                    new SweepGradient(cx, cy, attentionColors, null);

            int[] rainbowColors = new int[]{
                Color.parseColor("#38BDF8"),
                Color.parseColor("#818CF8"),
                Color.parseColor("#C084FC"),
                Color.parseColor("#F43F5E"),
                Color.parseColor("#38BDF8")
            };
            rainbowSweepGradient = new SweepGradient(cx, cy, rainbowColors, null);
        }

        private void updateRotationSpeed() {
            if (continuousRotator == null) return;

            long duration;
            if (agentWorking) {
                duration = 850L;
            } else if (isFlowing) {
                duration = 1200L;
            } else if (nativeVoiceState == 2) {
                duration = 1500L;
            } else if (nativeVoiceState == 1) {
                duration = 2500L;
            } else {
                duration = 4000L;
            }
            continuousRotator.setDuration(duration);
        }

        public void startWaterFlow() {
            isFlowing = true;
            isSuccessFlash = false;
            updateRotationSpeed();
            invalidate();
        }

        public void stopWaterFlow() {
            isFlowing = false;
            updateRotationSpeed();
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

        public void setAgentWorking(boolean working) {
            if (agentWorking == working) return;
            agentWorking = working;
            if (working) {
                agentNeedsAttention = false;
                agentResultFlash = 0;
                agentResultFlashGeneration++;
            }
            updateRotationSpeed();
            invalidate();
        }

        public void setAgentNeedsAttention(boolean needsAttention) {
            if (agentNeedsAttention == needsAttention) return;
            agentNeedsAttention = needsAttention;
            if (needsAttention) {
                agentWorking = false;
                agentResultFlash = 0;
                agentResultFlashGeneration++;
            }
            updateRotationSpeed();
            invalidate();
        }

        public void flashAgentResult(final boolean success) {
            agentWorking = false;
            agentNeedsAttention = false;
            updateRotationSpeed();

            final int generation = ++agentResultFlashGeneration;
            agentResultFlash = success ? 1 : 2;
            invalidate();

            postDelayed(new Runnable() {
                @Override public void run() {
                    if (generation != agentResultFlashGeneration) return;
                    agentResultFlash = 0;
                    invalidate();
                }
            }, success ? 650L : 950L);
        }

        public void setNativeVoiceState(int state) {
            this.nativeVoiceState = state;
            updateRotationSpeed();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float radius = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - 1.5f);

            // 0072 brand core: selected Crew assistant logo. The source PNG has
            // transparent corners so the overlay remains a true circular bubble.
            RectF logoBounds = new RectF(0f, 0f, getWidth(), getHeight());
            bgPaint.setShader(null);
            bgPaint.setAlpha(255);
            if (logoBitmap != null && !logoBitmap.isRecycled()) {
                canvas.drawBitmap(logoBitmap, null, logoBounds, bgPaint);
            } else {
                bgPaint.setColor(Color.parseColor("#071426"));
                canvas.drawCircle(cx, cy, radius, bgPaint);
            }

            // Keep the previous runtime-state language as a thin animated rim:
            // neutral=idle, blue=listening, purple=speaking, red=error,
            // rainbow=tool execution. The logo itself never changes identity.
            matrix.setRotate(rotationAngle, cx, cy);
            SweepGradient rimGradient = nativeVoiceState == 3
                    ? errorSweepGradient
                    : agentNeedsAttention
                    ? attentionSweepGradient
                    : nativeVoiceState == 2
                    ? speakingSweepGradient
                    : nativeVoiceState == 1
                    ? activeSweepGradient
                    : isFlowing
                    ? rainbowSweepGradient
                    : idleSweepGradient;
            if (rimGradient != null) {
                rimGradient.setLocalMatrix(matrix);
                ringPaint.setShader(rimGradient);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeCap(Paint.Cap.ROUND);
                ringPaint.setStrokeWidth(Math.max(2f, radius * 0.075f));
                ringPaint.setAlpha(
                        agentWorking
                                ? 78
                                : (nativeVoiceState == 0
                                        && !isFlowing
                                        && !agentNeedsAttention
                                        ? 90 : 225));
                RectF stateRing = new RectF(
                        ringPaint.getStrokeWidth() / 2f,
                        ringPaint.getStrokeWidth() / 2f,
                        getWidth() - ringPaint.getStrokeWidth() / 2f,
                        getHeight() - ringPaint.getStrokeWidth() / 2f);
                canvas.drawOval(stateRing, ringPaint);
                ringPaint.setShader(null);
            }

            if (agentWorking) {
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setShader(null);
                ringPaint.setStrokeCap(Paint.Cap.ROUND);
                ringPaint.setStrokeWidth(Math.max(2.5f, radius * 0.095f));
                ringPaint.setColor(Color.parseColor("#22D3EE"));
                ringPaint.setAlpha(255);

                float inset = ringPaint.getStrokeWidth() / 2f;
                RectF agentSpinnerRing = new RectF(
                        inset,
                        inset,
                        getWidth() - inset,
                        getHeight() - inset);
                canvas.drawArc(
                        agentSpinnerRing,
                        rotationAngle - 90f,
                        92f,
                        false,
                        ringPaint);
            }

            if (agentResultFlash != 0) {
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setShader(null);
                ringPaint.setStrokeCap(Paint.Cap.ROUND);
                ringPaint.setStrokeWidth(Math.max(2.5f, radius * 0.085f));
                ringPaint.setColor(
                        agentResultFlash == 1
                                ? Color.parseColor("#34D399")
                                : Color.parseColor("#FB7185"));
                ringPaint.setAlpha(245);

                float resultInset = ringPaint.getStrokeWidth();
                RectF resultRing = new RectF(
                        resultInset,
                        resultInset,
                        getWidth() - resultInset,
                        getHeight() - resultInset);
                canvas.drawOval(resultRing, ringPaint);
            }

            if (isSuccessFlash) {
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setShader(null);
                ringPaint.setStrokeWidth(Math.max(2f, radius * 0.055f));
                ringPaint.setColor(Color.WHITE);
                ringPaint.setAlpha(210);
                RectF successRing = new RectF(
                        ringPaint.getStrokeWidth(), ringPaint.getStrokeWidth(),
                        getWidth() - ringPaint.getStrokeWidth(),
                        getHeight() - ringPaint.getStrokeWidth());
                canvas.drawOval(successRing, ringPaint);
            }
        }
    }
}
