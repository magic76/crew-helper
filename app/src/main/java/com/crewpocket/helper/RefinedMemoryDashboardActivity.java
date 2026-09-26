package com.crewpocket.helper;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * User-facing observability for Refined Memory.
 *
 * Shows structured memory, causal use, terminal verification, correction, and
 * same-scope impact without exposing raw user text, queries, screenshots,
 * coordinates, credentials, or arbitrary tool arguments.
 */
public class RefinedMemoryDashboardActivity extends Activity {
    private LinearLayout content;
    private RefinedMemoryStore store;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new RefinedMemoryStore(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(32));
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

        TextView back = text(
                "‹ " + I18n.get(this, "返回", "Back"),
                13,
                CrewTheme.INDIGO_400,
                true);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> finish());
        content.addView(back);

        content.addView(text(
                I18n.get(this, "Memory Dashboard", "Memory Dashboard"),
                22,
                CrewTheme.TEXT_PRIMARY,
                true));
        TextView subtitle = text(
                I18n.get(this,
                        "確認 Crew 記住了什麼、這次有沒有用、以及是否真的改善任務",
                        "Verify what Crew remembers, whether it was used, and whether it actually improved tasks"),
                11,
                CrewTheme.TEXT_SECONDARY,
                false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        content.addView(subtitle);

        renderOverview();
        renderMemories();
        renderEvents();
    }

    private void renderOverview() {
        JSONArray items = store.dumpForDebug();
        JSONObject summary = store.dashboardSummary();
        int usable = store.injectableCount();

        section(
                I18n.get(this, "總覽", "Overview"),
                usable + " "
                        + I18n.get(this, "可使用", "usable")
                        + " · "
                        + items.length()
                        + " "
                        + I18n.get(this, "總計", "total"));

        LinearLayout card = card();
        card.addView(text(
                I18n.get(this,
                        "Memory 是結構化 procedure；Dashboard 只記 task / scope / memory ID、抽象 pattern 統計、步數、耗時與驗證結果。事件從這個版本開始累積。",
                        "Memory is a structured procedure. The dashboard stores only task/scope/memory IDs, abstract pattern statistics, steps, duration, and verification outcome. Events accumulate from this version onward."),
                10.5f,
                CrewTheme.TEXT_SECONDARY,
                false));

        int memoryTasks = summary.optInt("memoryTasks", 0);
        int memoryVerified = summary.optInt("memoryVerified", 0);
        int baselineTasks = summary.optInt("noMemoryTasks", 0);
        int baselineVerified = summary.optInt("noMemoryVerified", 0);
        int corrections = summary.optInt("corrections", 0);

        TextView stats = text(
                I18n.get(this, "Memory 任務 ", "Memory tasks ")
                        + memoryVerified + "/" + memoryTasks
                        + " terminal verified (" + rate(memoryVerified, memoryTasks) + ")\n"
                        + I18n.get(this, "無 Memory 任務 ", "No-memory tasks ")
                        + baselineVerified + "/" + baselineTasks
                        + " terminal verified (" + rate(baselineVerified, baselineTasks) + ")\n"
                        + I18n.get(this, "糾正事件 ", "Correction events ")
                        + corrections,
                10,
                CrewTheme.TEXT_MUTED,
                false);
        stats.setPadding(0, dp(8), 0, 0);
        card.addView(stats);

        if (items.length() > 0) {
            Button clear = actionButton(
                    I18n.get(this,
                            "清除全部 Refined Memory",
                            "Clear all Refined Memory"),
                    true);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(40));
            lp.topMargin = dp(10);
            clear.setOnClickListener(v -> confirmClearAll());
            card.addView(clear, lp);
        }

        content.addView(card, cardParams());
    }

    private void renderMemories() {
        JSONArray items = store.dumpForDebug();
        section(
                I18n.get(this, "記憶", "Memories"),
                items.length() + " "
                        + I18n.get(this, "條", "entries"));

        if (items.length() == 0) {
            content.addView(emptyCard(I18n.get(
                    this,
                    "還沒有 Refined Memory。只有取得可信終態證據的低風險流程才會累積 independent evidence。",
                    "No Refined Memory yet. Only low-risk procedures with trusted terminal evidence accumulate independent evidence.")));
            return;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            content.addView(memoryCard(item), cardParams());
        }
    }

    private View memoryCard(JSONObject item) {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        String scope = item.optString("scope", "");
        top.addView(text(
                scope,
                12.5f,
                CrewTheme.TEXT_PRIMARY,
                true),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        String state = item.optString("state", "");
        boolean enabled = item.optBoolean("enabled", true);
        String displayState =
                enabled
                        ? state
                        : "DISABLED · " + state;
        TextView badge = text(
                displayState,
                9.5f,
                enabled
                        ? stateColor(state)
                        : CrewTheme.TEXT_MUTED,
                true);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        badge.setBackground(CrewTheme.createCard(
                this,
                Color.argb(30, 82, 82, 91),
                CrewTheme.BORDER_SUBTLE,
                10));
        top.addView(badge);
        card.addView(top);

        String pkg = item.optString("packageName", "");
        String startPkg = item.optString("startPackage", "");
        if (!pkg.isEmpty()) {
            card.addView(text(
                    I18n.get(this, "主要 App · ", "Primary app · ")
                            + appName(pkg),
                    9.5f,
                    CrewTheme.TEXT_SECONDARY,
                    false));
        }
        if (!startPkg.isEmpty() && !startPkg.equals(pkg)) {
            card.addView(text(
                    I18n.get(this, "可從 ", "Can start from ")
                            + appName(startPkg),
                    9.5f,
                    CrewTheme.TEXT_SECONDARY,
                    false));
        }

        TextView pattern = text(
                item.optString("pattern", ""),
                10.5f,
                CrewTheme.INDIGO_400,
                true);
        pattern.setTypeface(Typeface.MONOSPACE);
        pattern.setPadding(0, dp(7), 0, 0);
        card.addView(pattern);

        int success = item.optInt("successCount", 0);
        int support = item.optInt("supportCount", 0);
        int failure = item.optInt("failureCount", 0);
        int confidence = (int) Math.round(
                item.optDouble("confidence", 0.0d) * 100.0d);
        card.addView(text(
                I18n.get(this, "Evidence · 獨立成功 ", "Evidence · independent ")
                        + success
                        + " · "
                        + I18n.get(this, "回放支持 ", "replay support ")
                        + support
                        + " · "
                        + I18n.get(this, "失敗 ", "failed ")
                        + failure
                        + " · confidence "
                        + confidence
                        + "%",
                9.5f,
                CrewTheme.TEXT_MUTED,
                false));

        JSONObject usage = store.usageStats(
                item.optString("id", ""),
                scope);
        int usedTasks = usage.optInt("usedTasks", 0);
        int verifiedTasks = usage.optInt("verifiedTasks", 0);
        int appliedTasks = usage.optInt("appliedTasks", 0);
        int corrected = usage.optInt("corrections", 0);
        int baselineTasks = usage.optInt("baselineTasks", 0);
        int baselineVerified = usage.optInt("baselineVerified", 0);

        TextView usageLine = text(
                I18n.get(this, "Usage · 使用 ", "Usage · used ")
                        + usedTasks
                        + I18n.get(this, " 次 · terminal ", " tasks · terminal ")
                        + verifiedTasks + "/" + usedTasks
                        + " · "
                        + I18n.get(this, "流程吻合 ", "pattern matched ")
                        + appliedTasks + "/" + usedTasks
                        + " · "
                        + I18n.get(this, "糾正 ", "corrected ")
                        + corrected,
                9.5f,
                corrected > 0
                        ? CrewTheme.ROSE_400
                        : CrewTheme.TEXT_SECONDARY,
                false);
        usageLine.setPadding(0, dp(6), 0, 0);
        card.addView(usageLine);

        if (usedTasks > 0) {
            card.addView(text(
                    I18n.get(this, "有這條 Memory · ", "With this memory · ")
                            + oneDecimal(usage.optDouble("avgStepsWith", 0.0d))
                            + " steps · "
                            + duration(usage.optDouble("avgDurationMsWith", 0.0d))
                            + " · verified "
                            + rate(verifiedTasks, usedTasks),
                    9.5f,
                    CrewTheme.TEAL_300,
                    false));
        }

        if (baselineTasks > 0) {
            card.addView(text(
                    I18n.get(this,
                            "同 scope、無 Refined Memory · ",
                            "Same scope, without Refined Memory · ")
                            + oneDecimal(usage.optDouble("avgStepsWithout", 0.0d))
                            + " steps · "
                            + duration(usage.optDouble("avgDurationMsWithout", 0.0d))
                            + " · verified "
                            + rate(baselineVerified, baselineTasks),
                    9.5f,
                    CrewTheme.TEXT_MUTED,
                    false));
        }

        String provenance =
                I18n.get(this, "來源 ", "Source ")
                        + item.optString("lastEvidenceSource", "—")
                        + " · "
                        + I18n.get(this, "首次 ", "first ")
                        + formatTime(item.optLong("createdAt", 0L))
                        + " · "
                        + I18n.get(this, "最後驗證 ", "last verified ")
                        + formatTime(item.optLong("lastVerifiedAt", 0L));
        long lastUsedAt = usage.optLong("lastUsedAt", 0L);
        if (lastUsedAt > 0L) {
            provenance += " · "
                    + I18n.get(this, "最後使用 ", "last used ")
                    + formatTime(lastUsedAt);
        }
        String lastTaskId = item.optString("lastTaskId", "");
        if (!lastTaskId.isEmpty()) {
            provenance += " · task " + shortTask(lastTaskId);
        }

        TextView provenanceView = text(
                provenance,
                9,
                CrewTheme.TEXT_MUTED,
                false);
        provenanceView.setPadding(0, dp(6), 0, 0);
        card.addView(provenanceView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(9), 0, 0);

        String memoryId = item.optString("id", "");

        Button toggle = actionButton(
                enabled
                        ? I18n.get(this, "停用", "Disable")
                        : I18n.get(this, "啟用", "Enable"),
                false);
        toggle.setOnClickListener(v -> {
            store.setEnabled(memoryId, !enabled);
            render();
        });
        actions.addView(toggle, actionLp());

        Button delete = actionButton(
                I18n.get(this, "刪除", "Delete"),
                true);
        delete.setOnClickListener(v ->
                confirmDelete(memoryId, scope));
        actions.addView(delete, actionLp());

        card.addView(actions);
        return card;
    }

    private void renderEvents() {
        JSONArray events = store.recentEvents(16);
        section(
                I18n.get(this, "最近 Memory Events", "Recent Memory Events"),
                events.length() + " "
                        + I18n.get(this, "筆", "events"));

        if (events.length() == 0) {
            content.addView(emptyCard(I18n.get(
                    this,
                    "這個版本開始才會記錄 Memory Dashboard events。",
                    "Memory Dashboard events start accumulating from this version.")));
            return;
        }

        LinearLayout card = card();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            TextView line = text(
                    eventLine(event),
                    9.5f,
                    eventColor(event.optString("type", "")),
                    false);
            if (i > 0) line.setPadding(0, dp(7), 0, 0);
            card.addView(line);
        }
        content.addView(card, cardParams());
    }

    private String eventLine(JSONObject event) {
        String type = event.optString("type", "");
        String scope = event.optString("scope", "");
        String prefix = formatTime(event.optLong("at", 0L))
                + "  " + type;
        if (!scope.isEmpty()) prefix += " · " + scope;

        String taskId = event.optString("taskId", "");
        if (!taskId.isEmpty()) {
            prefix += " · task " + shortTask(taskId);
        }

        if ("USED".equals(type)) {
            JSONArray ids = event.optJSONArray("memoryIds");
            return prefix + " · memories="
                    + (ids == null ? 0 : ids.length());
        }

        if ("TASK_RESULT".equals(type)) {
            JSONArray ids = event.optJSONArray("memoryIds");
            JSONArray applied = event.optJSONArray("appliedIds");
            return prefix
                    + " · memory="
                    + (ids == null ? 0 : ids.length())
                    + " · matched="
                    + (applied == null ? 0 : applied.length())
                    + " · terminal="
                    + (event.optBoolean("terminalVerified", false)
                            ? "yes" : "no")
                    + " · "
                    + event.optInt("stepCount", 0)
                    + " steps · "
                    + duration(event.optLong("durationMs", 0L));
        }

        if ("CORRECTED".equals(type)) {
            return prefix + " · affected="
                    + event.optInt("changed", 0);
        }

        if ("LEARNED".equals(type)
                || "EVIDENCE".equals(type)
                || "REPLAY_SUPPORT".equals(type)
                || "NEGATIVE".equals(type)) {
            return prefix
                    + " · "
                    + event.optString("state", "")
                    + " · independent="
                    + event.optInt("successCount", 0)
                    + " support="
                    + event.optInt("supportCount", 0)
                    + " failed="
                    + event.optInt("failureCount", 0);
        }

        return prefix;
    }

    private void confirmDelete(
            String memoryId,
            String scope) {
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(
                        this,
                        "刪除 Refined Memory？",
                        "Delete Refined Memory?"))
                .setMessage(scope)
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"),
                        null)
                .setPositiveButton(
                        I18n.get(this, "刪除", "Delete"),
                        (dialog, which) -> {
                            if (store.delete(memoryId)) {
                                Toast.makeText(
                                        this,
                                        I18n.get(
                                                this,
                                                "已刪除這條 Refined Memory",
                                                "Refined Memory deleted"),
                                        Toast.LENGTH_SHORT).show();
                            }
                            render();
                        })
                .show();
    }

    private void confirmClearAll() {
        int count = store.count();
        if (count == 0) return;

        new AlertDialog.Builder(this)
                .setTitle(I18n.get(
                        this,
                        "清除全部 Refined Memory？",
                        "Clear all Refined Memory?"))
                .setMessage(I18n.get(
                        this,
                        "這會清除 Refined Memory 與它的 Dashboard 歷史，不會清除 App Action Memory、Crew Experience 或 App Playbooks。",
                        "This clears Refined Memory and its Dashboard history. App Action Memory, Crew Experience, and App Playbooks are not affected."))
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"),
                        null)
                .setPositiveButton(
                        I18n.get(this, "全部清除", "Clear all"),
                        (dialog, which) -> {
                            int removed = store.clearAll();
                            Toast.makeText(
                                    this,
                                    I18n.get(
                                            this,
                                            "已清除 " + removed + " 條 Refined Memory",
                                            "Cleared " + removed + " Refined Memory entries"),
                                    Toast.LENGTH_SHORT).show();
                            render();
                        })
                .show();
    }

    private void section(String title, String badge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(8));

        row.addView(text(
                title,
                14,
                CrewTheme.TEXT_PRIMARY,
                true),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        row.addView(text(
                badge,
                9.5f,
                CrewTheme.TEAL_300,
                true));
        content.addView(row);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                14));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        return lp;
    }

    private View emptyCard(String message) {
        LinearLayout card = card();
        card.addView(text(
                message,
                11,
                CrewTheme.TEXT_SECONDARY,
                false));
        return card;
    }

    private Button actionButton(
            String label,
            boolean destructive) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(10.5f);
        button.setTextColor(
                destructive
                        ? CrewTheme.ROSE_400
                        : CrewTheme.TEXT_PRIMARY);
        button.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        return button;
    }

    private LinearLayout.LayoutParams actionLp() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(38),
                        1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
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

    private int stateColor(String state) {
        if (RefinedMemoryPolicy.STATE_TRUSTED.equals(state)) {
            return CrewTheme.EMERALD_400;
        }
        if (RefinedMemoryPolicy.STATE_VERIFIED.equals(state)) {
            return CrewTheme.TEAL_300;
        }
        if (RefinedMemoryPolicy.STATE_STALE.equals(state)
                || RefinedMemoryPolicy.STATE_SUSPECT.equals(state)) {
            return CrewTheme.ROSE_400;
        }
        return CrewTheme.TEXT_MUTED;
    }

    private int eventColor(String type) {
        if ("CORRECTED".equals(type)
                || "NEGATIVE".equals(type)
                || "DELETED".equals(type)
                || "DISABLED".equals(type)) {
            return CrewTheme.ROSE_400;
        }
        if ("LEARNED".equals(type)
                || "EVIDENCE".equals(type)
                || "REPLAY_SUPPORT".equals(type)
                || "ENABLED".equals(type)) {
            return CrewTheme.TEAL_300;
        }
        return CrewTheme.TEXT_MUTED;
    }

    private String appName(String pkg) {
        String app = AppRuntimeRegistry.displayName(this, pkg);
        return app == null || app.trim().isEmpty() || app.equals(pkg)
                ? pkg
                : app + " · " + pkg;
    }

    private String shortTask(String taskId) {
        String value = taskId == null ? "" : taskId.trim();
        if (value.length() <= 8) return value;
        return "…" + value.substring(value.length() - 8);
    }

    private String rate(int success, int total) {
        if (total <= 0) return "—";
        return Math.round(success * 100.0d / total) + "%";
    }

    private String oneDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String duration(double milliseconds) {
        if (milliseconds <= 0.0d) return "—";
        if (milliseconds < 1000.0d) {
            return Math.round(milliseconds) + "ms";
        }
        return String.format(
                Locale.US,
                "%.1fs",
                milliseconds / 1000.0d);
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0L) return "—";
        try {
            return DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT)
                    .format(new Date(timestamp));
        } catch (Exception ignored) {
            return "—";
        }
    }
}
