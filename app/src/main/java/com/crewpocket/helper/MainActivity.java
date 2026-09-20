package com.crewpocket.helper;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity
        implements NativeLiveService.RuntimeStateListener {
    static final String EXTRA_OPEN_GEMINI_KEY_SETTINGS = "crew.open_gemini_key_settings";
    private TextView statusDot;
    private TextView statusText;
    private TextView statusDetail;
    private LinearLayout statusCard;
    private LinearLayout pageContent;
    private FluidBubbleView homeOrb;
    private String homeRuntimeSignature = "";
    private final Button[] navButtons = new Button[3];
    private static final int[] NAV_ICONS = new int[]{
            CrewIcons.VOICE, CrewIcons.PRESENTATION, CrewIcons.SETTINGS};
    private int activeTab = 0;
    private boolean advancedSettingsExpanded = false;

    private int dp(float val) {
        return CrewTheme.dp(this, val);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DeckRepository.initialize(this);
        DeckWorkspaceRepository.initialize(this);

        // 🌌 Immersive Dark Status & Navigation Bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }
        getWindow().getDecorView().setBackgroundColor(CrewTheme.BG_PRIMARY);

        LinearLayout appRoot = new LinearLayout(this);
        appRoot.setOrientation(LinearLayout.VERTICAL);
        appRoot.setBackgroundColor(CrewTheme.BG_PRIMARY);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(20), dp(24), dp(20), dp(16));
        scroll.addView(pageContent);
        appRoot.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        appRoot.addView(buildBottomNavigation());
        setContentView(appRoot);

        boolean openGeminiKeySettings = getIntent() != null
                && getIntent().getBooleanExtra(
                        EXTRA_OPEN_GEMINI_KEY_SETTINGS, false);
        renderTab(openGeminiKeySettings ? 2 : 0);

        if (openGeminiKeySettings) {
            // Consume the one-shot deep link so Activity recreation does not
            // reopen the dialog forever.
            getIntent().removeExtra(EXTRA_OPEN_GEMINI_KEY_SETTINGS);
            pageContent.postDelayed(new Runnable() {
                @Override public void run() {
                    if (isFinishing()) return;
                    activeTab = 2;
                    renderSettingsPage();
                    refreshNavigation();
                    showSettingsDialog();
                }
            }, 180L);
        }

        // Request only runtime permissions when needed
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        NativeLiveService.reconcileAlwaysOn(this);
        if (activeTab == 0) renderHomePage();
        else refreshServiceStatus();

        if (pageContent != null) {
            pageContent.postDelayed(new Runnable() {
                @Override public void run() {
                    if (isFinishing()) return;
                    if (activeTab == 2) {
                        renderSettingsPage();
                        refreshNavigation();
                    } else {
                        refreshServiceStatus();
                    }
                }
            }, 900L);
        }

        NativeLiveService.setRuntimeStateListener(this);
    }


    @Override
    protected void onPause() {
        NativeLiveService.clearRuntimeStateListener(this);
        super.onPause();
    }

    @Override
    public void onRuntimeStateChanged() {
        if (isFinishing() || pageContent == null || activeTab != 0) return;

        String signature = buildHomeRuntimeSignature();
        if (signature.equals(homeRuntimeSignature)) return;

        homeRuntimeSignature = signature;
        renderHomePage();
    }

    private String buildHomeRuntimeSignature() {
        return NativeLiveService.getRuntimeState()
                + "|" + NativeLiveService.isActive()
                + "|" + NativeLiveService.isAiSpeaking()
                + "|" + NativeLiveService.hasActiveAgentTask()
                + "|" + AppConfig.isAlwaysOnEnabled(this);
    }

    private void renderTab(int tab) {
        activeTab = tab;
        if (tab == 0) renderHomePage();
        else if (tab == 1) renderDecksPage();
        else renderSettingsPage();
        refreshNavigation();
        refreshServiceStatus();
    }

    private void renderHomePage() {
        LinearLayout root = pageContent;
        root.removeAllViews();

        TextView title = new TextView(this);
        title.setText("Crew Helper");
        title.setTextSize(24);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(I18n.get(
                this,
                "語音、畫面理解與手機操作",
                "Voice, screen understanding, and phone actions"));
        subtitle.setTextSize(12);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        subtitle.setPadding(0, dp(3), 0, 0);
        root.addView(subtitle);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setGravity(Gravity.CENTER_HORIZONTAL);
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(18), 0, dp(14));
        statusCard.setLayoutParams(statusLp);

        homeOrb = new FluidBubbleView(this);
        statusCard.addView(homeOrb, new LinearLayout.LayoutParams(dp(92), dp(92)));

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER);
        stateRow.setPadding(0, dp(10), 0, 0);

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(11);
        statusDot.setPadding(0, 0, dp(7), 0);
        stateRow.addView(statusDot);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        stateRow.addView(statusText);
        statusCard.addView(stateRow);

        statusDetail = new TextView(this);
        statusDetail.setTextSize(11);
        statusDetail.setGravity(Gravity.CENTER);
        statusDetail.setTextColor(CrewTheme.TEXT_SECONDARY);
        statusDetail.setPadding(dp(8), dp(5), dp(8), 0);
        statusCard.addView(statusDetail);

        statusCard.setOnClickListener(v -> openFirstReadinessFix());
        root.addView(statusCard);

        boolean liveActive = NativeLiveService.isActive();
        Button liveButton = new Button(this);
        liveButton.setText(
                liveActive
                        ? I18n.get(this, "通話中 · 開啟控制", "Live · Open controls")
                        : I18n.get(this, "開始對話", "Start conversation"));
        liveButton.setCompoundDrawables(
                CrewIcons.drawable(this, CrewIcons.VOICE, Color.WHITE, dp(20)),
                null, null, null);
        liveButton.setCompoundDrawablePadding(dp(8));
        liveButton.setTextSize(15);
        liveButton.setTypeface(Typeface.DEFAULT_BOLD);
        liveButton.setTextColor(Color.WHITE);
        liveButton.setAllCaps(false);
        liveButton.setGravity(Gravity.CENTER);
        liveButton.setBackground(CrewTheme.createGradientButton(
                this, CrewTheme.TEAL_500, CrewTheme.INDIGO_500, 16));
        liveButton.setOnClickListener(v -> startActivity(
                new Intent(MainActivity.this, NativeLiveActivity.class)));

        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        liveLp.setMargins(0, 0, 0, dp(6));
        root.addView(liveButton, liveLp);

        NoteStore notebookStore = new NoteStore(this);
        root.addView(makeSettingsRow(
                CrewIcons.NOTEBOOK,
                I18n.get(this, "記事本", "Notebook"),
                notebookStore.count() + " " + I18n.get(this, "篇", "notes"),
                CrewTheme.TEAL_300,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(
                                MainActivity.this,
                                NotebookActivity.class));
                    }
                }));

        addSectionTitle(root, I18n.get(this, "隨身助理", "ASSISTANT"));

        final boolean wakeOn = AppConfig.isAlwaysOnEnabled(this);
        String runtime = NativeLiveService.getRuntimeState();
        String wakeDetail = wakeOn
                ? wakeRuntimeSummary(runtime)
                : I18n.get(
                        this,
                        "關閉時仍可手動開始通話",
                        "You can still start Live manually");

        root.addView(makeHomeControlRow(
                I18n.get(this, "喚醒詞", "Wake phrase"),
                wakeDetail,
                wakeOn,
                v -> {
                    if (AppConfig.isAlwaysOnEnabled(MainActivity.this)) {
                        NativeLiveService.disableAlwaysOn(MainActivity.this);
                    } else {
                        if (!hasMicrophonePermission()) {
                            requestPermissions(
                                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                                    991);
                            return;
                        }
                        NativeLiveService.enableAlwaysOn(MainActivity.this);
                    }
                    pageContent.postDelayed(() -> {
                        if (activeTab == 0) {
                            renderHomePage();
                            refreshServiceStatus();
                        }
                    }, 180L);
                }));

        FloatingBubbleManager bubbleManager = FloatingBubbleManager.getInstance(this);
        boolean bubbleOn = bubbleManager.isBubbleShowing();
        root.addView(makeHomeControlRow(
                I18n.get(this, "懸浮球", "Floating bubble"),
                bubbleOn
                        ? I18n.get(this, "顯示於其他 App 上方", "Visible above other apps")
                        : I18n.get(this, "點擊開啟", "Tap to enable"),
                bubbleOn,
                v -> {
                    FloatingBubbleManager manager =
                            FloatingBubbleManager.getInstance(MainActivity.this);
                    if (manager.isBubbleShowing()) manager.hideBubble();
                    else enableBubble();

                    pageContent.postDelayed(() -> {
                        if (activeTab == 0) {
                            renderHomePage();
                            refreshServiceStatus();
                        }
                    }, 120L);
                }));

        addSectionTitle(root, I18n.get(this, "裝置能力", "DEVICE CAPABILITIES"));

        addCapabilityRow(
                root,
                "Gemini",
                hasGeminiKey(),
                hasGeminiKey()
                        ? I18n.get(this, "已連線", "Ready")
                        : I18n.get(this, "需要 API Key", "API key required"),
                true,
                v -> openGeminiKeySettingsFromHome());

        boolean accessibility = CrewAccessibilityService.isServiceRunning();
        addCapabilityRow(
                root,
                I18n.get(this, "螢幕操作", "Screen actions"),
                accessibility,
                accessibility
                        ? I18n.get(this, "已授權", "Ready")
                        : I18n.get(
                                this,
                                "未啟用（選用）",
                                "Not enabled (optional)"),
                false,
                v -> showAccessibilityDisclosureDialog());

        addCapabilityRow(
                root,
                I18n.get(this, "麥克風", "Microphone"),
                hasMicrophonePermission(),
                hasMicrophonePermission()
                        ? I18n.get(this, "已授權", "Ready")
                        : I18n.get(this, "需要權限", "Permission required"),
                true,
                v -> requestPermissions(
                        new String[]{android.Manifest.permission.RECORD_AUDIO},
                        991));

        addCapabilityRow(
                root,
                I18n.get(this, "位置（約略）", "Approximate location"),
                hasApproximateLocationPermission(),
                hasApproximateLocationPermission()
                        ? I18n.get(this, "提供給 Live 基本位置上下文", "Available to Live session context")
                        : I18n.get(this, "未啟用（選用）", "Not enabled (optional)"),
                false,
                v -> requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},
                        993));

        addCapabilityRow(
                root,
                I18n.get(this, "懸浮視窗", "Overlay"),
                hasOverlayPermission(),
                hasOverlayPermission()
                        ? I18n.get(this, "已授權", "Ready")
                        : I18n.get(
                                this,
                                "未啟用（選用）",
                                "Not enabled (optional)"),
                false,
                v -> openOverlaySettings());

        if (wakeOn) {
            addCapabilityRow(
                    root,
                    I18n.get(this, "背景喚醒", "Background wake"),
                    isWakeCapabilityReady(),
                    wakeCapabilityDetail(),
                    true,
                    v -> fixWakeCapability());
        }

        homeRuntimeSignature = buildHomeRuntimeSignature();

        addFooter(root, false);
        refreshServiceStatus();
    }

    private View makeHomeControlRow(
            String title,
            String detail,
            boolean enabled,
            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        row.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(13);
        name.setTextColor(CrewTheme.TEXT_PRIMARY);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(name);

        TextView desc = new TextView(this);
        desc.setText(detail);
        desc.setTextSize(10.5f);
        desc.setTextColor(CrewTheme.TEXT_SECONDARY);
        desc.setPadding(0, dp(2), 0, 0);
        textCol.addView(desc);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(this);
        state.setText(enabled ? "ON" : "OFF");
        state.setTextSize(10);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setGravity(Gravity.CENTER);
        state.setTextColor(enabled ? CrewTheme.TEAL_300 : CrewTheme.TEXT_MUTED);
        state.setPadding(dp(9), dp(4), dp(9), dp(4));
        state.setBackground(CrewTheme.createCard(
                this,
                enabled ? Color.argb(45, 20, 184, 166) : Color.parseColor("#242426"),
                enabled ? CrewTheme.BORDER_TEAL : CrewTheme.BORDER_SUBTLE,
                12));
        row.addView(state);
        row.setOnClickListener(listener);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        outer.addView(row, lp);
        return outer;
    }

    private void addCapabilityRow(
            LinearLayout root,
            String title,
            boolean ready,
            String detail,
            boolean attentionWhenMissing,
            View.OnClickListener fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));

        TextView icon = new TextView(this);
        icon.setText(ready ? "✓" : (attentionWhenMissing ? "!" : "○"));
        icon.setTextSize(14);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextColor(
                ready
                        ? CrewTheme.EMERALD_400
                        : (attentionWhenMissing
                                ? CrewTheme.AMBER_400
                                : CrewTheme.TEXT_MUTED));
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(34)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(12.5f);
        name.setTextColor(CrewTheme.TEXT_PRIMARY);
        textCol.addView(name);

        TextView desc = new TextView(this);
        desc.setText(detail);
        desc.setTextSize(10);
        desc.setTextColor(CrewTheme.TEXT_SECONDARY);
        textCol.addView(desc);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!ready) {
            TextView action = new TextView(this);
            action.setText(I18n.get(
                    this,
                    attentionWhenMissing ? "修復 ›" : "啟用 ›",
                    attentionWhenMissing ? "Fix ›" : "Enable ›"));
            action.setTextSize(10.5f);
            action.setTextColor(
                    attentionWhenMissing
                            ? CrewTheme.AMBER_400
                            : CrewTheme.TEAL_300);
            row.addView(action);
            row.setOnClickListener(fix);
        }

        root.addView(row);
    }

    private boolean hasGeminiKey() {
        String key = AppConfig.getGeminiApiKey(this);
        return key != null && key.trim().length() >= 20;
    }

    private boolean hasMicrophonePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasApproximateLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    private boolean isCoreAssistantReady() {
        return hasGeminiKey() && hasMicrophonePermission();
    }

    private String firstCoreReadinessIssue() {
        if (!hasGeminiKey()) {
            return I18n.get(
                    this,
                    "需要設定 Gemini API Key",
                    "Gemini API key is required");
        }
        if (!hasMicrophonePermission()) {
            return I18n.get(
                    this,
                    "需要麥克風權限",
                    "Microphone permission is required");
        }
        return "";
    }

    private void openFirstReadinessFix() {
        if (!isCoreAssistantReady()) {
            if (!hasGeminiKey()) {
                openGeminiKeySettingsFromHome();
                return;
            }
            if (!hasMicrophonePermission()) {
                requestPermissions(
                        new String[]{android.Manifest.permission.RECORD_AUDIO},
                        991);
                return;
            }
        }

        if (AppConfig.isAlwaysOnEnabled(this) && !isWakeCapabilityReady()) {
            fixWakeCapability();
        }
    }


    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean areAppNotificationsEnabled() {
        try {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager)
                            getSystemService(NOTIFICATION_SERVICE);
            return manager == null || manager.areNotificationsEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isWakeCapabilityReady() {
        if (!AppConfig.isAlwaysOnEnabled(this)) return true;
        if (!hasNotificationPermission() || !areAppNotificationsEnabled()) {
            return false;
        }

        String runtime = NativeLiveService.getRuntimeState();
        return !"STOPPED".equals(runtime)
                && !"BLOCKED".equals(runtime)
                && !"DEGRADED".equals(runtime);
    }

    private String wakeCapabilityDetail() {
        if (!hasNotificationPermission()) {
            return I18n.get(
                    this,
                    "需要通知權限",
                    "Notification permission required");
        }
        if (!areAppNotificationsEnabled()) {
            return I18n.get(
                    this,
                    "系統通知已關閉",
                    "App notifications are disabled");
        }

        String runtime = NativeLiveService.getRuntimeState();
        if ("BLOCKED".equals(runtime)) {
            return I18n.get(this, "喚醒服務被阻擋", "Wake service is blocked");
        }
        if ("DEGRADED".equals(runtime)) {
            return I18n.get(this, "喚醒服務不穩定", "Wake service is degraded");
        }
        if ("STOPPED".equals(runtime)) {
            return I18n.get(
                    this,
                    "背景服務尚未啟動",
                    "Background service is not running");
        }
        if ("IDLE_MIC_YIELDED".equals(runtime)) {
            return I18n.get(
                    this,
                    "暫停：其他 App 正在使用麥克風",
                    "Paused: another app is using the microphone");
        }
        if ("ACTIVE".equals(runtime)) {
            return I18n.get(this, "目前通話中", "Live is active");
        }
        if ("IDLE_LISTENING".equals(runtime)) {
            return "「" + AppConfig.getWakePhrase(this) + "」· "
                    + I18n.get(this, "正在待命", "Listening");
        }
        return I18n.get(this, "正在準備", "Starting");
    }

    private String wakeRuntimeSummary(String runtime) {
        if (!hasNotificationPermission()) {
            return I18n.get(
                    this,
                    "需要通知權限",
                    "Notification permission required");
        }
        if (!areAppNotificationsEnabled()) {
            return I18n.get(
                    this,
                    "系統通知已關閉",
                    "App notifications are disabled");
        }
        if ("IDLE_LISTENING".equals(runtime)) {
            return "「" + AppConfig.getWakePhrase(this) + "」· "
                    + I18n.get(this, "正在待命", "Listening");
        }
        if ("ACTIVE".equals(runtime)) {
            return I18n.get(this, "目前通話中", "Live is active");
        }
        if ("IDLE_MIC_YIELDED".equals(runtime)) {
            return I18n.get(
                    this,
                    "暫停：其他 App 正在使用麥克風",
                    "Paused: another app is using the microphone");
        }
        if ("BLOCKED".equals(runtime) || "DEGRADED".equals(runtime)) {
            return I18n.get(
                    this,
                    "需要檢查喚醒服務",
                    "Wake service needs attention");
        }
        if ("STOPPED".equals(runtime)) {
            return I18n.get(
                    this,
                    "背景服務尚未啟動",
                    "Background service is not running");
        }
        return I18n.get(this, "正在準備", "Starting");
    }

    private void fixWakeCapability() {
        if (!hasNotificationPermission()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        992);
            }
            return;
        }

        if (!areAppNotificationsEnabled()) {
            openNotificationSettings();
            return;
        }

        renderTab(2);
        Toast.makeText(
                this,
                I18n.get(
                        this,
                        "請查看喚醒診斷資訊",
                        "Check wake diagnostics"),
                Toast.LENGTH_SHORT).show();
    }

    private void openNotificationSettings() {
        try {
            Intent intent =
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception error) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    private void openGeminiKeySettingsFromHome() {
        activeTab = 2;
        renderSettingsPage();
        refreshNavigation();
        pageContent.postDelayed(
                () -> {
                    if (!isFinishing()) showSettingsDialog();
                },
                120L);
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void renderDecksPage() {
        pageContent.removeAllViews();

        addPageHeading(CrewIcons.PRESENTATION,
            I18n.get(this, "AI 簡報", "AI Presentations"),
            I18n.get(this,
                "說一個主題，或指定資料夾讓 Gemini 依你的資料建立並主講簡報。",
                "Give Gemini a topic, or select a source folder to create and present from your own material."));

        Button createDeckBtn = new Button(this);
        createDeckBtn.setText(I18n.get(this, "AI 建立新簡報", "Create with AI"));
        createDeckBtn.setCompoundDrawables(
                CrewIcons.drawable(this, CrewIcons.SPARKLE, Color.WHITE, dp(20)),
                null, null, null);
        createDeckBtn.setCompoundDrawablePadding(dp(8));
        createDeckBtn.setTextSize(16);
        createDeckBtn.setTypeface(Typeface.DEFAULT_BOLD);
        createDeckBtn.setTextColor(Color.WHITE);
        createDeckBtn.setAllCaps(false);
        createDeckBtn.setGravity(Gravity.CENTER);
        createDeckBtn.setBackground(CrewTheme.createGradientButton(
                this, CrewTheme.INDIGO_500, CrewTheme.TEAL_500, 16));
        createDeckBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, NativeLiveActivity.class);
                intent.putExtra(
                        NativeLiveActivity.EXTRA_DECK_ENTRY_MODE,
                        NativeLiveActivity.DECK_ENTRY_CREATE);
                intent.putExtra(NativeLiveActivity.EXTRA_DECK_AUTO_START, true);
                startActivity(intent);
            }
        });
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        createLp.setMargins(0, dp(12), 0, dp(18));
        pageContent.addView(createDeckBtn, createLp);

        TextView createHint = new TextView(this);
        createHint.setText(I18n.get(this,
                "點下去後直接跟 Gemini 說主題，例如：「幫我做一份 5 分鐘介紹 AI Agent 的簡報」。不需要 deck.json。",
                "Then simply tell Gemini the topic, e.g. “Make a 5-minute presentation about AI agents.” No deck.json required."));
        createHint.setTextSize(11);
        createHint.setTextColor(CrewTheme.TEXT_SECONDARY);
        createHint.setPadding(dp(4), 0, dp(4), dp(16));
        pageContent.addView(createHint);

        addSectionTitle(pageContent, I18n.get(this, "簡報資料來源", "DECK WORKSPACE"));

        final org.json.JSONObject workspace = DeckWorkspaceRepository.getWorkspaceSummary();
        if (workspace.optBoolean("success", false)) {
            final String workspaceId = workspace.optString("workspaceId");
            String detail = workspace.optString("title", I18n.get(this, "已選資料夾", "Selected folder"))
                    + " · " + workspace.optInt("files", 0) + " "
                    + I18n.get(this, "個檔案", "files")
                    + " · " + workspace.optInt("readableSources", 0) + " "
                    + I18n.get(this, "個可讀內容", "readable");

            pageContent.addView(makeDeckActionCard(
                    CrewIcons.PRESENTATION,
                    I18n.get(this, "用目前資料來源建立簡報", "Create from current Workspace"),
                    detail,
                    CrewTheme.EMERALD_400,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            startWorkspaceDeckCreation(workspaceId);
                        }
                    }));

            pageContent.addView(makeDeckActionCard(
                    CrewIcons.PLUS,
                    I18n.get(this, "更換／重新整理資料夾", "Change / refresh source folder"),
                    I18n.get(this,
                            "重新選擇資料夾會重建索引；原始檔案不會被修改。",
                            "Selecting a folder rebuilds the private index; original files are never modified."),
                    CrewTheme.INDIGO_400,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            openDeckWorkspacePicker();
                        }
                    }));
        } else {
            pageContent.addView(makeDeckActionCard(
                    CrewIcons.PLUS,
                    I18n.get(this, "選擇資料夾生成簡報", "Choose a folder to create a presentation"),
                    I18n.get(this,
                            "Crew 先建立輕量索引，再由 Gemini 按需讀取相關檔案、提出大綱，確認後才生成簡報。",
                            "Crew builds a lightweight index first. Gemini reads only relevant sources, proposes an outline, then creates the deck after you confirm."),
                    CrewTheme.TEAL_400,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            openDeckWorkspacePicker();
                        }
                    }));
        }

        addSectionTitle(pageContent, I18n.get(this, "我的簡報", "MY PRESENTATIONS"));

        try {
            org.json.JSONObject res = DeckRepository.listDecks();
            org.json.JSONArray decks = res.optJSONArray("decks");
            if (decks != null && decks.length() > 0) {
                for (int i = 0; i < decks.length(); i++) {
                    final org.json.JSONObject deck = decks.getJSONObject(i);
                    final String deckId = deck.optString("deckId");
                    String deckTitle = deck.optString("title", deckId);
                    int cardCount = deck.optInt("cards", 0);

                    pageContent.addView(makeDeckActionCard(
                        CrewIcons.PRESENTATION,
                        deckTitle,
                        cardCount + " " + I18n.get(
                                MainActivity.this,
                                "張 · 點擊讓 Gemini 開始主講",
                                "cards · Tap to let Gemini present"),
                        CrewTheme.TEAL_400,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                Intent intent = new Intent(
                                        MainActivity.this, NativeLiveActivity.class);
                                intent.putExtra(
                                        NativeLiveActivity.EXTRA_DECK_ENTRY_MODE,
                                        NativeLiveActivity.DECK_ENTRY_PRESENT);
                                intent.putExtra(
                                        NativeLiveActivity.EXTRA_DECK_ID, deckId);
                                intent.putExtra(
                                        NativeLiveActivity.EXTRA_DECK_AUTO_START, true);
                                startActivity(intent);
                            }
                        }));
                }
            } else {
                TextView empty = new TextView(this);
                empty.setText(I18n.get(this,
                        "目前沒有已安裝簡報。可以先用 AI 建立一份。",
                        "No installed presentations yet. Create one with AI."));
                empty.setTextSize(12);
                empty.setTextColor(CrewTheme.TEXT_SECONDARY);
                empty.setPadding(dp(4), dp(4), dp(4), dp(14));
                pageContent.addView(empty);
            }
        } catch (Exception ignored) {}

        addSectionTitle(pageContent, I18n.get(this, "更多", "MORE"));

        pageContent.addView(makeDeckActionCard(
            CrewIcons.PLUS,
            I18n.get(this, "進階：匯入既有 deck.json", "Advanced: Import existing deck.json"),
            I18n.get(this,
                "只給已經準備好 deck.json 與圖片的舊式 Deck 使用；資料來源資料夾請用上面的 Deck Workspace。",
                "Only for legacy folders already containing deck.json and images. Use Deck Workspace above for source material."),
            CrewTheme.INDIGO_400,
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, 741);
                }
            }));

        addFooter(pageContent, false);
    }

    private void openDeckWorkspacePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, 742);
    }

    private void startWorkspaceDeckCreation(String workspaceId) {
        if (workspaceId == null || workspaceId.trim().isEmpty()) return;
        Intent intent = new Intent(MainActivity.this, NativeLiveActivity.class);
        intent.putExtra(
                NativeLiveActivity.EXTRA_DECK_ENTRY_MODE,
                NativeLiveActivity.DECK_ENTRY_CREATE);
        intent.putExtra(
                NativeLiveActivity.EXTRA_DECK_WORKSPACE_ID,
                workspaceId);
        intent.putExtra(NativeLiveActivity.EXTRA_DECK_AUTO_START, true);
        startActivity(intent);
    }

    private void renderSettingsPage() {
        pageContent.removeAllViews();

        addPageHeading(
                CrewIcons.SETTINGS,
                I18n.get(this, "設定", "Settings"),
                I18n.get(
                        this,
                        "只保留常用入口；細項集中在各分類裡。",
                        "Common settings first; detailed controls are grouped by category."));

        addSettingsAttentionCardIfNeeded();

        addSectionTitle(
                pageContent,
                I18n.get(this, "Crew", "CREW"));

        String personalityShort = personalityLabel(
                AppConfig.getPersonalityExpression(this),
                "direct", "直接",
                "lively", "活潑",
                "自然");
        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.VOICE,
                I18n.get(this, "語音與個性", "Voice & Personality"),
                AppConfig.getVoiceName(this) + " · " + personalityShort,
                CrewTheme.TEAL_300,
                v -> showVoiceAndPersonalitySettings()));

        String conversationSummary =
                interruptionSummary(AppConfig.getInterruptionSensitivity(this))
                        + " · " + audioOutputSummary()
                        + " · " + liveIdleTimeoutSummary(
                                AppConfig.getLiveIdleTimeoutSeconds(this));
        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.AUDIO,
                I18n.get(this, "對話體驗", "Conversation Experience"),
                conversationSummary,
                CrewTheme.CYAN_400,
                v -> showConversationExperienceSettings()));

        boolean alwaysOn = AppConfig.isAlwaysOnEnabled(this);
        boolean notificationReady =
                notificationPermissionGranted() && notificationsEnabled();
        String wakeSummary = "「" + AppConfig.getWakePhrase(this) + "」 · "
                + (alwaysOn
                        ? I18n.get(this, "待命 ON", "Standby ON")
                        : I18n.get(this, "待命 OFF", "Standby OFF"));
        if (alwaysOn && !notificationReady) {
            wakeSummary += " · " + I18n.get(this, "通知需處理", "Notifications need attention");
        }
        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.WAKE,
                I18n.get(this, "喚醒與待命", "Wake & Standby"),
                wakeSummary,
                alwaysOn ? CrewTheme.EMERALD_400 : CrewTheme.TEAL_300,
                v -> showWakeStandbySettings()));

        addSectionTitle(
                pageContent,
                I18n.get(this, "手機", "PHONE"));

        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.PHONE_ACTIONS,
                I18n.get(this, "手機能力", "Phone Capabilities"),
                phoneCapabilitiesSummary(),
                CrewTheme.INDIGO_400,
                v -> showPhoneCapabilitiesSettings()));

        addSectionTitle(
                pageContent,
                I18n.get(this, "學習與記憶", "LEARNING & MEMORY"));

        AppPlaybookStore appPlaybooks = new AppPlaybookStore(this);
        int learnedActions =
                new LearnedUiMappingStore(this).dumpForDebug("").length();
        int learnedExperience =
                new ReflectionLessonStore(this).count();
        String learnedSummary = learnedActions + " "
                + I18n.get(this, "動作", "actions")
                + " · " + learnedExperience + " Experience"
                + " · " + appPlaybooks.learnedRuleCount() + " "
                + I18n.get(this, "規則", "rules");
        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.BRAIN,
                I18n.get(this, "Crew 已學會", "Crew Learned"),
                learnedSummary,
                CrewTheme.TEAL_300,
                v -> startActivity(new Intent(
                        MainActivity.this,
                        CrewLearnedActivity.class))));

        addSectionTitle(
                pageContent,
                I18n.get(this, "App", "APP"));

        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.KEY,
                "Gemini",
                hasGeminiKey()
                        ? I18n.get(this, "API Key 已設定", "API key configured")
                        : I18n.get(this, "需要 API Key", "API key required"),
                hasGeminiKey()
                        ? CrewTheme.EMERALD_400
                        : CrewTheme.AMBER_400,
                v -> showSettingsDialog()));

        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.GLOBE,
                I18n.get(this, "語言", "Language"),
                languageSummary(),
                CrewTheme.INDIGO_400,
                v -> showLanguageDialog()));

        addSectionTitle(
                pageContent,
                I18n.get(this, "更多", "MORE"));

        pageContent.addView(makeSettingsOverviewRow(
                CrewIcons.DIAGNOSTICS,
                I18n.get(this, "進階", "Advanced"),
                advancedSettingsExpanded
                        ? I18n.get(this, "點擊收合", "Tap to collapse")
                        : I18n.get(this,
                                "自訂指令 · Inspector · 診斷",
                                "Custom instructions · Inspector · diagnostics"),
                CrewTheme.TEXT_SECONDARY,
                v -> {
                    advancedSettingsExpanded = !advancedSettingsExpanded;
                    renderSettingsPage();
                }));

        if (advancedSettingsExpanded) {
            String customPrompt = AppConfig.getCustomSystemPrompt(this);
            pageContent.addView(makeSettingsRow(
                    CrewIcons.BRAIN,
                    I18n.get(this, "進階自訂指令", "Advanced Custom Instructions"),
                    customPrompt == null || customPrompt.trim().isEmpty()
                            ? I18n.get(this, "未設定", "Not configured")
                            : I18n.get(this, "已設定", "Configured"),
                    customPrompt == null || customPrompt.trim().isEmpty()
                            ? CrewTheme.TEXT_MUTED
                            : CrewTheme.AMBER_400,
                    v -> showCustomPromptDialog()));

            pageContent.addView(makeSettingsRow(
                    CrewIcons.INSPECTOR,
                    "Agent Inspector",
                    I18n.get(this, "Runtime 診斷", "Runtime diagnostics"),
                    CrewTheme.TEXT_SECONDARY,
                    v -> startActivity(new Intent(
                            MainActivity.this,
                            AgentInspectorActivity.class))));

            pageContent.addView(makeSettingsRow(
                    CrewIcons.DIAGNOSTICS,
                    I18n.get(this, "診斷資訊", "Diagnostics"),
                    I18n.get(this, "服務與 Runtime 狀態", "Services & runtime state"),
                    CrewTheme.TEXT_SECONDARY,
                    v -> showDiagnosticsDialog()));
        }

        TextView version = new TextView(this);
        version.setText("Crew Helper " + appVersionSummary());
        version.setTextSize(9.5f);
        version.setTextColor(CrewTheme.TEXT_DISABLED);
        version.setTypeface(Typeface.MONOSPACE);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(18), 0, 0);
        pageContent.addView(version);

        addFooter(pageContent, false);
    }


    private View makeSettingsRow(
            int iconId,
            String titleText,
            String valueText,
            int valueColor,
            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        row.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                14));
        row.setOnClickListener(listener);

        CrewIconView icon = new CrewIconView(this);
        icon.setIcon(iconId, CrewTheme.TEXT_SECONDARY);
        icon.setIconScale(0.58f);
        row.addView(
                icon,
                new LinearLayout.LayoutParams(dp(34), dp(42)));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(13);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        TextView value = new TextView(this);
        value.setText(valueText == null ? "" : valueText);
        value.setTextSize(11.5f);
        value.setTextColor(valueColor);
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        value.setSingleLine(true);
        value.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        row.addView(
                value,
                new LinearLayout.LayoutParams(
                        dp(136),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(CrewTheme.TEXT_MUTED);
        chevron.setGravity(Gravity.CENTER);
        row.addView(
                chevron,
                new LinearLayout.LayoutParams(dp(20), dp(42)));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        outer.addView(row, lp);
        return outer;
    }

    private String personalitySummary() {
        return personalityLabel(
                        AppConfig.getPersonalityVerbosity(this),
                        "short", "精簡",
                        "detailed", "詳細",
                        "一般")
                + " · "
                + personalityLabel(
                        AppConfig.getPersonalityInitiative(this),
                        "quiet", "少打擾",
                        "proactive", "主動",
                        "平衡")
                + " · "
                + personalityLabel(
                        AppConfig.getPersonalityExpression(this),
                        "direct", "直接",
                        "lively", "活潑",
                        "自然")
                + " · "
                + personalityLabel(
                        AppConfig.getPersonalityExplanation(this),
                        "answer", "只給答案",
                        "teach", "教學",
                        "說明原因");
    }

    private String personalityLabel(
            String value,
            String firstValue,
            String firstLabel,
            String secondValue,
            String secondLabel,
            String fallbackLabel) {
        if (firstValue.equals(value)) return firstLabel;
        if (secondValue.equals(value)) return secondLabel;
        return fallbackLabel;
    }

    private String interruptionSummary(int value) {
        String label;
        if (value <= 35) {
            label = I18n.get(this, "低", "Low");
        } else if (value >= 71) {
            label = I18n.get(this, "高", "High");
        } else {
            label = I18n.get(this, "中", "Medium");
        }
        return label + " · " + value;
    }

    private String wakeSensitivitySummary(int value) {
        String label;
        if (value <= 40) {
            label = I18n.get(this, "低", "Low");
        } else if (value >= 76) {
            label = I18n.get(this, "高", "High");
        } else {
            label = I18n.get(this, "中", "Medium");
        }
        return label + " · " + value;
    }

    private String audioOutputSummary() {
        return "media".equals(AppConfig.getAudioOutput(this))
                ? I18n.get(this, "媒體模式", "Media")
                : I18n.get(this, "通話模式", "Call");
    }

    private String liveIdleTimeoutSummary(int seconds) {
        if (seconds <= 0) return I18n.get(this, "關閉", "Off");
        if (seconds < 60) return seconds + " " + I18n.get(this, "秒", "sec");
        return (seconds / 60) + " " + I18n.get(this, "分鐘", "min");
    }

    private String appVersionSummary() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(
                            getPackageName(), 0);
            String version = info.versionName;
            return version == null || version.trim().isEmpty()
                    ? ""
                    : "v" + version.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void showInterruptionSensitivityDialog() {
        final int current =
                AppConfig.getInterruptionSensitivity(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), 0);

        final TextView value = new TextView(this);
        value.setText(interruptionSummary(current));
        value.setTextSize(14);
        value.setTextColor(CrewTheme.TEXT_PRIMARY);
        value.setGravity(Gravity.CENTER);
        layout.addView(value);

        final android.widget.SeekBar seek =
                new android.widget.SeekBar(this);
        seek.setMax(100);
        seek.setProgress(current);
        layout.addView(seek);

        TextView hint = new TextView(this);
        hint.setText(I18n.get(
                this,
                "越高越容易在 Gemini 說話時插話打斷。設定於下一次通話套用。",
                "Higher values make barge-in easier while Gemini is speaking. Applies next session."));
        hint.setTextSize(11);
        hint.setTextColor(CrewTheme.TEXT_SECONDARY);
        hint.setPadding(0, dp(8), 0, 0);
        layout.addView(hint);

        seek.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(
                            android.widget.SeekBar bar,
                            int progress,
                            boolean fromUser) {
                        value.setText(interruptionSummary(progress));
                    }

                    @Override public void onStartTrackingTouch(
                            android.widget.SeekBar bar) {}

                    @Override public void onStopTrackingTouch(
                            android.widget.SeekBar bar) {}
                });

        final android.app.AlertDialog dialog =
                new android.app.AlertDialog.Builder(this)
                        .setTitle(I18n.get(
                                this,
                                "插話靈敏度",
                                "Interruption Sensitivity"))
                        .setView(layout)
                        .setPositiveButton(
                                I18n.get(this, "儲存", "Save"),
                                null)
                        .setNegativeButton(
                                I18n.get(this, "取消", "Cancel"),
                                null)
                        .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(
                    android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        AppConfig.setInterruptionSensitivity(
                                MainActivity.this,
                                seek.getProgress());
                        dialog.dismiss();
                        renderSettingsPage();
                    });
        });

        dialog.show();
    }

    private void showAudioOutputDialog() {
        final String current =
                AppConfig.getAudioOutput(this);
        final String[] values = {"call", "media"};
        final String[] labels = {
                I18n.get(
                        this,
                        "通話模式 · 適合語音對話與回音消除",
                        "Call mode · optimized for voice/AEC"),
                I18n.get(
                        this,
                        "媒體模式 · 跟隨媒體音量與裝置",
                        "Media mode · follows media volume/devices")
        };

        final int[] selected = {
                "media".equals(current) ? 1 : 0
        };

        new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.get(
                        this,
                        "音訊輸出",
                        "Audio Output"))
                .setSingleChoiceItems(
                        labels,
                        selected[0],
                        (dialog, which) -> selected[0] = which)
                .setPositiveButton(
                        I18n.get(this, "儲存", "Save"),
                        (dialog, which) -> {
                            AppConfig.setAudioOutput(
                                    MainActivity.this,
                                    values[selected[0]]);
                            Toast.makeText(
                                    MainActivity.this,
                                    I18n.get(
                                            MainActivity.this,
                                            "音訊輸出將於下一次通話套用",
                                            "Audio output applies next session"),
                                    Toast.LENGTH_SHORT).show();
                            renderSettingsPage();
                        })
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"),
                        null)
                .show();
    }

    private int currentFilterTab = 0; // 0: All, 1: Female, 2: Male

    private void showVoicePersonaDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(10));
        root.setBackgroundColor(CrewTheme.BG_PRIMARY);

        TextView titleView = new TextView(this);
        titleView.setText(I18n.get(this, "語音助理音色選擇 (全 30 款)", "Select Voice Persona (30 Voices)"));
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(CrewTheme.TEXT_PRIMARY);
        titleView.setPadding(0, 0, 0, dp(4));
        root.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(I18n.get(this, "點擊「試聽」可播放聲音，點擊卡片直接選用。", "Tap 'Preview' to listen, tap card to select."));
        subtitleView.setTextSize(11);
        subtitleView.setTextColor(CrewTheme.TEXT_SECONDARY);
        subtitleView.setPadding(0, 0, 0, dp(12));
        root.addView(subtitleView);

        // Filter Tabs Row
        final LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(0, 0, 0, dp(10));

        final String currentVoice = AppConfig.getVoiceName(this);
        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        final android.app.AlertDialog dialogRef[] = new android.app.AlertDialog[1];

        final Runnable refreshList = new Runnable() {
            @Override public void run() {
                listContainer.removeAllViews();
                for (int i = 0; i < VoiceCatalog.ALL_VOICES.length; i++) {
                    final VoiceInfo voice = VoiceCatalog.ALL_VOICES[i];
                    if (currentFilterTab == 1 && !voice.isFemale) continue;
                    if (currentFilterTab == 2 && voice.isFemale) continue;

                    final boolean isSelected = voice.name.equalsIgnoreCase(currentVoice);

                    LinearLayout itemCard = new LinearLayout(MainActivity.this);
                    itemCard.setOrientation(LinearLayout.HORIZONTAL);
                    itemCard.setGravity(Gravity.CENTER_VERTICAL);
                    itemCard.setPadding(dp(12), dp(10), dp(10), dp(10));
                    int cardBg = isSelected ? Color.parseColor("#140D9488") : CrewTheme.BG_SURFACE;
                    int borderCol = isSelected ? CrewTheme.TEAL_400 : CrewTheme.BORDER_SUBTLE;
                    itemCard.setBackground(CrewTheme.createCard(MainActivity.this, cardBg, borderCol, 12));

                    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cardLp.setMargins(0, 0, 0, dp(8));
                    itemCard.setLayoutParams(cardLp);

                    // Indicator
                    TextView indicator = new TextView(MainActivity.this);
                    indicator.setText(isSelected ? "●" : "○");
                    indicator.setTextSize(14);
                    indicator.setTextColor(isSelected ? CrewTheme.TEAL_400 : CrewTheme.TEXT_MUTED);
                    indicator.setPadding(0, 0, dp(10), 0);
                    itemCard.addView(indicator);

                    // Text Info
                    LinearLayout infoCol = new LinearLayout(MainActivity.this);
                    infoCol.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                    infoCol.setLayoutParams(infoLp);

                    LinearLayout nameBadgeRow = new LinearLayout(MainActivity.this);
                    nameBadgeRow.setOrientation(LinearLayout.HORIZONTAL);
                    nameBadgeRow.setGravity(Gravity.CENTER_VERTICAL);

                    TextView nameView = new TextView(MainActivity.this);
                    nameView.setText(voice.name);
                    nameView.setTextSize(13);
                    nameView.setTypeface(Typeface.DEFAULT_BOLD);
                    nameView.setTextColor(isSelected ? CrewTheme.TEAL_300 : CrewTheme.TEXT_PRIMARY);
                    nameBadgeRow.addView(nameView);

                    infoCol.addView(nameBadgeRow);

                    TextView descView = new TextView(MainActivity.this);
                    descView.setText(I18n.get(MainActivity.this, voice.zhDesc, voice.enDesc));
                    descView.setTextSize(10);
                    descView.setTextColor(CrewTheme.TEXT_SECONDARY);
                    descView.setPadding(0, dp(2), 0, 0);
                    infoCol.addView(descView);

                    itemCard.addView(infoCol);

                    // Audition Button
                    Button previewBtn = new Button(MainActivity.this);
                    previewBtn.setText(I18n.get(MainActivity.this, "試聽", "Play"));
                    previewBtn.setTextSize(11);
                    previewBtn.setTextColor(CrewTheme.CYAN_400);
                    previewBtn.setTypeface(Typeface.DEFAULT_BOLD);
                    previewBtn.setAllCaps(false);
                    previewBtn.setBackground(CrewTheme.createCard(MainActivity.this, CrewTheme.BG_PRIMARY, CrewTheme.BORDER_SUBTLE, 8));
                    previewBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
                    previewBtn.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            playAudition(voice);
                        }
                    });

                    LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
                    itemCard.addView(previewBtn, btnLp);

                    // Click item to select
                    itemCard.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            AppConfig.setVoiceName(MainActivity.this, voice.name);
                            Toast.makeText(MainActivity.this, I18n.get(MainActivity.this, "已選用音色：" + voice.name, "Switched to " + voice.name), Toast.LENGTH_SHORT).show();
                            if (dialogRef[0] != null) dialogRef[0].dismiss();
                            recreate();
                        }
                    });

                    listContainer.addView(itemCard);
                }
            }
        };

        // Tab Buttons
        String[] tabLabels = new String[]{
            I18n.get(this, "全部 (30)", "All (30)"),
            I18n.get(this, "女性 (15)", "Female (15)"),
            I18n.get(this, "男性 (15)", "Male (15)")
        };

        final Button[] tabButtons = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int tabIdx = i;
            Button tab = new Button(this);
            tab.setText(tabLabels[i]);
            tab.setTextSize(11);
            tab.setAllCaps(false);
            tabButtons[i] = tab;

            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    currentFilterTab = tabIdx;
                    for (int j = 0; j < 3; j++) {
                        boolean active = (j == currentFilterTab);
                        tabButtons[j].setTextColor(active ? Color.WHITE : CrewTheme.TEXT_MUTED);
                        tabButtons[j].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                        tabButtons[j].setBackground(CrewTheme.createCard(MainActivity.this, active ? CrewTheme.TEAL_500 : CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
                    }
                    refreshList.run();
                }
            });

            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
            if (i > 0) tabLp.setMargins(dp(6), 0, 0, 0);
            tabsRow.addView(tab, tabLp);
        }

        // Initialize Tab Styles
        for (int j = 0; j < 3; j++) {
            boolean active = (j == currentFilterTab);
            tabButtons[j].setTextColor(active ? Color.WHITE : CrewTheme.TEXT_MUTED);
            tabButtons[j].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabButtons[j].setBackground(CrewTheme.createCard(this, active ? CrewTheme.TEAL_500 : CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        }

        root.addView(tabsRow);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
        listScroll.setLayoutParams(scrollLp);
        listScroll.addView(listContainer);
        root.addView(listScroll);

        refreshList.run();

        builder.setView(root);
        builder.setNegativeButton(I18n.get(this, "關閉", "Close"), new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                GeminiVoicePreviewClient.stop();
            }
        });

        android.app.AlertDialog dialog = builder.create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 742 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri workspaceUri = data.getData();
            Toast.makeText(
                    this,
                    I18n.get(this, "正在建立簡報資料索引…", "Indexing presentation sources..."),
                    Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                @Override public void run() {
                    final org.json.JSONObject res =
                            DeckWorkspaceRepository.importWorkspaceTree(
                                    MainActivity.this,
                                    workspaceUri);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            if (res.optBoolean("success", false)) {
                                Toast.makeText(
                                        MainActivity.this,
                                        I18n.get(
                                                MainActivity.this,
                                                "已索引 " + res.optInt("files", 0) + " 個檔案，開始規劃簡報",
                                                "Indexed " + res.optInt("files", 0) + " files. Starting deck planning."),
                                        Toast.LENGTH_LONG).show();
                                startWorkspaceDeckCreation(
                                        res.optString("workspaceId"));
                            } else {
                                Toast.makeText(
                                        MainActivity.this,
                                        "❌ " + res.optString("error"),
                                        Toast.LENGTH_LONG).show();
                                if (activeTab == 1) renderDecksPage();
                            }
                        }
                    });
                }
            }, "crew-deck-workspace-index").start();
            return;
        }

        if (requestCode == 741 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Toast.makeText(this, I18n.get(this, "正在匯入 Deck…", "Importing Deck..."), Toast.LENGTH_SHORT).show();
            org.json.JSONObject res = DeckRepository.importDeckTree(this, data.getData());
            if (res.optBoolean("success", false)) {
                Toast.makeText(this, res.optString("message"), Toast.LENGTH_LONG).show();
                if (activeTab == 1) renderDecksPage();
            } else {
                Toast.makeText(this, "❌ " + res.optString("error"), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GeminiVoicePreviewClient.stop();
    }

    private void refreshServiceStatus() {
        if (statusDot == null
                || statusText == null
                || statusDetail == null
                || statusCard == null
                || activeTab != 0) {
            return;
        }

        boolean coreReady = isCoreAssistantReady();
        String runtime = NativeLiveService.getRuntimeState();
        boolean agentWorking = NativeLiveService.hasActiveAgentTask();
        boolean speaking = NativeLiveService.isAiSpeaking();
        boolean live = NativeLiveService.isActive();
        boolean wakeAttention =
                AppConfig.isAlwaysOnEnabled(this)
                        && !isWakeCapabilityReady();

        if (homeOrb != null) {
            homeOrb.setAgentWorking(false);
            homeOrb.setAgentNeedsAttention(false);

            if (!coreReady) {
                homeOrb.setNativeVoiceState(3);
            } else if (agentWorking) {
                homeOrb.setNativeVoiceState(1);
                homeOrb.setAgentWorking(true);
            } else if (speaking) {
                homeOrb.setNativeVoiceState(2);
            } else if (live) {
                homeOrb.setNativeVoiceState(1);
            } else if (wakeAttention) {
                homeOrb.setNativeVoiceState(0);
                homeOrb.setAgentNeedsAttention(true);
            } else {
                homeOrb.setNativeVoiceState(0);
            }
        }

        if (!coreReady) {
            statusDot.setTextColor(CrewTheme.AMBER_400);
            statusText.setText(I18n.get(
                    this,
                    "需要完成核心設定",
                    "Core setup required"));
            statusText.setTextColor(CrewTheme.AMBER_400);
            statusDetail.setText(firstCoreReadinessIssue());
            statusCard.setBackground(CrewTheme.createCard(
                    this,
                    Color.parseColor("#1A78350F"),
                    Color.parseColor("#4DF59E0B"),
                    18));
            return;
        }

        statusDot.setTextColor(CrewTheme.EMERALD_400);
        statusText.setTextColor(CrewTheme.TEXT_PRIMARY);

        if (agentWorking) {
            statusText.setText(I18n.get(this, "正在執行任務", "Working on a task"));
            statusDetail.setText(I18n.get(
                    this,
                    "詳細步驟只保留在 Agent Inspector",
                    "Detailed steps stay in Agent Inspector"));
        } else if (speaking) {
            statusText.setText(I18n.get(this, "Gemini 正在回覆", "Gemini is speaking"));
            statusDetail.setText(I18n.get(
                    this,
                    "可以直接插話打斷",
                    "You can interrupt naturally"));
        } else if (live) {
            statusText.setText(I18n.get(this, "正在聆聽", "Listening"));
            statusDetail.setText(I18n.get(
                    this,
                    "直接說出你要做的事",
                    "Say what you want to do"));
        } else if (wakeAttention) {
            statusDot.setTextColor(CrewTheme.AMBER_400);
            statusText.setTextColor(CrewTheme.AMBER_400);
            statusText.setText(I18n.get(
                    this,
                    "助理可用 · 喚醒詞需要處理",
                    "Assistant ready · Wake needs attention"));
            statusDetail.setText(wakeCapabilityDetail());
            statusCard.setBackground(CrewTheme.createCard(
                    this,
                    Color.parseColor("#1A78350F"),
                    Color.parseColor("#4DF59E0B"),
                    18));
            return;
        } else if (AppConfig.isAlwaysOnEnabled(this)
                && "IDLE_LISTENING".equals(runtime)) {
            statusText.setText(I18n.get(
                    this,
                    "助理正在待命",
                    "Assistant is standing by"));
            statusDetail.setText(
                    "說「" + AppConfig.getWakePhrase(this) + "」"
                            + I18n.get(this, "即可開始", " to start"));
        } else {
            statusText.setText(I18n.get(this, "準備就緒", "Ready"));

            boolean phoneControl = CrewAccessibilityService.isServiceRunning();
            boolean overlay = hasOverlayPermission();

            if (phoneControl && overlay) {
                statusDetail.setText(I18n.get(
                        this,
                        "語音與手機操作均可使用",
                        "Voice and phone actions are ready"));
            } else if (!phoneControl && !overlay) {
                statusDetail.setText(I18n.get(
                        this,
                        "語音已可用 · 手機操作與懸浮球尚未啟用",
                        "Voice ready · Phone actions and bubble are optional"));
            } else if (!phoneControl) {
                statusDetail.setText(I18n.get(
                        this,
                        "語音已可用 · 手機操作尚未啟用",
                        "Voice ready · Phone actions are optional"));
            } else {
                statusDetail.setText(I18n.get(
                        this,
                        "語音與手機操作可用 · 懸浮球尚未啟用",
                        "Voice and phone actions ready · Bubble is optional"));
            }
        }

        statusCard.setBackground(CrewTheme.createCard(
                this,
                Color.parseColor("#111F2937"),
                Color.parseColor("#334155"),
                18));
    }
}
