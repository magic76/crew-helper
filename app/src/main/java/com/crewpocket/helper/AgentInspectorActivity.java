package com.crewpocket.helper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

/** Developer-facing, sanitized view of self-improvement and runtime traces. */
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
        title.setText(I18n.get(this, "自省紀錄", "Reflection Insights"));
        title.setTextSize(22);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(I18n.get(
                this,
                "Self-Improvement · Evidence Rules、模型與 Runtime 診斷",
                "Self-improvement · evidence rules, models, and runtime diagnostics"));
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
                "這裡顯示的是已脫敏的自省結果：App、Runtime Evidence Rule、Lesson、信心與確認次數。"
                        + "Rule identity 由 Runtime evidence 決定，不由模型自由命名。"
                        + "不保存或顯示對話內容、訊息文字、搜尋值、密碼、OTP、付款資料、任意工具參數、API Key 或模型原始回答。",
                "This page shows sanitized reflection results: app, Runtime evidence rule, lesson, confidence, and confirmations. "
                        + "Rule identity comes from Runtime evidence, not model-authored categories. "
                        + "It does not store or show conversations, message text, search values, passwords, OTPs, payment data, arbitrary tool arguments, API keys, or raw model replies."));
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);

        Button refresh = smallButton(I18n.get(this, "重新整理", "Refresh"));
        refresh.setOnClickListener(v -> refreshReport());

        Button copy = smallButton(I18n.get(this, "複製報告", "Copy report"));
        copy.setOnClickListener(v -> copyReport());

        Button clear = smallButton(I18n.get(this, "清除診斷", "Clear diagnostics"));
        clear.setTextColor(Color.parseColor("#FDA4AF"));
        clear.setOnClickListener(v -> {
            AgentInspectorStore.clear(AgentInspectorActivity.this);
            ReflectionHistoryStore.clear(AgentInspectorActivity.this);
            refreshReport();
            Toast.makeText(
                    AgentInspectorActivity.this,
                    I18n.get(
                            AgentInspectorActivity.this,
                            "Runtime 與 Reflection History 已清除；已學習的 Lesson 不會刪除",
                            "Runtime and reflection history cleared; learned lessons were kept"),
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

    private String buildFullReport() {
        return ReflectionLessonStore.buildReport(this)
                + "\n\n────────────────────\n\n"
                + ReflectionHistoryStore.buildReport(this)
                + "\n\n────────────────────\n\n"
                + ReflectionLearningStats.buildReport(this)
                + "\n\n────────────────────\n\n"
                + AgentInspectorStore.buildReport(this);
    }

    private void refreshReport() {
        if (reportView != null) {
            reportView.setText(buildFullReport());
        }
    }

    private void copyReport() {
        String report = buildFullReport();
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Crew Helper Reflection Report", report));
            Toast.makeText(
                    this,
                    I18n.get(this, "自省報告已複製", "Reflection report copied"),
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
