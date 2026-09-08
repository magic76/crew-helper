package com.crewpocket.helper;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 0026 Settings UI for reviewing/editing Correction Memory. */
public class CorrectionRulesActivity extends Activity {
    private LinearLayout content;
    private CorrectionRuleStore store;
    private MemoryRuleStore memoryStore;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
        getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        store = new CorrectionRuleStore(this);
        memoryStore = new MemoryRuleStore(this);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText(I18n.get(this,
                "🧠 已學習操作與快捷指令",
                "🧠 Learned Operations & Shortcuts"));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        content.addView(title);

        TextView desc = new TextView(this);
        desc.setText(I18n.get(this,
                "包含單步修正與你明確建立的快捷指令；舊 Memory Rule 會自動相容。不保存密碼、OTP 或訊息內容。",
                "Contains verified corrections and explicitly created shortcuts. Legacy Memory Rules remain compatible. Passwords, OTPs and message contents are not stored."));
        desc.setTextSize(12);
        desc.setTextColor(CrewTheme.TEXT_SECONDARY);
        desc.setPadding(0, dp(6), 0, dp(16));
        content.addView(desc);

        List<CorrectionRuleStore.Rule> rules = store.listRules();

        TextView summary = new TextView(this);
        summary.setText(I18n.get(this,
                "修正 " + rules.size() + " 條 · 快捷指令 " + memoryStore.count()
                        + " 條 · 啟用 " + (store.enabledCount() + memoryStore.enabledCount()) + " 條",
                rules.size() + " corrections · " + memoryStore.count() + " shortcuts · "
                        + (store.enabledCount() + memoryStore.enabledCount()) + " enabled"));
        summary.setTextSize(11);
        summary.setTextColor(CrewTheme.TEAL_300);
        summary.setPadding(0, 0, 0, dp(12));
        content.addView(summary);

