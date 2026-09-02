package com.crewpocket.helper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Modern Native Gemini Live Verification Screen
 * Cyberpunk Dark Luxury Style
 */
public class NativeLiveActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 301;
    private EditText apiKeyInput;
    private EditText textInput;
    private TextView statusDot;
    private TextView statusText;
    private TextView transcript;
    private String lastTranscriptRole = "";
    private Button callButton;
    private Button textSendButton;
    private Button interruptionButton;
    private Button awakeButton;
    private Button cameraButton;
    private Button screenButton;
    private Button micButton;
    private Button noiseButton;
    private SeekBar noiseSlider;
    private TextView noiseLevelText;
    private TextView microphoneMeterText;
    private Button diagnosticButton;
    private TextView diagnosticText;
    private NativeGeminiLiveClient client;
    private double lastMicDbfs = -96d;
    private boolean observedMicSend = false;
    private long lastMicMeterAt = 0L;
    private boolean callRequested = false;
    private int reconnectAttempts = 0;
    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!callRequested) return;
            startClient(AppConfig.getGeminiApiKey(NativeLiveActivity.this));
        }
    };
    private final Handler handler = new Handler();
    private final Runnable connectionWatchdog = new Runnable() {
        @Override public void run() {
            if (client != null && client.isRunning()) {
                updateStatus(CrewTheme.AMBER_400, "連線診斷：目前停在「" + client.getStage() + "」");
            }
        }
    };

    private int dp(float val) {
        return CrewTheme.dp(this, val);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        // 🌌 Immersive Dark Bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }
        getWindow().getDecorView().setBackgroundColor(CrewTheme.BG_PRIMARY);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(32));
        root.setBackgroundColor(CrewTheme.BG_PRIMARY);
        scroll.addView(root);

        // ── 1. Header with Back Navigation ──
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = new TextView(this);
        backBtn.setText(I18n.get(this, "‹ 返回", "‹ Back"));
        backBtn.setTextSize(14);
        backBtn.setTextColor(CrewTheme.INDIGO_400);
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        backBtn.setPadding(0, 0, dp(12), 0);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        headerRow.addView(backBtn);

        TextView title = new TextView(this);
        title.setText(I18n.get(this, "原生 Gemini Live", "Native Gemini Live"));
        title.setTextSize(18);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(title);

        root.addView(headerRow);

        TextView note = new TextView(this);
        note.setText(I18n.get(this, "端到端低延遲 Web Audio PCM 直連通話（無須開啟瀏覽器）", "End-to-end low-latency direct voice chat (No browser needed)"));
        note.setTextSize(11);
        note.setTextColor(CrewTheme.TEXT_SECONDARY);
        note.setPadding(0, dp(4), 0, dp(18));
        root.addView(note);

        // ── 2. Status Badge Card ──
        LinearLayout statusBadge = new LinearLayout(this);
        statusBadge.setOrientation(LinearLayout.HORIZONTAL);
        statusBadge.setGravity(Gravity.CENTER_VERTICAL);
        statusBadge.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusBadge.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(12);
        statusDot.setTextColor(CrewTheme.EMERALD_400);
        statusDot.setPadding(0, 0, dp(8), 0);
        statusBadge.addView(statusDot);

        statusText = new TextView(this);
        statusText.setText(I18n.get(this, "待命就緒", "Ready"));
        statusText.setTextSize(12);
        statusText.setTextColor(CrewTheme.TEXT_PRIMARY);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusBadge.addView(statusText);

        root.addView(statusBadge);

        // ── 2b. Voice health check ──
        LinearLayout diagnosticCard = new LinearLayout(this);
        diagnosticCard.setOrientation(LinearLayout.VERTICAL);
        diagnosticCard.setPadding(dp(14), dp(10), dp(14), dp(12));
        diagnosticCard.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));
        LinearLayout.LayoutParams diagnosticLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        diagnosticLp.setMargins(0, dp(10), 0, 0);
        diagnosticCard.setLayoutParams(diagnosticLp);
        diagnosticButton = new Button(this);
        diagnosticButton.setText(I18n.get(this, "🧪 執行語音自檢", "🧪 Run voice check"));
        diagnosticButton.setTextSize(12);
        diagnosticButton.setTextColor(CrewTheme.TEXT_PRIMARY);
        diagnosticButton.setBackground(CrewTheme.createCard(this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_INDIGO, 10));
        diagnosticCard.addView(diagnosticButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        diagnosticText = new TextView(this);
        diagnosticText.setText(I18n.get(this, "檢查麥克風、網路、Gemini 與音訊送出。", "Checks mic, network, Gemini, and audio sending."));
        diagnosticText.setTextSize(10);
        diagnosticText.setTextColor(CrewTheme.TEXT_MUTED);
        diagnosticText.setPadding(dp(4), dp(7), dp(4), 0);
        diagnosticCard.addView(diagnosticText);
        root.addView(diagnosticCard);

        // ── 3. API Key Card ──
        LinearLayout keyCard = new LinearLayout(this);
        keyCard.setOrientation(LinearLayout.VERTICAL);
        keyCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams keyCardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyCardLp.setMargins(0, dp(14), 0, 0);
        keyCard.setLayoutParams(keyCardLp);
        keyCard.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 16));

        TextView keyLabel = new TextView(this);
        keyLabel.setText("Google AI Studio API Key");
        keyLabel.setTextSize(11);
        keyLabel.setTextColor(CrewTheme.TEAL_300);
        keyLabel.setTypeface(Typeface.DEFAULT_BOLD);
        keyCard.addView(keyLabel);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("AIzaSy...");
        apiKeyInput.setHintTextColor(CrewTheme.TEXT_MUTED);
        apiKeyInput.setTextColor(CrewTheme.TEXT_PRIMARY);
        apiKeyInput.setTextSize(12);
        apiKeyInput.setTypeface(Typeface.MONOSPACE);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setBackground(CrewTheme.createCard(this, CrewTheme.BG_PRIMARY, CrewTheme.BORDER_SUBTLE, 10));
        apiKeyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, dp(8), 0, 0);
        keyCard.addView(apiKeyInput, inputLp);

        String savedKey = AppConfig.getGeminiApiKey(this);
        apiKeyInput.setText(savedKey);

        TextView keyHint = new TextView(this);
        keyHint.setText(I18n.get(this, "🔗 免費申請 Gemini API Key (aistudio.google.com) ↗", "🔗 Get Free Gemini API Key (aistudio.google.com) ↗"));
        keyHint.setTextSize(11);
        keyHint.setTextColor(CrewTheme.CYAN_400);
        keyHint.setPadding(0, dp(6), 0, 0);
        keyHint.setClickable(true);
        keyHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")));
                } catch (Exception ignored) {}
            }
        });
        keyCard.addView(keyHint);

        root.addView(keyCard);

        // ── 4. Main Call Action Button ──
        callButton = new Button(this);
        callButton.setText(I18n.get(this, "🎙️ 開始原生 Live 通話", "🎙️ Start Native Live Call"));
        callButton.setTextSize(14);
        callButton.setTextColor(Color.WHITE);
        callButton.setTypeface(Typeface.DEFAULT_BOLD);
        updateCallButtonUi(false);

        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        buttonLp.setMargins(0, dp(14), 0, 0);
        root.addView(callButton, buttonLp);

        // ── 5. Shared Assistant Control Dock ──
        // Mirrors the expanded floating assistant so both entry points expose the same actions.
        LinearLayout controlCard = new LinearLayout(this);
        controlCard.setOrientation(LinearLayout.VERTICAL);
        controlCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        controlCard.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_INDIGO, 16));
        LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlLp.setMargins(0, dp(12), 0, 0);
        controlCard.setLayoutParams(controlLp);

        LinearLayout controlHeader = new LinearLayout(this);
        controlHeader.setOrientation(LinearLayout.HORIZONTAL);
        controlHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView controlTitle = new TextView(this);
        controlTitle.setText(I18n.get(this, "助理控制", "Assistant Controls"));
        controlTitle.setTextSize(12);
        controlTitle.setTypeface(Typeface.DEFAULT_BOLD);
        controlTitle.setTextColor(CrewTheme.TEAL_300);
        controlHeader.addView(controlTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        interruptionButton = makeControlButton();
        awakeButton = makeControlButton();
        noiseButton = makeControlButton();
        controlHeader.addView(interruptionButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        LinearLayout.LayoutParams awakeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        awakeLp.setMargins(dp(6), 0, 0, 0);
        controlHeader.addView(awakeButton, awakeLp);
        LinearLayout.LayoutParams noiseLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        noiseLp.setMargins(dp(6), 0, 0, 0);
        controlHeader.addView(noiseButton, noiseLp);
        controlCard.addView(controlHeader);

        LinearLayout noiseRow = new LinearLayout(this);
        noiseRow.setOrientation(LinearLayout.HORIZONTAL);
        noiseRow.setGravity(Gravity.CENTER_VERTICAL);
        noiseRow.setPadding(0, dp(8), 0, 0);
        TextView noiseLabel = new TextView(this);
        noiseLabel.setText(I18n.get(this, "插話門檻", "Interrupt gate"));
        noiseLabel.setTextSize(10);
        noiseLabel.setTextColor(CrewTheme.TEXT_SECONDARY);
        noiseRow.addView(noiseLabel, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
        noiseSlider = new SeekBar(this);
        noiseSlider.setMax(100);
        noiseSlider.setProgress(AppConfig.getNoiseSuppression(this));
        noiseSlider.setContentDescription(I18n.get(this, "插話門檻：左側較容易觸發插話，右側較不易被環境聲打斷", "Interrupt gate: left is more sensitive, right rejects ambient interruptions"));
        noiseRow.addView(noiseSlider, new LinearLayout.LayoutParams(0, dp(36), 1f));
        noiseLevelText = new TextView(this);
        noiseLevelText.setTextSize(10);
        noiseLevelText.setTextColor(CrewTheme.TEAL_300);
        noiseLevelText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        noiseRow.addView(noiseLevelText, new LinearLayout.LayoutParams(dp(34), dp(36)));
        controlCard.addView(noiseRow);

        microphoneMeterText = new TextView(this);
        microphoneMeterText.setText(I18n.get(this, "收音：等待通話開始", "Mic: waiting for call"));
        microphoneMeterText.setTextSize(10);
        microphoneMeterText.setTextColor(CrewTheme.TEXT_MUTED);
        microphoneMeterText.setPadding(0, dp(2), 0, 0);
        controlCard.addView(microphoneMeterText);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(10), 0, 0);
        cameraButton = makeControlButton();
        screenButton = makeControlButton();
        micButton = makeControlButton();
        actionRow.addView(cameraButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams screenLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        screenLp.setMargins(dp(6), 0, dp(6), 0);
        actionRow.addView(screenButton, screenLp);
        actionRow.addView(micButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        controlCard.addView(actionRow);
        root.addView(controlCard);

        // ── 6. Text Input Card ──
        LinearLayout textCard = new LinearLayout(this);
        textCard.setOrientation(LinearLayout.VERTICAL);
        textCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams textCardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textCardLp.setMargins(0, dp(14), 0, 0);
        textCard.setLayoutParams(textCardLp);
        textCard.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 16));

        textInput = new EditText(this);
        textInput.setHint(I18n.get(this, "文字輸入測試（例如：「看我現在螢幕上有什麼？」）", "Type text command (e.g., 'What is on my screen?')"));
        textInput.setHintTextColor(CrewTheme.TEXT_MUTED);
        textInput.setTextColor(CrewTheme.TEXT_PRIMARY);
        textInput.setTextSize(12);
        textInput.setMinLines(2);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setBackground(CrewTheme.createCard(this, CrewTheme.BG_PRIMARY, CrewTheme.BORDER_SUBTLE, 10));
        textInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        textCard.addView(textInput);

        textSendButton = new Button(this);
        textSendButton.setText(I18n.get(this, "💬 傳送文字至 Live 通話", "💬 Send Text to Live Session"));
        textSendButton.setTextSize(12);
        textSendButton.setTextColor(CrewTheme.TEXT_PRIMARY);
        textSendButton.setBackground(CrewTheme.createCard(this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_INDIGO, 10));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        sendLp.setMargins(0, dp(8), 0, 0);
        textCard.addView(textSendButton, sendLp);

        root.addView(textCard);

        // ── 7. Transcript Area ──
        TextView transcriptTitle = new TextView(this);
        transcriptTitle.setText(I18n.get(this, "即時逐字稿", "Live Transcript"));
        transcriptTitle.setTextSize(12);
        transcriptTitle.setTypeface(Typeface.DEFAULT_BOLD);
        transcriptTitle.setTextColor(CrewTheme.TEAL_300);
        transcriptTitle.setPadding(dp(4), dp(18), 0, dp(6));
        root.addView(transcriptTitle);

        transcript = new TextView(this);
        transcript.setText(I18n.get(this, "（通話中的語音辨識與 Gemini 即時回覆將動態顯示在這裡）", "(Voice recognition and Gemini realtime replies will appear here)"));
        transcript.setTextSize(12);
        transcript.setTextColor(CrewTheme.TEXT_SECONDARY);
        transcript.setTypeface(Typeface.MONOSPACE);
        transcript.setPadding(dp(14), dp(12), dp(14), dp(12));
        transcript.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 14));
        transcript.setMinLines(5);
        LinearLayout.LayoutParams transcriptLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(transcript, transcriptLp);

        setContentView(scroll);

        // Event Listeners
        callButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCall(); }
        });
        diagnosticButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runVoiceDiagnostic(); }
        });
        textSendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = textInput.getText().toString().trim();
                if (text.isEmpty()) { updateStatus(CrewTheme.AMBER_400, I18n.get(NativeLiveActivity.this, "請先輸入測試文字", "Please enter text first")); return; }
                if (client == null || !client.isRunning()) { updateStatus(CrewTheme.AMBER_400, I18n.get(NativeLiveActivity.this, "請先開始 Gemini Live 通話", "Please start Live Call first")); return; }
                if (client.sendText(text)) {
                    textInput.setText("");
                    updateStatus(CrewTheme.TEAL_400, I18n.get(NativeLiveActivity.this, "文字已送出，等待回覆…", "Text sent, waiting for reply..."));
                } else updateStatus(CrewTheme.ROSE_500, I18n.get(NativeLiveActivity.this, "文字送出失敗，請確認連線", "Failed to send text"));
            }
        });
        interruptionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isCallActive()) return;
                client.setAllowVoiceInterruption(!client.isVoiceInterruptionAllowed());
                refreshAssistantControls();
            }
        });
        awakeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FloatingBubbleManager.toggleKeepAwake(NativeLiveActivity.this);
                refreshAssistantControls();
            }
        });
        noiseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String current = AppConfig.getNoiseMode(NativeLiveActivity.this);
                String next = "auto".equals(current) ? "noisy" : ("noisy".equals(current) ? "quiet" : "auto");
                AppConfig.setNoiseMode(NativeLiveActivity.this, next);
                if (client != null) client.setNoiseMode(next);
                refreshAssistantControls();
            }
        });
        noiseSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                AppConfig.setNoiseSuppression(NativeLiveActivity.this, progress);
                if (client != null) client.setNoiseSuppression(progress);
                updateNoiseLevel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                Toast.makeText(NativeLiveActivity.this, I18n.get(NativeLiveActivity.this, "插話門檻已調整", "Interrupt gate adjusted"), Toast.LENGTH_SHORT).show();
            }
        });
        micButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isCallActive()) return;
                boolean muted = client.toggleAgentMute();
                Toast.makeText(NativeLiveActivity.this, muted ? I18n.get(NativeLiveActivity.this, "麥克風已靜音", "Microphone muted") : I18n.get(NativeLiveActivity.this, "聆聽中", "Listening"), Toast.LENGTH_SHORT).show();
                refreshAssistantControls();
            }
        });
        screenButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isCallActive()) return;
                client.sendScreenFrame();
                updateStatus(CrewTheme.CYAN_400, I18n.get(NativeLiveActivity.this, "正在擷取並傳送螢幕…", "Capturing and sending screen..."));
            }
        });
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { captureAndSendCamera(); }
        });
        refreshAssistantControls();
    }

    private Button makeControlButton() {
        Button button = new Button(this);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setTextColor(CrewTheme.TEXT_PRIMARY);
        button.setPadding(dp(5), 0, dp(5), 0);
        return button;
    }

    private boolean isCallActive() {
        if (client != null && client.isRunning()) return true;
        updateStatus(CrewTheme.AMBER_400, I18n.get(this, "請先開始 Live 通話", "Please start Live Call first"));
        return false;
    }

    private void refreshAssistantControls() {
        if (interruptionButton == null) return;
        boolean active = client != null && client.isRunning();
        boolean allowInterruption = active && client.isVoiceInterruptionAllowed();
        interruptionButton.setText(allowInterruption ? I18n.get(this, "🎙️ 可插話", "🎙️ Interrupt") : I18n.get(this, "🛡️ 防插話", "🛡️ Protected"));
        interruptionButton.setBackground(CrewTheme.createCard(this, allowInterruption ? Color.parseColor("#064E3B") : Color.parseColor("#78350F"), allowInterruption ? CrewTheme.EMERALD_500 : CrewTheme.AMBER_500, 10));
        boolean awake = FloatingBubbleManager.isKeepAwakeActive();
        awakeButton.setText(awake ? I18n.get(this, "☀️ 常亮", "☀️ Awake") : I18n.get(this, "☾ 休眠", "☾ Sleep"));
        awakeButton.setBackground(CrewTheme.createCard(this, awake ? Color.parseColor("#422006") : CrewTheme.BG_ELEVATED, awake ? CrewTheme.AMBER_500 : CrewTheme.BORDER_SUBTLE, 10));
        String noise = client != null ? client.getNoiseMode() : AppConfig.getNoiseMode(this);
        noiseButton.setText("noisy".equals(noise) ? I18n.get(this, "🛡️ 嘈雜", "🛡️ Noisy") : ("quiet".equals(noise) ? I18n.get(this, "🌙 安靜", "🌙 Quiet") : I18n.get(this, "✦ 自動", "✦ Auto")));
        noiseButton.setBackground(CrewTheme.createCard(this, "noisy".equals(noise) ? Color.parseColor("#78350F") : CrewTheme.BG_ELEVATED, "noisy".equals(noise) ? CrewTheme.AMBER_500 : CrewTheme.BORDER_SUBTLE, 10));
        int suppression = client != null ? client.getNoiseSuppression() : AppConfig.getNoiseSuppression(this);
        if (noiseSlider != null && noiseSlider.getProgress() != suppression) noiseSlider.setProgress(suppression);
        updateNoiseLevel(suppression);
        cameraButton.setText(I18n.get(this, "📷 拍照", "📷 Camera"));
        screenButton.setText(I18n.get(this, "▣ 看螢幕", "▣ Screen"));
        boolean muted = active && client.isAgentMuted();
        micButton.setText(muted ? I18n.get(this, "🔇 已靜音", "🔇 Muted") : I18n.get(this, "🎙️ 聆聽", "🎙️ Listen"));
        cameraButton.setEnabled(active);
        screenButton.setEnabled(active);
        micButton.setEnabled(active);
        int inactive = Color.parseColor("#475569");
        cameraButton.setTextColor(active ? CrewTheme.TEXT_PRIMARY : inactive);
        screenButton.setTextColor(active ? CrewTheme.TEXT_PRIMARY : inactive);
        micButton.setTextColor(active ? CrewTheme.TEXT_PRIMARY : inactive);
        cameraButton.setBackground(CrewTheme.createCard(this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_SUBTLE, 12));
        screenButton.setBackground(CrewTheme.createCard(this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_SUBTLE, 12));
        micButton.setBackground(CrewTheme.createCard(this, muted ? Color.parseColor("#881337") : CrewTheme.TEAL_500, muted ? CrewTheme.ROSE_500 : CrewTheme.TEAL_400, 12));
    }

    private void updateNoiseLevel(int value) {
        if (noiseLevelText == null) return;
        String label = value < 35 ? I18n.get(this, "低", "Low") : (value < 70 ? I18n.get(this, "中", "Med") : I18n.get(this, "高", "High"));
        noiseLevelText.setText(label);
    }

    private void updateMicrophoneMeter(double dbfs, double gateDbfs, boolean sending) {
        lastMicDbfs = dbfs;
        lastMicMeterAt = System.currentTimeMillis();
        if (sending) observedMicSend = true;
        if (microphoneMeterText == null) return;
        String db = String.format(java.util.Locale.US, "%.0f", dbfs);
        String gate = String.format(java.util.Locale.US, "%.0f", gateDbfs);
        microphoneMeterText.setText(I18n.get(this, "收音 ", "Mic ") + db + " dB · "
            + I18n.get(this, "門檻 ", "Gate ") + gate + " dB · "
            + (sending ? I18n.get(this, "送出中", "Sending") : I18n.get(this, "已過濾", "Filtered")));
        microphoneMeterText.setTextColor(sending ? CrewTheme.TEAL_300 : CrewTheme.TEXT_MUTED);
    }

    private boolean hasNetworkConnection() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception ignored) { return false; }
    }

    private void runVoiceDiagnostic() {
        boolean permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean network = hasNetworkConnection();
        boolean liveReady = client != null && client.isRunning() && client.isSetupReady();
        if (!permission || !network || !liveReady) {
            String result = (permission ? "✓ 麥克風權限" : "✕ 麥克風權限") + "\n"
                    + (network ? "✓ 網路連線" : "✕ 網路連線") + "\n"
                    + (liveReady ? "✓ Gemini 已就緒" : "✕ Gemini 尚未就緒：請先開始 Live 通話");
            diagnosticText.setText(result);
            diagnosticText.setTextColor((!permission || !network) ? CrewTheme.ROSE_400 : CrewTheme.AMBER_400);
            return;
        }
        observedMicSend = false;
        final long startedAt = System.currentTimeMillis();
        diagnosticButton.setEnabled(false);
        diagnosticButton.setText(I18n.get(this, "請說一句話…（4 秒）", "Say a short phrase… (4 sec)"));
        diagnosticText.setText(I18n.get(this, "正在確認實際收音與 PCM 是否送出。", "Checking live microphone capture and PCM sending."));
        diagnosticText.setTextColor(CrewTheme.CYAN_400);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                boolean micSeen = lastMicMeterAt >= startedAt && lastMicDbfs > -90d;
                boolean sent = observedMicSend;
                boolean stillReady = client != null && client.isRunning() && client.isSetupReady();
                String result = "✓ 麥克風權限\n✓ 網路連線\n"
                        + (stillReady ? "✓ Gemini 已連線" : "✕ Gemini 連線已中斷") + "\n"
                        + (micSeen ? "✓ 收音 " + Math.round(lastMicDbfs) + " dB" : "✕ 沒有收到麥克風音量") + "\n"
                        + (sent ? "✓ PCM 音訊正在送出" : "✕ 尚未偵測到音訊送出");
                diagnosticText.setText(result);
                diagnosticText.setTextColor(stillReady && micSeen && sent ? CrewTheme.EMERALD_400 : CrewTheme.ROSE_400);
                diagnosticButton.setEnabled(true);
                diagnosticButton.setText(I18n.get(NativeLiveActivity.this, "🧪 再次執行自檢", "🧪 Run again"));
            }
        }, 4000);
    }

    private void captureAndSendCamera() {
        if (!isCallActive()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 102);
            return;
        }
        updateStatus(CrewTheme.CYAN_400, I18n.get(this, "正在拍照並傳送…", "Capturing and sending photo..."));
        CameraCaptureManager.capturePhoto(this, false, new CameraCaptureManager.CaptureCallback() {
            @Override public void onSuccess(String filePath) {
                if (client != null && client.isRunning()) client.sendCameraFrame(filePath);
                updateStatus(CrewTheme.TEAL_400, I18n.get(NativeLiveActivity.this, "相片已傳送給 Gemini", "Photo sent to Gemini"));
            }
            @Override public void onError(String error) {
                updateStatus(CrewTheme.ROSE_500, I18n.get(NativeLiveActivity.this, "相機失敗：", "Camera failed: ") + error);
            }
        });
    }

    private void updateCallButtonUi(boolean isCallActive) {
        if (isCallActive) {
            callButton.setText(I18n.get(this, "🛑 結束 Live 通話", "🛑 End Live Call"));
            callButton.setBackground(CrewTheme.createGradientButton(this, CrewTheme.ROSE_500, Color.parseColor("#9F1239"), 14));
        } else {
            callButton.setText(I18n.get(this, "🎙️ 開始原生 Live 通話", "🎙️ Start Native Live Call"));
            callButton.setBackground(CrewTheme.createGradientButton(this, CrewTheme.TEAL_500, CrewTheme.INDIGO_600, 14));
        }
    }

    private void updateStatus(final int color, final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (statusDot != null) statusDot.setTextColor(color);
                if (statusText != null) statusText.setText(text);
            }
        });
    }

    private void toggleCall() {
        if (callRequested || (client != null && client.isRunning())) {
            callRequested = false;
            if (client != null) client.stop();
            client = null;
            handler.removeCallbacks(connectionWatchdog);
            handler.removeCallbacks(reconnectRunnable);
            updateCallButtonUi(false);
            updateStatus(CrewTheme.TEXT_MUTED, "通話已結束");
            refreshAssistantControls();
            return;
        }
        final String key = apiKeyInput.getText().toString().trim();
        if (key.length() < 20) { updateStatus(CrewTheme.ROSE_500, "請填入有效的 Gemini API Key"); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        AppConfig.setGeminiApiKey(this, key);
        callRequested = true;
        reconnectAttempts = 0;
        startClient(key);
    }

    private void startClient(String key) {
        String serverUrl = AppConfig.getServerUrl(this);
        String voiceName = AppConfig.getVoiceName(this);
        client = new NativeGeminiLiveClient(key, serverUrl, voiceName, AppConfig.getNoiseMode(this), AppConfig.getNoiseSuppression(this), new NativeGeminiLiveClient.Listener() {
            @Override public void onStatus(final String text) {
                if (text != null && text.contains("已連線")) reconnectAttempts = 0;
                updateStatus(CrewTheme.TEAL_400, text);
            }
            @Override public void onStopped(final String reason) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!callRequested) {
                            updateStatus(CrewTheme.TEXT_MUTED, reason);
                            handler.removeCallbacks(connectionWatchdog);
                            updateCallButtonUi(false);
                            refreshAssistantControls();
                        } else if (reconnectAttempts < 3) {
                            reconnectAttempts++;
                            client = null;
                            updateStatus(CrewTheme.AMBER_400, "連線中斷，正在重新連線（" + reconnectAttempts + "/3）…");
                            handler.removeCallbacks(connectionWatchdog);
                            handler.postDelayed(reconnectRunnable, 900L * reconnectAttempts);
                        } else {
                            callRequested = false;
                            updateStatus(CrewTheme.ROSE_400, "重連 3 次仍失敗：" + reason);
                            handler.removeCallbacks(connectionWatchdog);
                            updateCallButtonUi(false);
                            refreshAssistantControls();
                        }
                    }
                });
            }
            @Override public void onTranscript(final String role, final String text) {
                runOnUiThread(new Runnable() { @Override public void run() { appendTranscript(role, text); } });
            }
            @Override public void onSpeakingChanged(final boolean speaking) {
                if (speaking) {
                    updateStatus(CrewTheme.AMBER_400, "🔊 Gemini 正在說話...");
                } else {
                    updateStatus(CrewTheme.EMERALD_400, "🎙️ 聆聽中 (雙向全雙工)");
                }
            }
            @Override public void onMicrophoneLevel(final double dbfs, final double gateDbfs, final boolean sending) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { updateMicrophoneMeter(dbfs, gateDbfs, sending); }
                });
            }
        });
        client.start();
        handler.removeCallbacks(connectionWatchdog);
        handler.postDelayed(connectionWatchdog, 18000);
        updateCallButtonUi(true);
        refreshAssistantControls();
    }

    private void appendTranscript(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String existing = transcript.getText().toString();
        if (existing.startsWith("（通話中的")) existing = "";
        boolean sameSpeaker = role.equals(lastTranscriptRole) && !existing.isEmpty();
        String prefix = sameSpeaker ? "" : (existing.isEmpty() ? "" : "\n") + (role.equalsIgnoreCase("user") ? "🧑 我：" : "🤖 Gemini：");
        String next = existing + prefix + text.trim();
        if (next.length() > 12000) next = next.substring(next.length() - 12000);
        transcript.setText(next);
        lastTranscriptRole = role;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_RECORD_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleCall();
        else if (requestCode == REQUEST_RECORD_AUDIO) updateStatus(CrewTheme.ROSE_500, "未取得麥克風權限");
    }

    @Override protected void onDestroy() {
        callRequested = false;
        if (client != null) client.stop();
        handler.removeCallbacks(connectionWatchdog);
        handler.removeCallbacks(reconnectRunnable);
        super.onDestroy();
    }
}
