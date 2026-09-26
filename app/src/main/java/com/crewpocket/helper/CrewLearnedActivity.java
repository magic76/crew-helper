package com.crewpocket.helper;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Toast;

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
    private ReflectionLessonStore experiences;
    private RefinedMemoryStore refinedMemory;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        actionMemory = new LearnedUiMappingStore(this);
        playbooks = new AppPlaybookStore(this);
        experiences = new ReflectionLessonStore(this);
        refinedMemory = new RefinedMemoryStore(this);

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
        renderRefinedMemory();
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

        LinearLayout manageCard = card();
        manageCard.addView(text(
                I18n.get(this,
                        "可以查看全部 mapping、改自訂名稱、停用/啟用、刪除，並整理結構完全相同的重複項。",
                        "View all mappings, edit custom names, enable/disable, delete, and clean structurally identical duplicates."),
                11, CrewTheme.TEXT_SECONDARY, false));

        int duplicateCount = actionMemory.exactDuplicateCount();
        if (duplicateCount > 0) {
            TextView duplicateHint = text(
                    I18n.get(this,
                            "目前偵測到 " + duplicateCount + " 條可安全整理的重複 mapping",
                            duplicateCount + " safely cleanable duplicate mappings detected"),
                    10, CrewTheme.ROSE_400, true);
            duplicateHint.setPadding(0, dp(7), 0, 0);
            manageCard.addView(duplicateHint);
        }

        Button manage = new Button(this);
        manage.setAllCaps(false);
        manage.setText(I18n.get(this,
                "管理 App Action Memory",
                "Manage App Action Memory"));
        manage.setTextColor(Color.WHITE);
        manage.setTextSize(12);
        manage.setTypeface(Typeface.DEFAULT_BOLD);
        manage.setBackground(CrewTheme.createGradientButton(
                this, CrewTheme.TEAL_500, CrewTheme.INDIGO_500, 12));
        manage.setOnClickListener(v -> startActivity(
                new Intent(CrewLearnedActivity.this, ActionMemoryActivity.class)));
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        manageLp.topMargin = dp(10);
        manageCard.addView(manage, manageLp);
        content.addView(manageCard, cardParams());

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

    private void renderRefinedMemory() {
        JSONArray items = refinedMemory == null
                ? new JSONArray()
                : refinedMemory.dumpForDebug();
        int usable = refinedMemory == null
                ? 0
                : refinedMemory.injectableCount();
        JSONObject summary = refinedMemory == null
                ? new JSONObject()
                : refinedMemory.dashboardSummary();

        addSectionHeader(
                I18n.get(this, "Refined Memory Dashboard", "Refined Memory Dashboard"),
                I18n.get(this,
                        "PATTERNS · 看 Crew 學了什麼、何時用到，以及是否真的改善任務",
                        "PATTERNS · see what Crew learned, when it was used, and whether it actually helped"),
                usable + " "
                        + I18n.get(this, "可使用", "usable")
                        + " · " + items.length() + " "
                        + I18n.get(this, "總計", "total"));

        LinearLayout overview = card();
        overview.addView(text(
                I18n.get(this,
                        "底層保存的是結構化流程，不保存搜尋文字、訊息、座標或密碼。Dashboard 事件從支援此版本後開始累積；只有 VERIFIED / TRUSTED 才會進 Gemini context。",
                        "The underlying memory is a structured procedure and never stores query text, messages, coordinates, or credentials. Dashboard events accumulate from this version onward; only VERIFIED / TRUSTED patterns enter Gemini context."),
                11, CrewTheme.TEXT_SECONDARY, false));

        int memoryTasks = summary.optInt("memoryTasks", 0);
        int memoryVerified = summary.optInt("memoryVerified", 0);
        int baselineTasks = summary.optInt("noMemoryTasks", 0);
        int baselineVerified = summary.optInt("noMemoryVerified", 0);
        int corrections = summary.optInt("corrections", 0);

        TextView overall = text(
                I18n.get(this, "最近整體：", "Recent overall: ")
                        + I18n.get(this, "Memory 任務 ", "Memory tasks ")
                        + memoryTasks
                        + " · terminal verified "
                        + memoryVerified
                        + "/"
                        + memoryTasks
                        + " ("
                        + rate(memoryVerified, memoryTasks)
                        + ")\n"
                        + I18n.get(this, "無 Memory baseline ", "No-memory baseline ")
                        + baselineVerified
                        + "/"
                        + baselineTasks
                        + " ("
                        + rate(baselineVerified, baselineTasks)
                        + ") · "
                        + I18n.get(this, "糾正 ", "corrections ")
                        + corrections,
                10,
                CrewTheme.TEXT_MUTED,
                false);
        overall.setPadding(0, dp(8), 0, 0);
        overview.addView(overall);

        Button clearAll = memoryActionButton(
                I18n.get(this, "清除全部 Refined Memory", "Clear all Refined Memory"),
                true);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        clearLp.topMargin = dp(10);
        clearAll.setOnClickListener(v -> confirmClearRefinedMemory());
        overview.addView(clearAll, clearLp);
        content.addView(overview, cardParams());

        if (items.length() == 0) {
            content.addView(emptyCard(I18n.get(
                    this,
                    "目前還沒有 Refined Memory。只有取得可信終態證據的低風險流程才會累積 independent evidence。",
                    "No Refined Memory yet. Only low-risk procedures with trusted terminal evidence accumulate independent evidence.")));
        } else {
            int shown = Math.min(12, items.length());
            for (int i = 0; i < shown; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                LinearLayout memoryCard = card();
                LinearLayout top = new LinearLayout(this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);

                String scope = item.optString("scope", "");
                top.addView(text(scope, 12.5f, CrewTheme.TEXT_PRIMARY, true),
                        new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                String state = item.optString("state", "");
                int stateColor =
                        RefinedMemoryPolicy.STATE_TRUSTED.equals(state)
                                ? CrewTheme.EMERALD_400
                                : (RefinedMemoryPolicy.STATE_VERIFIED.equals(state)
                                        ? CrewTheme.TEAL_300
                                        : ((RefinedMemoryPolicy.STATE_STALE.equals(state)
                                                || RefinedMemoryPolicy.STATE_SUSPECT.equals(state))
                                                ? CrewTheme.ROSE_400
                                                : CrewTheme.TEXT_MUTED));
                TextView badge = text(state, 9.5f, stateColor, true);
                badge.setPadding(dp(8), dp(3), dp(8), dp(3));
                badge.setBackground(CrewTheme.createCard(
                        this,
                        Color.argb(30, 82, 82, 91),
                        CrewTheme.BORDER_SUBTLE,
                        10));
                top.addView(badge);
                memoryCard.addView(top);

                String pkg = item.optString("packageName", "");
                String startPkg = item.optString("startPackage", "");
                if (!pkg.isEmpty()) {
                    memoryCard.addView(text(
                            I18n.get(this, "主要 App · ", "Primary app · ")
                                    + appName(pkg),
                            9.5f,
                            CrewTheme.TEXT_SECONDARY,
                            false));
                }
                if (!startPkg.isEmpty() && !startPkg.equals(pkg)) {
                    memoryCard.addView(text(
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
                memoryCard.addView(pattern);

                int success = item.optInt("successCount", 0);
                int support = item.optInt("supportCount", 0);
                int failure = item.optInt("failureCount", 0);
                int confidence = (int) Math.round(
                        item.optDouble("confidence", 0.0d) * 100.0d);
                memoryCard.addView(text(
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

                JSONObject usage = refinedMemory.usageStats(
                        item.optString("id", ""),
                        scope);
                int usedTasks = usage.optInt("usedTasks", 0);
                int verifiedTasks = usage.optInt("verifiedTasks", 0);
                int appliedTasks = usage.optInt("appliedTasks", 0);
                int corrected = usage.optInt("corrections", 0);
                int sameScopeBaseline = usage.optInt("baselineTasks", 0);
                int baselineVerifiedForScope =
                        usage.optInt("baselineVerified", 0);

                TextView usageText = text(
                        I18n.get(this, "Usage · 使用 ", "Usage · used ")
                                + usedTasks
                                + I18n.get(this, " 次 · terminal ", " tasks · terminal ")
                                + verifiedTasks
                                + "/"
                                + usedTasks
                                + " · "
                                + I18n.get(this, "流程吻合 ", "pattern applied ")
                                + appliedTasks
                                + "/"
                                + usedTasks
                                + " · "
                                + I18n.get(this, "糾正 ", "corrected ")
                                + corrected,
                        9.5f,
                        corrected > 0
                                ? CrewTheme.ROSE_400
                                : CrewTheme.TEXT_SECONDARY,
                        false);
                usageText.setPadding(0, dp(6), 0, 0);
                memoryCard.addView(usageText);

                if (usedTasks > 0) {
                    memoryCard.addView(text(
                            I18n.get(this, "有 Memory · ", "With memory · ")
                                    + oneDecimal(usage.optDouble("avgStepsWith", 0.0d))
                                    + I18n.get(this, " steps · ", " steps · ")
                                    + duration(usage.optDouble("avgDurationMsWith", 0.0d))
                                    + " · verified "
                                    + rate(verifiedTasks, usedTasks),
                            9.5f,
                            CrewTheme.TEAL_300,
                            false));
                }
                if (sameScopeBaseline > 0) {
                    memoryCard.addView(text(
                            I18n.get(this, "同 scope 無 Memory · ", "Same-scope without memory · ")
                                    + oneDecimal(usage.optDouble("avgStepsWithout", 0.0d))
                                    + I18n.get(this, " steps · ", " steps · ")
                                    + duration(usage.optDouble("avgDurationMsWithout", 0.0d))
                                    + " · verified "
                                    + rate(
                                            baselineVerifiedForScope,
                                            sameScopeBaseline),
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
                memoryCard.addView(provenanceView);

                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, dp(9), 0, 0);

                boolean enabled = item.optBoolean("enabled", true);
                String memoryId = item.optString("id", "");
                Button toggle = memoryActionButton(
                        enabled
                                ? I18n.get(this, "停用", "Disable")
                                : I18n.get(this, "啟用", "Enable"),
                        false);
                toggle.setOnClickListener(v -> {
                    refinedMemory.setEnabled(memoryId, !enabled);
                    render();
                });
                actions.addView(toggle, memoryActionLp());

                Button delete = memoryActionButton(
                        I18n.get(this, "刪除", "Delete"),
                        true);
                delete.setOnClickListener(v ->
                        confirmDeleteRefinedMemory(
                                memoryId,
                                scope));
                actions.addView(delete, memoryActionLp());
                memoryCard.addView(actions);

                content.addView(memoryCard, cardParams());
            }

            if (items.length() > shown) {
                TextView more = text(
                        I18n.get(this,
                                "另有 " + (items.length() - shown) + " 條較舊記憶",
                                (items.length() - shown) + " older memories"),
                        10, CrewTheme.TEXT_MUTED, false);
                more.setPadding(dp(4), 0, 0, dp(8));
                content.addView(more);
            }
        }

        renderRecentMemoryEvents();
    }

    private void renderRecentMemoryEvents() {
        JSONArray events = refinedMemory == null
                ? new JSONArray()
                : refinedMemory.recentEvents(12);
        addSectionHeader(
                I18n.get(this, "最近 Memory Events", "Recent Memory Events"),
                I18n.get(this,
                        "最近的使用、學習、驗證、糾正與管理事件",
                        "Recent use, learning, verification, correction, and management events"),
                events.length() + " "
                        + I18n.get(this, "筆", "events"));

        if (events.length() == 0) {
            content.addView(emptyCard(I18n.get(
                    this,
                    "這個版本開始才會記錄 Memory Dashboard events。",
                    "Memory Dashboard events start accumulating from this version.")));
            return;
        }

        LinearLayout eventCard = card();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            TextView line = text(
                    memoryEventLine(event),
                    9.5f,
                    eventColor(event.optString("type", "")),
                    false);
            if (i > 0) line.setPadding(0, dp(7), 0, 0);
            eventCard.addView(line);
        }
        content.addView(eventCard, cardParams());
    }

    private String memoryEventLine(JSONObject event) {
        String type = event.optString("type", "");
        String scope = event.optString("scope", "");
        String prefix = formatTime(event.optLong("at", 0L))
                + "  " + type;
        if (!scope.isEmpty()) prefix += " · " + scope;
        String eventTaskId = event.optString("taskId", "");
        if (!eventTaskId.isEmpty()) {
            prefix += " · task " + shortTask(eventTaskId);
        }

        if ("USED".equals(type)) {
            JSONArray ids = event.optJSONArray("memoryIds");
            return prefix
                    + " · memories="
                    + (ids == null ? 0 : ids.length());
        }
        if ("TASK_RESULT".equals(type)) {
            JSONArray ids = event.optJSONArray("memoryIds");
            JSONArray applied = event.optJSONArray("appliedIds");
            return prefix
                    + " · memory="
                    + (ids == null ? 0 : ids.length())
                    + " · applied="
                    + (applied == null ? 0 : applied.length())
                    + " · verified="
                    + (event.optBoolean("terminalVerified", false)
                            ? "yes" : "no")
                    + " · "
                    + event.optInt("stepCount", 0)
                    + " steps · "
                    + duration(event.optLong("durationMs", 0L));
        }
        if ("CORRECTED".equals(type)) {
            return prefix
                    + " · affected="
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

    private Button memoryActionButton(
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

    private LinearLayout.LayoutParams memoryActionLp() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(38),
                        1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private void confirmDeleteRefinedMemory(
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
                            if (refinedMemory.delete(memoryId)) {
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

    private void confirmClearRefinedMemory() {
        int count = refinedMemory == null ? 0 : refinedMemory.count();
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
                            int removed = refinedMemory.clearAll();
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
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private String duration(double milliseconds) {
        if (milliseconds <= 0.0d) return "—";
        if (milliseconds < 1000.0d) {
            return Math.round(milliseconds) + "ms";
        }
        return String.format(
                java.util.Locale.US,
                "%.1fs",
                milliseconds / 1000.0d);
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
        int learnedCount = experiences == null ? 0 : experiences.count();

        addSectionHeader(
                I18n.get(this, "Crew Experience", "Crew Experience"),
                I18n.get(this,
                        "LESSONS · 從摩擦、重試與修正中學到的經驗",
                        "LESSONS · reusable experience from friction and correction"),
                learnedCount + " " + I18n.get(this, "條 lesson", "lessons"));

        LinearLayout card = card();
        card.addView(text(
                learnedCount == 0
                        ? I18n.get(this,
                                "目前還沒有 learned Experience。一般成功不會產生 Experience；只有明顯摩擦或重複中度摩擦才會進入學習。",
                                "No learned Experience yet. Plain success stays quiet; only strong or repeated medium friction enters learning.")
                        : I18n.get(this,
                                "Experience 會先成為候選，經相容證據確認後才升級到 App Playbook。你可以查看、編輯 Crew 最終記住的 lesson，或刪除它。",
                                "Experience starts as a candidate and is promoted to App Playbook only after compatible evidence. You can inspect, edit, or delete the learned lesson."),
                11, CrewTheme.TEXT_SECONDARY, false));

        Button manage = new Button(this);
        manage.setAllCaps(false);
        manage.setText(I18n.get(this,
                "管理 Crew Experience",
                "Manage Crew Experience"));
        manage.setTextColor(Color.WHITE);
        manage.setTextSize(12);
        manage.setTypeface(Typeface.DEFAULT_BOLD);
        manage.setBackground(CrewTheme.createGradientButton(
                this, CrewTheme.TEAL_500, CrewTheme.INDIGO_500, 12));
        manage.setOnClickListener(v -> startActivity(
                new Intent(CrewLearnedActivity.this, CrewExperienceActivity.class)));
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        buttonLp.topMargin = dp(10);
        card.addView(manage, buttonLp);

        TextView evidence = text(
                ExperienceEvidenceStore.buildSummary(this)
                        + "\n"
                        + ReflectionHistoryStore.buildSummary(this),
                9.5f, CrewTheme.TEXT_MUTED, false);
        evidence.setTypeface(Typeface.MONOSPACE);
        evidence.setLineSpacing(dp(1), 1.05f);
        evidence.setPadding(0, dp(10), 0, 0);
        card.addView(evidence);

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