        Button addMemoryRule = new Button(this);
        addMemoryRule.setText(I18n.get(this, "＋ 新增快捷指令", "+ Add Shortcut"));
        addMemoryRule.setAllCaps(false);
        addMemoryRule.setTextColor(CrewTheme.TEXT_PRIMARY);
        addMemoryRule.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_TEAL, 12));
        addMemoryRule.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAddMemoryRuleDialog(); }
        });
        LinearLayout.LayoutParams addRuleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        addRuleLp.setMargins(0, 0, 0, dp(12));
        content.addView(addMemoryRule, addRuleLp);

        List<MemoryRuleStore.Rule> memoryRules = memoryStore.list();
        if (rules.isEmpty() && memoryRules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(I18n.get(this,
                    "目前還沒有記錄。\n修正操作時按「打斷」並教它正確做法；建立規則時說「記住一條規則：以後我說『…』，就幫我『…』」。",
                    "No learned records yet.\nUse Interrupt to correct an action, or say: 'Remember a rule: when I say …, do …'."));
            empty.setTextSize(13);
            empty.setTextColor(CrewTheme.TEXT_SECONDARY);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.setBackground(CrewTheme.createCard(
                    this,
                    CrewTheme.BG_SURFACE,
                    CrewTheme.BORDER_SUBTLE,
                    12));
            content.addView(empty);
            return;
        }

        if (!memoryRules.isEmpty()) {
            addGroupTitle(I18n.get(this, "快捷指令", "SHORTCUTS"));
            for (final MemoryRuleStore.Rule rule : memoryRules) content.addView(buildMemoryRuleCard(rule));
        }
        if (!rules.isEmpty()) addGroupTitle(I18n.get(this, "單步修正", "CORRECTIONS"));
        for (final CorrectionRuleStore.Rule rule : rules) {
            content.addView(buildRuleCard(rule));
        }

        Button clear = new Button(this);
        clear.setText(I18n.get(this,
                "清除全部學習記錄",
                "Clear all learned corrections"));
        clear.setTextColor(Color.rgb(251, 113, 133));
        clear.setAllCaps(false);
        clear.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                12));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(
                        CorrectionRulesActivity.this)
                        .setTitle(I18n.get(
                                CorrectionRulesActivity.this,
                                "清除全部學習記錄？",
                                "Clear all learned corrections?"))
                        .setMessage(I18n.get(
                                CorrectionRulesActivity.this,
                                "此操作無法復原。",
                                "This cannot be undone."))
                        .setPositiveButton(
                                I18n.get(
                                        CorrectionRulesActivity.this,
                                        "清除",
                                        "Clear"),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(
                                            DialogInterface dialog,
                                            int which) {
                                        store.clearAll();
                                        memoryStore.clearAll();
                                        render();
                                    }
                                })
                        .setNegativeButton(
                                I18n.get(
                                        CorrectionRulesActivity.this,
                                        "取消",
                                        "Cancel"),
                                null)
                        .show();
            }
        });
        LinearLayout.LayoutParams clearLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48));
        clearLp.setMargins(0, dp(16), 0, 0);
        content.addView(clear, clearLp);
    }

    private void addGroupTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text); title.setTextSize(11); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(CrewTheme.TEAL_300); title.setPadding(0, dp(8), 0, dp(8));
        content.addView(title);
    }

    /** Direct creation is deterministic; voice phrasing remains an optional shortcut. */
    private void showAddMemoryRuleDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        form.setPadding(padding, dp(8), padding, 0);

        TextView note = new TextView(this);
        note.setText(I18n.get(this,
                "輸入你要說的觸發句，以及助理應執行的操作。規則不會保存密碼、OTP 或訊息內容。",
                "Enter the phrase you will say and the action the assistant should perform. Passwords, OTPs and message content are not stored."));
        note.setTextSize(12);
        note.setTextColor(CrewTheme.TEXT_SECONDARY);
        form.addView(note);

        final EditText trigger = new EditText(this);
        trigger.setHint(I18n.get(this, "例如：開 V App", "Example: Open V App"));
        trigger.setSingleLine(true);
        trigger.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(trigger, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText action = new EditText(this);
        action.setHint(I18n.get(this, "例如：開啟 WEAApp", "Example: Open WEAApp"));
        action.setMinLines(2);
        action.setGravity(Gravity.TOP | Gravity.START);
        action.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.get(this, "新增快捷指令", "Add Shortcut"))
                .setView(form)
                .setNegativeButton(I18n.get(this, "取消", "Cancel"), null)
                .setPositiveButton(I18n.get(this, "儲存快捷指令", "Save Shortcut"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                MemoryRuleStore.Rule saved = memoryStore.save(
                                        trigger.getText().toString(), action.getText().toString());
                                if (saved == null) {
                                    Toast.makeText(CorrectionRulesActivity.this,
                                            I18n.get(CorrectionRulesActivity.this,
                                                    "未儲存：請填寫觸發句與非敏感操作內容。",
                                                    "Not saved: enter a trigger and a non-sensitive action."),
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Toast.makeText(CorrectionRulesActivity.this,
                                        I18n.get(CorrectionRulesActivity.this,
                                                "快捷指令已儲存", "Shortcut saved"), Toast.LENGTH_SHORT).show();
                                render();
                            }
                        })
                .show();
    }

    private View buildMemoryRuleCard(final MemoryRuleStore.Rule rule) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(CrewTheme.createCard(this, CrewTheme.BG_SURFACE,
                rule.enabled ? CrewTheme.BORDER_TEAL : CrewTheme.BORDER_SUBTLE, 12));
        TextView trigger = new TextView(this);
        trigger.setText((rule.enabled ? "● 當我說「" : "○ 已停用：當我說「") + rule.trigger + "」");
        trigger.setTextColor(rule.enabled ? CrewTheme.TEXT_PRIMARY : CrewTheme.TEXT_MUTED);
        trigger.setTextSize(13); trigger.setTypeface(Typeface.DEFAULT_BOLD); card.addView(trigger);
        TextView action = new TextView(this);
        String usage = rule.triggerCount > 0
                ? "\n使用 " + rule.triggerCount + " 次 · " + rule.lastMatchMode : "";
        action.setText("→ " + rule.action + usage);
        action.setTextColor(CrewTheme.TEXT_SECONDARY); action.setTextSize(12); action.setPadding(0, dp(5), 0, 0); card.addView(action);
        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { showMemoryRuleDialog(rule); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10)); card.setLayoutParams(lp); return card;
    }

    private void showMemoryRuleDialog(final MemoryRuleStore.Rule rule) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.get(this, "快捷指令", "Shortcut"))
                .setMessage(I18n.get(this, "觸發：" + rule.trigger + "\n\n執行：" + rule.action,
                        "Trigger: " + rule.trigger + "\n\nAction: " + rule.action))
                .setPositiveButton(I18n.get(this, rule.enabled ? "停用" : "啟用", rule.enabled ? "Disable" : "Enable"),
                        new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { memoryStore.setEnabled(rule.id, !rule.enabled); render(); } })
                .setNegativeButton(I18n.get(this, "刪除", "Delete"),
                        new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { memoryStore.delete(rule.id); render(); } })
                .show();
    }

    private View buildRuleCard(final CorrectionRuleStore.Rule rule) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                rule.enabled
                        ? CrewTheme.BORDER_TEAL
                        : CrewTheme.BORDER_SUBTLE,
                12));

        TextView title = new TextView(this);
        title.setText((rule.enabled ? "● " : "○ ")
                + (rule.title.isEmpty()
                        ? "Correction Rule"
                        : rule.title));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(rule.enabled
                ? CrewTheme.TEXT_PRIMARY
                : CrewTheme.TEXT_MUTED);
        card.addView(title);

        TextView context = new TextView(this);
        context.setText(rule.packageName
                + " · screen="
                + CorrectionRuleStore.shortScreen(
                        rule.screenFingerprint));
        context.setTextSize(10);
        context.setTypeface(Typeface.MONOSPACE);
        context.setTextColor(CrewTheme.TEXT_MUTED);
        context.setPadding(0, dp(4), 0, dp(4));
        card.addView(context);

        TextView action = new TextView(this);
        action.setText(
                "✕ "
                        + CorrectionRuleStore.describeAction(
                                rule.wrongTool,
                                parse(rule.wrongArgs))
                        + "\n✓ "
                        + CorrectionRuleStore.describeAction(
                                rule.correctTool,
                                parse(rule.correctArgs)));
        action.setTextSize(12);
        action.setTextColor(CrewTheme.TEXT_SECONDARY);
        card.addView(action);

        TextView stats = new TextView(this);
        stats.setText(
                "confidence="
                        + Math.round(rule.confidence() * 100)
                        + "% · success="
                        + rule.successCount
                        + " · failure="
                        + rule.failureCount
                        + (rule.note.isEmpty()
                                ? ""
                                : "\n" + rule.note));
        stats.setTextSize(10);
        stats.setTextColor(CrewTheme.TEXT_MUTED);
        stats.setPadding(0, dp(5), 0, 0);
        card.addView(stats);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(rule);
            }
        });

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        return card;
    }

    private void showEditDialog(
            final CorrectionRuleStore.Rule rule) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(4));

        TextView match = new TextView(this);
        match.setText(
                "MATCH (read-only)\n"
                        + rule.packageName
                        + "\nscreen="
                        + rule.screenFingerprint
                        + "\n"
                        + rule.wrongTool
                        + " "
                        + rule.wrongArgs);
        match.setTextSize(10);
        match.setTypeface(Typeface.MONOSPACE);
        match.setTextColor(CrewTheme.TEXT_MUTED);
        match.setTextIsSelectable(true);
        match.setPadding(0, 0, 0, dp(10));
        box.addView(match);

        final EditText title =
                field("名稱", rule.title, false);
        final EditText note =
                field("備註 / SOP 說明", rule.note, true);
        final EditText tool =
                field("正確 tool", rule.correctTool, false);
        final EditText args =
                field("正確參數 JSON", rule.correctArgs, true);

        box.addView(title);
        box.addView(note);
        box.addView(tool);
        box.addView(args);

        new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.get(
                        this,
                        "編輯學習規則",
                        "Edit learned correction"))
                .setView(box)
                .setPositiveButton(
                        I18n.get(this, "儲存", "Save"),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialog,
                                    int which) {
                                boolean ok = store.updateRule(
                                        rule.id,
                                        title.getText().toString(),
                                        note.getText().toString(),
                                        tool.getText().toString(),
                                        args.getText().toString());
                                Toast.makeText(
                                        CorrectionRulesActivity.this,
                                        ok
                                                ? I18n.get(
                                                        CorrectionRulesActivity.this,
                                                        "已儲存",
                                                        "Saved")
                                                : I18n.get(
                                                        CorrectionRulesActivity.this,
                                                        "格式錯誤：請檢查 tool 與 JSON",
                                                        "Invalid tool or JSON"),
                                        Toast.LENGTH_SHORT)
                                        .show();
                                render();
                            }
                        })
                .setNeutralButton(
                        I18n.get(
                                this,
                                rule.enabled ? "停用" : "啟用",
                                rule.enabled ? "Disable" : "Enable"),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialog,
                                    int which) {
                                store.setEnabled(
                                        rule.id,
                                        !rule.enabled);
                                render();
                            }
                        })
                .setNegativeButton(
                        I18n.get(this, "刪除", "Delete"),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialog,
                                    int which) {
                                store.delete(rule.id);
                                render();
                            }
                        })
                .show();
    }

    private EditText field(
            String hint,
            String value,
            boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        input.setTextColor(CrewTheme.TEXT_PRIMARY);
        input.setHintTextColor(CrewTheme.TEXT_MUTED);
        input.setTextSize(12);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (multiline
                                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                : 0));
        if (multiline) {
            input.setMinLines(2);
            input.setMaxLines(5);
            input.setGravity(Gravity.TOP | Gravity.START);
        }
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        input.setLayoutParams(lp);
        return input;
    }

    private org.json.JSONObject parse(String raw) {
        try {
            return new org.json.JSONObject(
                    raw == null ? "{}" : raw);
        } catch (Exception ignored) {
            return new org.json.JSONObject();
        }
    }
}
