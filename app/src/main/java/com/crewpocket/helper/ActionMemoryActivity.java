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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;

/** User-facing list/editor for App Action Memory mappings. */
public class ActionMemoryActivity extends Activity {
    private LearnedUiMappingStore store;
    private LinearLayout content;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new LearnedUiMappingStore(this);

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
        icon.setIcon(CrewIcons.PHONE_ACTIONS, CrewTheme.TEAL_300);
        icon.setIconScale(0.62f);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8), 0, 0, 0);
        titles.addView(text(
                I18n.get(this, "App Action Memory", "App Action Memory"),
                22, CrewTheme.TEXT_PRIMARY, true));
        titles.addView(text(
                I18n.get(this,
                        "管理 Crew 學會的 UI 控制 mapping",
                        "Manage UI-control mappings learned by Crew"),
                11, CrewTheme.TEXT_SECONDARY, false));
        heading.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(heading);

        TextView intro = text(
                I18n.get(this,
                        "可以改自訂名稱、停用/啟用或刪除 mapping。Runtime role 與 Selector 結構保持唯讀，避免手動修改後指到錯的 UI。重複整理只會合併同 App、同 role、同結構 selector 的 mapping。",
                        "You can edit a custom name, enable/disable, or delete a mapping. Runtime roles and structural selectors stay read-only to avoid pointing at the wrong UI. Duplicate cleanup only merges mappings with the same app, role, and structural selector."),
                11, CrewTheme.TEXT_SECONDARY, false);
        intro.setLineSpacing(dp(2), 1.08f);
        intro.setPadding(0, dp(14), 0, dp(12));
        content.addView(intro);

        JSONArray rules = store.dumpForDebug("");
        int active = 0;
        int disabled = 0;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            if (rule.optBoolean("enabled", true)) active++;
            else disabled++;
        }

        TextView stats = text(
                I18n.get(this,
                        rules.length() + " 條 · " + active + " 有效"
                                + (disabled > 0 ? " · " + disabled + " 停用" : ""),
                        rules.length() + " mappings · " + active + " active"
                                + (disabled > 0 ? " · " + disabled + " disabled" : "")),
                10.5f, CrewTheme.TEAL_300, true);
        stats.setPadding(dp(12), dp(9), dp(12), dp(9));
        stats.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));
        content.addView(stats);

        int duplicates = store.exactDuplicateCount();
        if (duplicates > 0) {
            Button cleanup = new Button(this);
            cleanup.setAllCaps(false);
            cleanup.setText(I18n.get(this,
                    "整理重複項 · " + duplicates + " 條",
                    "Clean duplicates · " + duplicates));
            cleanup.setTextSize(12);
            cleanup.setTypeface(Typeface.DEFAULT_BOLD);
            cleanup.setTextColor(Color.WHITE);
            cleanup.setBackground(CrewTheme.createGradientButton(
                    this, CrewTheme.TEAL_500, CrewTheme.INDIGO_500, 12));
            cleanup.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(I18n.get(this,
                            "整理重複 Action Memory？",
                            "Clean duplicate Action Memory?"))
                    .setMessage(I18n.get(this,
                            "只會合併結構完全相同的 mapping，成功/失敗次數會合併保留。",
                            "Only structurally identical mappings are merged. Success/failure evidence is preserved."))
                    .setPositiveButton(I18n.get(this, "整理", "Clean"),
                            (d, which) -> {
                                int removed = store.deduplicateExact();
                                Toast.makeText(this,
                                        I18n.get(this,
                                                "已整理 " + removed + " 條重複 mapping",
                                                "Removed " + removed + " duplicate mappings"),
                                        Toast.LENGTH_SHORT).show();
                                render();
                            })
                    .setNegativeButton(I18n.get(this, "取消", "Cancel"), null)
                    .show());
            LinearLayout.LayoutParams cleanupLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            cleanupLp.setMargins(0, dp(10), 0, dp(14));
            content.addView(cleanup, cleanupLp);
        }

        if (rules.length() == 0) {
            content.addView(emptyCard(I18n.get(this,
                    "目前沒有 Action Memory mapping。",
                    "No Action Memory mappings yet.")), cardParams());
            return;
        }

        HashMap<String, Integer> counts = duplicateCounts(rules);
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            String key = duplicateKey(rule);
            int similar = counts.containsKey(key) ? counts.get(key) : 1;
            content.addView(mappingCard(rule, similar), cardParams());
        }
    }

    private LinearLayout mappingCard(final JSONObject rule, int duplicateCount) {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        String runtimeRole = rule.optString("role", "");
        String userLabel = rule.optString("userLabel", "").trim();
        String titleText = userLabel.isEmpty()
                ? friendlyRole(runtimeRole)
                : userLabel;
        top.addView(text(titleText, 13, CrewTheme.TEXT_PRIMARY, true),
                new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean enabled = rule.optBoolean("enabled", true);
        TextView status = text(
                enabled ? I18n.get(this, "有效", "ACTIVE")
                        : I18n.get(this, "停用", "DISABLED"),
                9, enabled ? CrewTheme.EMERALD_400 : CrewTheme.TEXT_MUTED, true);
        status.setPadding(dp(8), dp(3), dp(8), dp(3));
        status.setBackground(CrewTheme.createCard(
                this,
                enabled ? Color.argb(36, 16, 185, 129) : Color.parseColor("#29292B"),
                enabled ? CrewTheme.BORDER_TEAL : CrewTheme.BORDER_SUBTLE,
                10));
        top.addView(status);
        card.addView(top);

        if (!userLabel.isEmpty()) {
            TextView roleLine = text(
                    I18n.get(this, "Runtime role: ", "Runtime role: ")
                            + friendlyRole(runtimeRole),
                    9.5f, CrewTheme.TEXT_MUTED, false);
            roleLine.setPadding(0, dp(3), 0, 0);
            card.addView(roleLine);
        }

        String pkg = rule.optString("packageName", "");
        String app = AppRuntimeRegistry.displayName(this, pkg);
        String appLine = app == null || app.trim().isEmpty() || app.equals(pkg)
                ? pkg : app + " · " + pkg;
        card.addView(text(appLine, 10, CrewTheme.TEXT_SECONDARY, false));

        if (duplicateCount > 1) {
            TextView duplicate = text(
                    I18n.get(this,
                            "重複結構 × " + duplicateCount,
                            "Same structure × " + duplicateCount),
                    9.5f, CrewTheme.ROSE_400, true);
            duplicate.setPadding(0, dp(5), 0, 0);
            card.addView(duplicate);
        }

        String selector = selectorSummary(rule);
        if (!selector.isEmpty()) {
            TextView selectorView = text(selector, 9.5f, CrewTheme.TEXT_MUTED, false);
            selectorView.setPadding(0, dp(5), 0, 0);
            card.addView(selectorView);
        }

        int success = rule.optInt("successCount", 0);
        int failure = rule.optInt("failureCount", 0);
        long verifiedAt = rule.optLong("lastVerifiedAt", 0L);
        String meta = I18n.get(this, "成功 ", "Verified ")
                + success + " · "
                + I18n.get(this, "失敗 ", "Failed ") + failure;
        if (verifiedAt > 0L) meta += " · " + formatTime(verifiedAt);

        TextView metaView = text(meta, 9.5f, CrewTheme.TEXT_MUTED, false);
        metaView.setPadding(0, dp(5), 0, 0);
        card.addView(metaView);

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showEditor(rule));
        return card;
    }

    private void showEditor(final JSONObject rule) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView runtimeRole = text(
                I18n.get(this, "Runtime role（唯讀）: ", "Runtime role (read-only): ")
                        + friendlyRole(rule.optString("role", "")),
                10.5f, CrewTheme.TEXT_SECONDARY, false);
        runtimeRole.setPadding(dp(11), dp(9), dp(11), dp(9));
        runtimeRole.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        root.addView(runtimeRole);

        final EditText label = new EditText(this);
        label.setText(rule.optString("userLabel", ""));
        label.setHint(I18n.get(this,
                "自訂名稱（選填，例如：LINE 送出按鈕）",
                "Custom name (optional, e.g. LINE send button)"));
        label.setTextSize(12);
        label.setTextColor(CrewTheme.TEXT_PRIMARY);
        label.setHintTextColor(CrewTheme.TEXT_MUTED);
        label.setSingleLine(true);
        label.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        label.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        label.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, dp(8), 0, 0);
        root.addView(label, labelLp);

        final CheckBox enabled = new CheckBox(this);
        enabled.setText(I18n.get(this, "啟用這條 mapping", "Enable this mapping"));
        enabled.setChecked(rule.optBoolean("enabled", true));
        enabled.setTextColor(CrewTheme.TEXT_PRIMARY);
        LinearLayout.LayoutParams enabledLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        enabledLp.setMargins(0, dp(8), 0, 0);
        root.addView(enabled, enabledLp);

        TextView selector = text(
                selectorDetails(rule),
                10, CrewTheme.TEXT_SECONDARY, false);
        selector.setPadding(dp(11), dp(9), dp(11), dp(9));
        selector.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        LinearLayout.LayoutParams selectorLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        selectorLp.setMargins(0, dp(10), 0, 0);
        root.addView(selector, selectorLp);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        "編輯 App Action Memory",
                        "Edit App Action Memory"))
                .setView(root)
                .setPositiveButton(I18n.get(this, "儲存", "Save"), null)
                .setNeutralButton(I18n.get(this, "刪除", "Delete"), null)
                .setNegativeButton(I18n.get(this, "取消", "Cancel"), null)
                .create();

        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        JSONObject result = store.updateRule(
                                rule.optString("id", ""),
                                label.getText().toString(),
                                enabled.isChecked());
                        if (!result.optBoolean("success", false)) {
                            Toast.makeText(this,
                                    result.optString("error", "Save failed"),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        dialog.dismiss();
                        render();
                    });

            Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (delete != null) {
                delete.setTextColor(CrewTheme.ROSE_400);
                delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                "刪除這條 Action Memory？",
                                "Delete this Action Memory?"))
                        .setMessage(I18n.get(this,
                                "刪除後 Crew 不會再使用這條 mapping；需要時可以重新教一次。",
                                "Crew will stop using this mapping. You can teach it again later if needed."))
                        .setPositiveButton(I18n.get(this, "刪除", "Delete"),
                                (d, which) -> {
                                    boolean removed = store.deleteRule(
                                            rule.optString("id", ""));
                                    if (!removed) {
                                        Toast.makeText(this,
                                                "ACTION_MEMORY_NOT_FOUND",
                                                Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    dialog.dismiss();
                                    render();
                                })
                        .setNegativeButton(I18n.get(this, "取消", "Cancel"), null)
                        .show());
            }
        });

        dialog.show();
    }

    private HashMap<String, Integer> duplicateCounts(JSONArray rules) {
        HashMap<String, Integer> out = new HashMap<String, Integer>();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            String key = duplicateKey(rule);
            out.put(key, (out.containsKey(key) ? out.get(key) : 0) + 1);
        }
        return out;
    }

    private String duplicateKey(JSONObject rule) {
        if (rule == null) return "";
        String key = rule.optString("packageName", "") + "|"
                + rule.optString("role", "") + "|"
                + rule.optString("viewId", "") + "|"
                + rule.optString("className", "") + "|"
                + rule.optString("contentDescription", "") + "|"
                + rule.optString("parentClassName", "") + "|"
                + rule.optString("relativePosition", "") + "|"
                + rule.optString("composerState", "") + "|"
                + rule.optString("composerClassName", "") + "|"
                + rule.optString("composerViewId", "") + "|"
                + rule.optString("anchorViewId", "") + "|"
                + rule.optString("anchorClassName", "") + "|"
                + rule.optString("anchorContentDescription", "") + "|"
                + rule.optBoolean("anchored", false) + "|"
                + rule.optInt("targetOffsetX", 0) + "|"
                + rule.optInt("targetOffsetY", 0);

        boolean hasStableSelector = !rule.optString("viewId", "").isEmpty()
                || !rule.optString("contentDescription", "").isEmpty()
                || !rule.optString("anchorViewId", "").isEmpty()
                || (!rule.optString("className", "").isEmpty()
                    && !rule.optString("parentClassName", "").isEmpty()
                    && !rule.optString("relativePosition", "").isEmpty());
        if (!hasStableSelector) {
            key += "|xy=" + rule.optInt("centerX", -1)
                    + "," + rule.optInt("centerY", -1);
        }
        return key;
    }

    private String selectorSummary(JSONObject rule) {
        String viewId = shortValue(rule.optString("viewId", ""));
        String desc = shortValue(rule.optString("contentDescription", ""));
        String cls = shortClass(rule.optString("className", ""));
        String composer = rule.optString("composerState", "");
        StringBuilder out = new StringBuilder();
        if (!viewId.isEmpty()) out.append("id=").append(viewId);
        else if (!desc.isEmpty()) out.append("desc=").append(desc);
        else if (!cls.isEmpty()) out.append("class=").append(cls);
        if (!composer.isEmpty()) {
            if (out.length() > 0) out.append(" · ");
            out.append("composer=").append(composer);
        }
        if (rule.optBoolean("anchored", false)) {
            if (out.length() > 0) out.append(" · ");
            out.append("anchored");
        }
        return out.toString();
    }

    private String selectorDetails(JSONObject rule) {
        StringBuilder out = new StringBuilder();
        out.append(I18n.get(this, "Selector（唯讀）", "Selector (read-only)"));
        appendLine(out, "viewId", rule.optString("viewId", ""));
        appendLine(out, "class", shortClass(rule.optString("className", "")));
        appendLine(out, "description", rule.optString("contentDescription", ""));
        appendLine(out, "parent", shortClass(rule.optString("parentClassName", "")));
        appendLine(out, "relative", rule.optString("relativePosition", ""));
        appendLine(out, "composer", rule.optString("composerState", ""));
        appendLine(out, "composerViewId", rule.optString("composerViewId", ""));
        appendLine(out, "stableScreen", shortValue(rule.optString("stableScreenKey", "")));
        if (rule.optBoolean("anchored", false)) {
            appendLine(out, "anchorViewId", rule.optString("anchorViewId", ""));
            appendLine(out, "offset",
                    rule.optInt("targetOffsetX", 0) + ","
                            + rule.optInt("targetOffsetY", 0));
        }
        return out.toString();
    }

    private void appendLine(StringBuilder out, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        out.append("\n").append(key).append(": ").append(value.trim());
    }

    private String shortClass(String value) {
        if (value == null) return "";
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length()
                ? value.substring(dot + 1) : value;
    }

    private String shortValue(String value) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= 70 ? text : text.substring(0, 67) + "...";
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

    private LinearLayout emptyCard(String message) {
        LinearLayout card = card();
        card.addView(text(message, 11, CrewTheme.TEXT_SECONDARY, false));
        return card;
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
