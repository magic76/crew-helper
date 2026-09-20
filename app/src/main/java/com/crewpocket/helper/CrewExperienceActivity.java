package com.crewpocket.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** User-facing list/editor for learned Crew Experience lessons. */
public class CrewExperienceActivity extends Activity {
    private ReflectionLessonStore store;
    private LinearLayout content;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ReflectionLessonStore(this);

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

        TextView back = text("‹ " + I18n.get(this, "返回 Crew 已學會", "Back to Crew Learned"),
                13, CrewTheme.INDIGO_400, true);
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
        titles.addView(text("Crew Experience", 22, CrewTheme.TEXT_PRIMARY, true));
        titles.addView(text(
                I18n.get(this,
                        "從摩擦與修正中學到的可重用 lesson",
                        "Reusable lessons learned from friction and correction"),
                11, CrewTheme.TEXT_SECONDARY, false));
        heading.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(heading);

        TextView intro = text(
                I18n.get(this,
                        "點一條 Experience 可以編輯 Crew 最終記住的 lesson 或刪除它。條件與 evidence 保持唯讀，避免人工修改後和實際證據失去對應。已升級到 App Playbook 的 Experience 會同步更新或刪除對應規則。",
                        "Tap an Experience to edit the final lesson or delete it. Conditions and evidence stay read-only so they remain grounded. Promoted App Playbook guidance is updated or removed together."),
                11, CrewTheme.TEXT_SECONDARY, false);
        intro.setLineSpacing(dp(2), 1.08f);
        intro.setPadding(0, dp(14), 0, dp(12));
        content.addView(intro);

