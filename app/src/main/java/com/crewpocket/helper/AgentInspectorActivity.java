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

/** Developer-only, sanitized view of the most recent Agent runtime trace. */
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
        title.setText("Agent Inspector");
        title.setTextSize(22);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("開發者工具 · 已脫敏 Runtime Trace");
        subtitle.setTextSize(11);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subtitle);

        header.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = smallButton("關閉");
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(68), dp(42)));
        root.addView(header);

        TextView privacy = new TextView(this);
        privacy.setText(
                "只保存工具名稱、成功/失敗、步數與狀態分類；"
                + "不保存螢幕截圖、對話內容、工具參數、模型回答、API Key 或 Bridge Token。");
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

        Button refresh = smallButton("重新整理");
        refresh.setOnClickListener(v -> refreshReport());

        Button copy = smallButton("複製 Debug Report");
        copy.setOnClickListener(v -> copyReport());

        Button clear = smallButton("清除");
        clear.setTextColor(Color.parseColor("#FDA4AF"));
        clear.setOnClickListener(v -> {
            AgentInspectorStore.clear(AgentInspectorActivity.this);
            refreshReport();
            Toast.makeText(
                    AgentInspectorActivity.this,
                    "Inspector 已清除",
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

    private void refreshReport() {
        if (reportView != null) {
            reportView.setText(AgentInspectorStore.buildReport(this));
        }
    }

    private void copyReport() {
        String report = AgentInspectorStore.buildReport(this);
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Crew Helper Debug Report", report));
            Toast.makeText(this, "Debug Report 已複製", Toast.LENGTH_SHORT).show();
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
