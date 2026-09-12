package com.crewpocket.helper;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class PersonalitySettingsActivity extends Activity {
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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(CrewTheme.BG_PRIMARY);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("說話個性");
        title.setTextSize(22);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("只影響表達方式，不改變工具權限與安全規則");
        subtitle.setTextSize(11);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        subtitle.setPadding(0, dp(3), 0, 0);
        titleCol.addView(subtitle);

        header.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = makeSmallButton("完成");
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(72), dp(42)));
        root.addView(header);

        TextView nextSession = new TextView(this);
        nextSession.setText("設定會在下一次通話套用。音色請在「設定 → 音色」獨立選擇。");
        nextSession.setTextSize(11);
        nextSession.setTextColor(CrewTheme.TEXT_SECONDARY);
        nextSession.setPadding(0, dp(14), 0, dp(12));
        root.addView(nextSession);

        addSectionLabel(root, "快速模板");

        LinearLayout templateRow1 = new LinearLayout(this);
        templateRow1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(templateRow1);
        addTemplateButton(templateRow1, "簡潔助手", "brief");
        addTemplateButton(templateRow1, "工作拍檔", "work");

        LinearLayout templateRow2 = new LinearLayout(this);
        templateRow2.setOrientation(LinearLayout.HORIZONTAL);
        templateRow2.setPadding(0, dp(6), 0, 0);
        root.addView(templateRow2);
        addTemplateButton(templateRow2, "聊天型", "chat");
        addTemplateButton(templateRow2, "老師型", "teacher");

        addDimension(root, "回答長度",
                AppConfig.getPersonalityVerbosity(this),
                new String[]{"short", "balanced", "detailed"},
                new String[]{"精簡", "一般", "詳細"},
                value -> AppConfig.setPersonalityVerbosity(this, value));

        addDimension(root, "主動程度",
                AppConfig.getPersonalityInitiative(this),
                new String[]{"quiet", "balanced", "proactive"},
                new String[]{"少打擾", "平衡", "主動建議"},
                value -> AppConfig.setPersonalityInitiative(this, value));

        addDimension(root, "表達方式",
                AppConfig.getPersonalityExpression(this),
                new String[]{"direct", "natural", "lively"},
                new String[]{"直接", "自然", "活潑"},
                value -> AppConfig.setPersonalityExpression(this, value));

        addDimension(root, "解釋程度",
                AppConfig.getPersonalityExplanation(this),
                new String[]{"answer", "reason", "teach"},
                new String[]{"只給答案", "說明原因", "像老師一樣教"},
                value -> AppConfig.setPersonalityExplanation(this, value));

        addDimension(root, "幽默感",
                AppConfig.getPersonalityHumor(this),
                new String[]{"none", "light", "playful"},
                new String[]{"無", "偶爾", "明顯"},
                value -> AppConfig.setPersonalityHumor(this, value));

        addDimension(root, "決策風格",
                AppConfig.getPersonalityDecisionStyle(this),
                new String[]{"cautious", "balanced", "decisive"},
                new String[]{"保守", "平衡", "果斷"},
                value -> AppConfig.setPersonalityDecisionStyle(this, value));

        setContentView(scroll);
    }

    private interface ValueSetter {
        void set(String value);
    }

    private void addTemplateButton(LinearLayout row, String label, String template) {
        Button button = makeSmallButton(label);
        button.setOnClickListener(v -> {
            AppConfig.applyPersonalityTemplate(this, template);
            Toast.makeText(
                    this,
                    "已套用「" + label + "」，下次通話生效",
                    Toast.LENGTH_SHORT).show();
            recreate();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, lp);
    }

    private void addDimension(
            LinearLayout root,
            String title,
            String current,
            String[] values,
            String[] labels,
            ValueSetter setter) {
        addSectionLabel(root, title);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(6), dp(2), dp(6), dp(4));
        group.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 14));

        int selectedId = View.NO_ID;
        for (int i = 0; i < values.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setText(labels[i]);
            radio.setTextSize(13);
            radio.setTextColor(CrewTheme.TEXT_PRIMARY);
            radio.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    CrewTheme.TEAL_400));
            radio.setPadding(dp(8), dp(2), dp(8), dp(2));
            radio.setTag(values[i]);
            group.addView(radio, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            if (values[i].equals(current)) selectedId = radio.getId();
        }

        if (selectedId != View.NO_ID) group.check(selectedId);

        group.setOnCheckedChangeListener((g, checkedId) -> {
            RadioButton selected = g.findViewById(checkedId);
            if (selected == null || selected.getTag() == null) return;
            setter.set(String.valueOf(selected.getTag()));
        });

        root.addView(group);
    }

    private void addSectionLabel(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(11);
        label.setTextColor(CrewTheme.TEXT_SECONDARY);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(2), dp(18), 0, dp(7));
        root.addView(label);
    }

    private Button makeSmallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11.5f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(CrewTheme.TEXT_PRIMARY);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(CrewTheme.createCard(
                this,
                Color.parseColor("#FF2C2C2E"),
                CrewTheme.BORDER_SUBTLE,
                12));
        return button;
    }
}