        JSONArray items = store.list();
        int candidate = 0;
        int verified = 0;
        int suspect = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String state = item.optString("state", ReflectionLessonStore.STATE_CANDIDATE);
            if (ReflectionLessonStore.STATE_VERIFIED.equals(state)) verified++;
            else if (ReflectionLessonStore.STATE_SUSPECT.equals(state)) suspect++;
            else candidate++;
        }

        TextView stats = text(
                I18n.get(this,
                        items.length() + " 條 · " + verified + " 已驗證 · "
                                + candidate + " 候選"
                                + (suspect > 0 ? " · " + suspect + " 待確認" : ""),
                        items.length() + " lessons · " + verified + " verified · "
                                + candidate + " candidates"
                                + (suspect > 0 ? " · " + suspect + " suspect" : "")),
                10.5f, CrewTheme.TEAL_300, true);
        stats.setPadding(dp(12), dp(9), dp(12), dp(9));
        stats.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));
        content.addView(stats);

        TextView evidenceTitle = text(
                I18n.get(this, "學習觸發狀態", "LEARNING EVIDENCE"),
                10.5f, CrewTheme.INDIGO_400, true);
        evidenceTitle.setPadding(dp(4), dp(14), 0, dp(6));
        content.addView(evidenceTitle);

        TextView evidence = text(
                ExperienceEvidenceStore.buildReport(this),
                9.5f, CrewTheme.TEXT_MUTED, false);
        evidence.setTypeface(Typeface.MONOSPACE);
        evidence.setPadding(dp(12), dp(10), dp(12), dp(10));
        evidence.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));
        content.addView(evidence);

        TextView section = text(
                I18n.get(this, "已學到的 Experience", "LEARNED EXPERIENCE"),
                10.5f, CrewTheme.INDIGO_400, true);
        section.setPadding(dp(4), dp(18), 0, dp(8));
        content.addView(section);

        if (items.length() == 0) {
            LinearLayout empty = card();
            empty.addView(text(
                    I18n.get(this,
                            "目前還沒有 learned Experience。只有明顯摩擦或重複出現的中度摩擦才會進入學習。",
                            "No learned Experience yet. Only strong friction or repeated medium friction enters learning."),
                    11, CrewTheme.TEXT_SECONDARY, false));
            content.addView(empty, cardParams());
            return;
        }

        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) content.addView(experienceCard(item), cardParams());
        }
    }

    private View experienceCard(final JSONObject item) {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        String app = item.optString("app", "");
        String pkg = item.optString("package", "");
        if (app.trim().isEmpty()) app = pkg;
        TextView title = text(app, 13, CrewTheme.TEXT_PRIMARY, true);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String state = item.optString("state", ReflectionLessonStore.STATE_CANDIDATE);
        int statusColor = ReflectionLessonStore.STATE_VERIFIED.equals(state)
                ? CrewTheme.EMERALD_400
                : ReflectionLessonStore.STATE_SUSPECT.equals(state)
                        ? CrewTheme.ROSE_400 : CrewTheme.INDIGO_400;
        TextView chip = text(friendlyState(state), 9, statusColor, true);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setBackground(CrewTheme.createCard(
                this, Color.argb(28, 255, 255, 255), CrewTheme.BORDER_SUBTLE, 10));
        top.addView(chip);
        card.addView(top);

        if (!pkg.isEmpty() && !pkg.equals(app)) {
            TextView packageText = text(pkg, 9, CrewTheme.TEXT_MUTED, false);
            packageText.setPadding(0, dp(2), 0, 0);
            card.addView(packageText);
        }

        String scope = item.optString("scope", "");
        String condition = item.optString("condition", "");
        String response = item.optString("response", "");
        TextView rule = text(
                scope + (condition.isEmpty() ? "" : " · " + condition)
                        + (response.isEmpty() ? "" : " → " + response),
                10, CrewTheme.TEXT_SECONDARY, false);
        rule.setPadding(0, dp(7), 0, 0);
        card.addView(rule);

        TextView lesson = text(item.optString("lesson", ""),
                11.5f, CrewTheme.TEXT_PRIMARY, false);
        lesson.setPadding(0, dp(7), 0, dp(4));
        card.addView(lesson);

        int confirmations = item.optInt("confirmations", 0);
        double confidence = item.optDouble("avgConfidence", 0d);
        String meta = I18n.get(this, "確認 ", "Confirmations ")
                + confirmations + "/" + ReflectionLearningPolicy.CONFIRMATIONS_TO_VERIFY
                + " · " + I18n.get(this, "信心 ", "confidence ")
                + String.format(Locale.US, "%.2f", confidence);
        if (item.optBoolean("manualOverride", false)) {
            meta += " · " + I18n.get(this, "已手動編輯", "manually edited");
        }
        long updatedAt = item.optLong("updatedAt", 0L);
        if (updatedAt > 0L) meta += " · " + formatTime(updatedAt);

        card.addView(text(meta, 9.5f, CrewTheme.TEXT_MUTED, false));

        if (!item.optString("playbookRuleId", "").isEmpty()) {
            TextView promoted = text(
                    I18n.get(this, "✓ 已同步到 App Playbook", "✓ Synced to App Playbook"),
                    9.5f, CrewTheme.TEAL_300, true);
            promoted.setPadding(0, dp(5), 0, 0);
            card.addView(promoted);
        }

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showEditor(item));
        return card;
    }

    private void showEditor(final JSONObject item) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView context = text(
                item.optString("scope", "")
                        + "\n"
                        + I18n.get(this, "條件：", "When: ")
                        + item.optString("condition", "")
                        + "\n"
                        + I18n.get(this, "建議動作：", "Response: ")
                        + item.optString("response", ""),
                10.5f, CrewTheme.TEXT_SECONDARY, false);
        context.setPadding(dp(11), dp(9), dp(11), dp(9));
        context.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        root.addView(context);

        final EditText lesson = new EditText(this);
        lesson.setText(item.optString("lesson", ""));
        lesson.setHint(I18n.get(this,
                "Crew 應該記住的 lesson",
                "Lesson Crew should remember"));
        lesson.setTextSize(12);
        lesson.setTextColor(CrewTheme.TEXT_PRIMARY);
        lesson.setHintTextColor(CrewTheme.TEXT_MUTED);
        lesson.setGravity(Gravity.TOP | Gravity.START);
        lesson.setMinLines(4);
        lesson.setMaxLines(8);
        lesson.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        lesson.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        lesson.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams lessonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lessonLp.setMargins(0, dp(10), 0, 0);
        root.addView(lesson, lessonLp);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, "編輯 Crew Experience", "Edit Crew Experience"))
                .setView(root)
                .setPositiveButton(I18n.get(this, "儲存", "Save"), null)
                .setNeutralButton(I18n.get(this, "刪除", "Delete"), null)
                .setNegativeButton(I18n.get(this, "取消", "Cancel"), null)
                .create();

        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String value = lesson.getText().toString().trim();
                        if (value.isEmpty()) {
                            lesson.setError(I18n.get(
                                    CrewExperienceActivity.this,
                                    "請輸入 lesson",
                                    "Enter a lesson"));
                            return;
                        }
                        JSONObject result = store.updateLesson(
                                item.optString("id", ""), value);
                        if (!result.optBoolean("success", false)) {
                            Toast.makeText(
                                    CrewExperienceActivity.this,
                                    friendlyError(result.optString("error", "")),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        dialog.dismiss();
                        render();
                    });

            Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (delete != null) {
                delete.setTextColor(CrewTheme.ROSE_400);
                delete.setOnClickListener(v -> new AlertDialog.Builder(
                        CrewExperienceActivity.this)
                        .setTitle(I18n.get(
                                CrewExperienceActivity.this,
                                "刪除這條 Experience？",
                                "Delete this Experience?"))
                        .setMessage(I18n.get(
                                CrewExperienceActivity.this,
                                "如果它已升級到 App Playbook，對應的 Playbook 規則也會一起刪除。",
                                "If it was promoted, the linked App Playbook rule will also be removed."))
                        .setPositiveButton(I18n.get(
                                        CrewExperienceActivity.this,
                                        "刪除", "Delete"),
                                (d, which) -> {
                                    JSONObject result = store.deleteLesson(
                                            item.optString("id", ""));
                                    if (!result.optBoolean("success", false)) {
                                        Toast.makeText(
                                                CrewExperienceActivity.this,
                                                friendlyError(result.optString("error", "")),
                                                Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    dialog.dismiss();
                                    render();
                                })
                        .setNegativeButton(I18n.get(
                                CrewExperienceActivity.this,
                                "取消", "Cancel"), null)
                        .show());
            }
        });
        dialog.show();
    }

    private String friendlyState(String state) {
        if (ReflectionLessonStore.STATE_VERIFIED.equals(state)) {
            return I18n.get(this, "已驗證", "VERIFIED");
        }
        if (ReflectionLessonStore.STATE_SUSPECT.equals(state)) {
            return I18n.get(this, "待確認", "SUSPECT");
        }
        return I18n.get(this, "候選", "CANDIDATE");
    }

    private String friendlyError(String error) {
        if ("EXPERIENCE_POLICY_REJECTED".equals(error)) {
            return I18n.get(this,
                    "這段內容不符合 Experience 安全規則",
                    "This lesson does not pass Experience safety rules");
        }
        return error == null || error.trim().isEmpty()
                ? I18n.get(this, "操作失敗", "Operation failed")
                : error;
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

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
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
