package com.crewpocket.helper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Developer-facing, sanitized view of Crew Experience and runtime traces. */
public class AgentInspectorActivity extends Activity {
    private TextView reportView;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CrewTheme.BG_PRIMARY);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Crew Experience");
        title.setTextSize(22);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(I18n.get(
                this,
                "從真正的修正與重複成功路徑中學習 · Developer diagnostics",
                "Learns from proven recovery and repeated successful patterns · Developer diagnostics"));
        subtitle.setTextSize(11);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subtitle);

        header.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = smallButton(I18n.get(this, "關閉", "Close"));
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(68), dp(42)));
        root.addView(header);

        TextView privacy = new TextView(this);
        privacy.setText(I18n.get(
                this,
                "Crew 只會在 Runtime 有足夠證據時學習：失敗後修正成功會立即進入 Experience Review；一般成功路徑需重複出現才會進入模型。"
                        + "Rule identity 由 Runtime evidence 決定，模型只負責把已驗證的 evidence 壓成 Lesson。"
                        + "App Action Memory 則保存使用者明確教過的 UI 結構映射，與 Experience 分開。"
                        + "一般診斷不保存對話、訊息、搜尋值、密碼、OTP、付款資料、工具參數、API Key 或模型原始回答。"
                        + "手動開啟語音診斷後，只在記憶體顯示最新一句辨識文字（可能包含隱私）；關閉就清除，不寫入磁碟。",
                "Crew learns only when Runtime has sufficient evidence: proven recovery is reviewed immediately, while normal successful paths must repeat before model review. "
                        + "Rule identity comes from Runtime evidence; the model only compresses qualified evidence into a lesson. "
                        + "App Action Memory separately stores structural UI mappings explicitly taught by the user. "
                        + "Normal diagnostics never store conversations, message text, search values, passwords, OTPs, payment data, tool arguments, API keys, or raw model replies. "
                        + "Opt-in Voice Debug shows the latest raw transcription in RAM (possibly private); turning it off clears it without persisting speech."));
        privacy.setTextSize(11);
        privacy.setTextColor(CrewTheme.TEXT_SECONDARY);
        privacy.setPadding(0, dp(14), 0, dp(10));
        root.addView(privacy);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        reportView = new TextView(this);
        reportView.setTextSize(11.5f);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextColor(Color.parseColor("#E4E4E7"));
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(14), dp(14), dp(14), dp(14));

        GradientDrawable reportBg = CrewTheme.createCard(
                this,
                Color.parseColor("#FF242426"),
                Color.parseColor("#52525B"),
                16);
        reportView.setBackground(reportBg);

        scroll.addView(reportView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button debugLab = smallButton("");
        updateDebugLabLabel(debugLab);
        LinearLayout.LayoutParams debugLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        debugLp.setMargins(dp(3), dp(10), dp(3), 0);
        debugLab.setOnClickListener(v -> {
            boolean on = !getSharedPreferences(
                    "crew_debug_lab", Context.MODE_PRIVATE)
                    .getBoolean("enabled", false);
            getSharedPreferences("crew_debug_lab", Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled", on).apply();
            CrewDebugTrace.setEnabled(on);
            if (!on) {
                CrewDebugShareProvider.targetFile(this).delete();
            }
            updateDebugLabLabel(debugLab);
            refreshReport();
        });
        root.addView(debugLab, debugLp);

        Button exportDebug = smallButton(I18n.get(
                this, "匯出 Debug ZIP（手動分享）", "Export Debug ZIP (share manually)"));
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        exportLp.setMargins(dp(3), dp(8), dp(3), 0);
        exportDebug.setOnClickListener(v -> exportDebugBundle(exportDebug));
        root.addView(exportDebug, exportLp);

        Button voiceDebug = smallButton("");
        updateVoiceDebugLabel(voiceDebug);
        LinearLayout.LayoutParams voiceDebugLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        voiceDebugLp.setMargins(dp(3), dp(10), dp(3), 0);
        voiceDebug.setOnClickListener(v -> {
            VoiceDebugTrace.setEnabled(
                    AgentInspectorActivity.this,
                    !VoiceDebugTrace.enabled(AgentInspectorActivity.this));
            updateVoiceDebugLabel(voiceDebug);
            refreshReport();
        });
        root.addView(voiceDebug, voiceDebugLp);

        Button memoryDashboard = smallButton(I18n.get(
                this,
                "開啟 Memory Dashboard",
                "Open Memory Dashboard"));
        LinearLayout.LayoutParams memoryDashboardLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(44));
        memoryDashboardLp.setMargins(dp(3), dp(12), dp(3), 0);
        memoryDashboard.setOnClickListener(v -> startActivity(
                new Intent(
                        AgentInspectorActivity.this,
                        RefinedMemoryDashboardActivity.class)));
        root.addView(memoryDashboard, memoryDashboardLp);

        Button testModel = smallButton(I18n.get(
                this,
                "測試 Experience Model",
                "Test Experience model"));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44));
        testLp.setMargins(dp(3), dp(12), dp(3), 0);
        testModel.setOnClickListener(v -> runModelDiagnostic(testModel));
        root.addView(testModel, testLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button refresh = smallButton(I18n.get(this, "重新整理", "Refresh"));
        refresh.setOnClickListener(v -> refreshReport());

        Button copy = smallButton(I18n.get(this, "複製報告", "Copy report"));
        copy.setOnClickListener(v -> copyReport());

        Button clear = smallButton(I18n.get(this, "清除診斷", "Clear diagnostics"));
        clear.setTextColor(Color.parseColor("#FDA4AF"));
        clear.setOnClickListener(v -> {
            AgentInspectorStore.clear(AgentInspectorActivity.this);
            if (CrewDebugTrace.enabled()) CrewDebugTrace.startSession();
            CrewDebugShareProvider.targetFile(this).delete();
            ReflectionHistoryStore.clear(AgentInspectorActivity.this);
            VoiceDebugTrace.clear();
            refreshReport();
            Toast.makeText(
                    AgentInspectorActivity.this,
                    I18n.get(
                            AgentInspectorActivity.this,
                            "Runtime 與 Experience Model 診斷已清除；學習中的 Experience、App Action Memory 與已學 Lesson 不會刪除",
                            "Runtime and Experience model diagnostics cleared; accumulated experience, App Action Memory, and learned lessons were kept"),
                    Toast.LENGTH_SHORT).show();
        });

        actions.addView(refresh, actionLp());
        actions.addView(copy, actionLp());
        actions.addView(clear, actionLp());
        root.addView(actions);

        setContentView(root);
        refreshReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReport();
    }

    private void updateDebugLabLabel(Button button) {
        boolean on = getSharedPreferences(
                "crew_debug_lab", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        button.setText(on
                ? I18n.get(this, "Debug Lab：開啟（點擊停止並清除）",
                        "Debug Lab: ON (tap to stop and clear)")
                : I18n.get(this, "Debug Lab：關閉（點擊開始記錄）",
                        "Debug Lab: OFF (tap to start)"));
    }

    private void exportDebugBundle(Button button) {
        CrewDebugTrace.Snapshot snapshot = CrewDebugTrace.snapshot();
        if (!snapshot.enabled || snapshot.startedAt <= 0L) {
            Toast.makeText(this,
                    I18n.get(this, "請先開啟 Debug Lab 再重現問題",
                            "Enable Debug Lab and reproduce the issue first"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        button.setEnabled(false);
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                String version = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                CrewDebugBundle.write(
                        CrewDebugShareProvider.targetFile(app),
                        snapshot, version, LivePipelineDiagnostic.report());
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/zip");
                    send.putExtra(Intent.EXTRA_STREAM,
                            CrewDebugShareProvider.SHARE_URI);
                    send.setClipData(ClipData.newUri(
                            getContentResolver(), "Crew Debug Lab",
                            CrewDebugShareProvider.SHARE_URI));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send,
                            I18n.get(this, "分享診斷 ZIP", "Share debug ZIP")));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    Toast.makeText(this,
                            I18n.get(this, "診斷 ZIP 產生失敗",
                                    "Debug ZIP export failed"),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "CrewDebugLabExport").start();
    }

    private void updateVoiceDebugLabel(Button button) {
        boolean on = VoiceDebugTrace.enabled(this);
        button.setText(on
                ? I18n.get(this, "語音診斷：開啟（點擊關閉並清除）",
                        "Voice Debug: ON (tap to disable and clear)")
                : I18n.get(this, "語音診斷：關閉（點擊暫時開啟）",
                        "Voice Debug: OFF (tap to enable)"));
    }

    private String buildFullReport() {
        return CrewDebugTrace.snapshot().summary()
                + "\n\n────────────────────\n\n"
                + VoiceDebugTrace.report(this)
                + "\n\n────────────────────\n\n"
                + ReflectionLessonStore.buildReport(this)
                + "\n\n────────────────────\n\n"
                + ExperienceEvidenceStore.buildReport(this)
                + "\n\n────────────────────\n\n"
                + ReflectionHistoryStore.buildReport(this)
                + "\n\n────────────────────\n\n"
                + ReflectionLearningStats.buildReport(this)
                + "\n\n────────────────────\n\n"
                + LearnedUiMappingStore.buildActionMemoryReport(this)
                + "\n\n────────────────────\n\n"
                + AgentInspectorStore.buildReport(this);
    }

    private void refreshReport() {
        if (reportView != null) reportView.setText(buildFullReport());
    }

    private void runModelDiagnostic(Button button) {
        if (button == null || !button.isEnabled()) return;
        final String idleLabel = I18n.get(
                this,
                "測試 Experience Model",
                "Test Experience model");
        button.setEnabled(false);
        button.setText(I18n.get(this, "測試中…", "Testing…"));

        final Context appContext = getApplicationContext();
        new Thread(() -> {
            GeminiTaskReflector.DiagnosticResult result;
            try {
                String apiKey = AppConfig.getGeminiApiKey(appContext);
                result = new GeminiTaskReflector(apiKey).diagnose();
            } catch (Exception error) {
                result = new GeminiTaskReflector.DiagnosticResult(
                        false,
                        "LOCAL_ERROR",
                        GeminiTaskReflector.MODEL,
                        GeminiTaskReflector.safeFailureCode(error),
                        false,
                        0L);
            }

            final GeminiTaskReflector.DiagnosticResult diagnostic = result;
            ReflectionHistoryStore.recordDiagnostic(appContext, diagnostic);

            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText(idleLabel);
                refreshReport();

                String message;
                if (diagnostic.success) {
                    message = I18n.get(
                            AgentInspectorActivity.this,
                            "模型測試成功：",
                            "Model test succeeded: ")
                            + diagnostic.selectedModel;
                    if (diagnostic.fallbackUsed) {
                        message += I18n.get(
                                AgentInspectorActivity.this,
                                "（使用 fallback）",
                                " (fallback used)");
                    }
                } else {
                    message = I18n.get(
                            AgentInspectorActivity.this,
                            "模型測試失敗：",
                            "Model test failed: ")
                            + diagnostic.selectedStatus;
                }
                Toast.makeText(
                        AgentInspectorActivity.this,
                        message,
                        Toast.LENGTH_LONG).show();
            });
        }, "CrewExperienceDiagnostic").start();
    }

    private void copyReport() {
        String report = buildFullReport();
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Crew Helper Experience Report", report));
            Toast.makeText(
                    this,
                    I18n.get(this, "Experience 報告已複製", "Experience report copied"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams actionLp() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(CrewTheme.TEXT_PRIMARY);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                12));
        return button;
    }
}
