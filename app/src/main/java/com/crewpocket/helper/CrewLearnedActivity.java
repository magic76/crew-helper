package com.crewpocket.helper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * One user-facing place to understand what Crew has learned.
 *
 * The underlying systems remain deliberately separate:
 * - App Action Memory = HOW to operate a concrete UI control.
 * - App Playbooks = app-local operating guidance.
 * - Crew Experience = deterministic evidence that may qualify for reflection.
 */
public class CrewLearnedActivity extends Activity {
    private LinearLayout content;
    private LearnedUiMappingStore actionMemory;
    private AppPlaybookStore playbooks;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        actionMemory = new LearnedUiMappingStore(this);
        playbooks = new AppPlaybookStore(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(26), dp(20), dp(32));
        scroll.addView(content);

        setContentView(scroll);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) render();
    }

    private void render() {
        content.removeAllViews();

        TextView back = text("‹ " + I18n.get(this, "返回設定", "Back to Settings"), 13,
                CrewTheme.INDIGO_400, true);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> finish());
        content.addView(back);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        CrewIconView icon = new CrewIconView(this);
        icon.setIcon(CrewIcons.BRAIN, CrewTheme.TEAL_300);
        icon.setIconScale(0.66f);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8), 0, 0, 0);
        titles.addView(text(I18n.get(this, "Crew 已學會", "Crew Learned"), 22,
                CrewTheme.TEXT_PRIMARY, true));
        titles.addView(text(
                I18n.get(this,
                        "看得懂 Crew 記住了什麼，以及目前是否仍有效",
                        "See what Crew remembers and whether it is still valid"),
                11, CrewTheme.TEXT_SECONDARY, false));
        heading.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(heading);

        TextView intro = text(
                I18n.get(this,
                        "這裡把不同學習機制放在同一個地方看，但底層仍分開，避免一種記憶誤當成另一種權限。",
                        "This page unifies visibility, while the underlying memory systems remain separate so learned behavior never becomes authorization."),
                11, CrewTheme.TEXT_SECONDARY, false);
        intro.setLineSpacing(dp(2), 1.08f);
        intro.setPadding(0, dp(14), 0, dp(14));
        content.addView(intro);

        renderActionMemory();
        renderPlaybooks();
        renderExperience();
    }

    private void renderActionMemory() {
        JSONArray rules = actionMemory.dumpForDebug("");
        int active = 0;
        int disabled = 0;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            if (rule.optBoolean("enabled", true)) active++;
            else disabled++;
        }

        addSectionHeader(
                I18n.get(this, "App Action Memory", "App Action Memory"),
                I18n.get(this,
                        "HOW · Crew 實際知道哪個 UI 控制怎麼操作",
                        "HOW · concrete UI controls Crew knows how to operate"),
                active + " " + I18n.get(this, "有效", "active")
                        + (disabled > 0
                                ? " · " + disabled + " " + I18n.get(this, "停用", "disabled")
                                : ""));

        if (rules.length() == 0) {
            content.addView(emptyCard(I18n.get(
                    this,
                    "還沒有教過 UI 控制。你可以在 App 裡用泡泡的「教 Crew」功能。",
                    "No UI controls have been taught yet. Use Teach Crew from the bubble inside an app.")));
            return;
        }

        int shown = Math.min(12, rules.length());
        for (int i = 0; i < shown; i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;

            String pkg = rule.optString("packageName", "");
            String role = friendlyRole(rule.optString("role", ""));
            boolean enabled = rule.optBoolean("enabled", true);
            int successes = rule.optInt("successCount", 0);
            int failures = rule.optInt("failureCount", 0);
            long verifiedAt = rule.optLong("lastVerifiedAt", 0L);

            LinearLayout card = card();
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = text(role, 13, CrewTheme.TEXT_PRIMARY, true);
            top.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView status = text(
                    enabled ? I18n.get(this, "有效", "ACTIVE")
                            : I18n.get(this, "停用", "DISABLED"),
                    9.5f,
                    enabled ? CrewTheme.EMERALD_400 : CrewTheme.TEXT_MUTED,
                    true);
            status.setPadding(dp(8), dp(3), dp(8), dp(3));
            status.setBackground(CrewTheme.createCard(
                    this,
                    enabled ? Color.argb(38, 16, 185, 129) : Color.parseColor("#29292B"),
                    enabled ? CrewTheme.BORDER_TEAL : CrewTheme.BORDER_SUBTLE,
                    10));
            top.addView(status);
            card.addView(top);

            String appLabel = AppRuntimeRegistry.displayName(this, pkg);
            if (appLabel == null || appLabel.trim().isEmpty()
                    || appLabel.equals(pkg)) {
                appLabel = pkg;
            } else {
                appLabel = appLabel + " · " + pkg;
            }
            card.addView(text(
                    appLabel,
                    10,
                    CrewTheme.TEXT_SECONDARY,
                    false));

            String detail = I18n.get(this, "成功 ", "Verified ")
                    + successes
                    + " · "
                    + I18n.get(this, "失敗 ", "Failed ")
                    + failures;
            if (verifiedAt > 0L) {
                detail += " · " + formatTime(verifiedAt);
            }
            TextView details = text(detail, 10, CrewTheme.TEXT_MUTED, false);
            details.setPadding(0, dp(5), 0, 0);
            card.addView(details);

            content.addView(card, cardParams());
        }

        if (rules.length() > shown) {
            TextView more = text(
                    I18n.get(this,
                            "另有 " + (rules.length() - shown) + " 條較舊 mapping",
                            (rules.length() - shown) + " older mappings"),
                    10, CrewTheme.TEXT_MUTED, false);
            more.setPadding(dp(4), 0, 0, dp(8));
            content.addView(more);
        }
    }

    private void renderPlaybooks() {
        int ruleCount = playbooks.learnedRuleCount();
        int appCount = playbooks.learnedAppCount();

        addSectionHeader(
                I18n.get(this, "App Playbooks", "App Playbooks"),
                I18n.get(this,
                        "GUIDANCE · 每個 App 的操作習慣與規則",
                        "GUIDANCE · app-local operating guidance"),
                appCount + " " + I18n.get(this, "個 App", "apps")
                        + " · " + ruleCount + " "
                        + I18n.get(this, "條規則", "rules"));

        LinearLayout card = card();
        card.addView(text(
                ruleCount == 0
                        ? I18n.get(this,
                                "目前還沒有自訂 App 經驗。",
                                "No custom App Playbooks yet.")
                        : I18n.get(this,
                                "這些是提示，不會自己取得送出、付款或敏感操作權限。",
                                "These are guidance only and never grant send, payment, or sensitive-action authorization."),
                11, CrewTheme.TEXT_SECONDARY, false));

        Button manage = new Button(this);
        manage.setAllCaps(false);
        manage.setText(I18n.get(this, "管理 App Playbooks", "Manage App Playbooks"));
        manage.setTextColor(Color.WHITE);
        manage.setTextSize(12);
        manage.setTypeface(Typeface.DEFAULT_BOLD);
        manage.setBackground(CrewTheme.createGradientButton(
                this, CrewTheme.TEAL_500, CrewTheme.INDIGO_500, 12));
        manage.setOnClickListener(v -> startActivity(
                new Intent(CrewLearnedActivity.this, AppPlaybookActivity.class)));
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        buttonLp.topMargin = dp(10);
        card.addView(manage, buttonLp);

        content.addView(card, cardParams());
    }

    private void renderExperience() {
        addSectionHeader(
                I18n.get(this, "Crew Experience", "Crew Experience"),
                I18n.get(this,
                        "EVIDENCE · 成功模式與修正證據",
                        "EVIDENCE · repeated success and recovery evidence"),
                "");

        LinearLayout card = card();
        TextView report = text(
                ExperienceEvidenceStore.buildReport(this),
                10.5f, CrewTheme.TEXT_SECONDARY, false);
        report.setTypeface(Typeface.MONOSPACE);
        report.setLineSpacing(dp(2), 1.08f);
        card.addView(report);

        TextView note = text(
                I18n.get(this,
                        "一般成功不會直接建立 Experience；只有符合 recovery 或重複成功門檻才會進入 review。",
                        "Ordinary successes stay quiet; only qualified recovery or repeated-success evidence enters review."),
                10, CrewTheme.TEXT_MUTED, false);
        note.setPadding(0, dp(9), 0, 0);
        card.addView(note);

        content.addView(card, cardParams());
    }

    private void addSectionHeader(String title, String subtitle, String badge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(8));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        titleRow.addView(text(title, 14, CrewTheme.TEXT_PRIMARY, true),
                new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (badge != null && !badge.isEmpty()) {
            titleRow.addView(text(badge, 9.5f, CrewTheme.TEAL_300, true));
        }
        row.addView(titleRow);
        row.addView(text(subtitle, 10, CrewTheme.TEXT_SECONDARY, false));
        content.addView(row);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 14));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        return lp;
    }

    private View emptyCard(String message) {
        LinearLayout card = card();
        card.addView(text(message, 11, CrewTheme.TEXT_SECONDARY, false));
        return card;
    }

    private TextView text(
            String value,
            float size,
            int color,
            boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private String friendlyRole(String role) {
        if ("COMPOSER_SEND".equalsIgnoreCase(role)) {
            return I18n.get(this, "送出按鈕", "Send button");
        }
        if (role == null || role.trim().isEmpty()) {
            return I18n.get(this, "UI 控制", "UI control");
        }
        return role.replace('_', ' ');
    }

    private String formatTime(long timestamp) {
        try {
            return DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT).format(new Date(timestamp));
        } catch (Exception ignored) {
            return "";
        }
    }
}
