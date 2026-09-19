package com.crewpocket.helper;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Owns Runtime Memory Rule matching and deterministic shortcut execution.
 *
 * Phone-tool authorization stays in NativeGeminiLiveClient. Legacy rules that
 * need normal Observe -> Action -> Verify are handed back through Host instead
 * of executing model-facing mutations here.
 */
final class MemoryRuleController {
    interface Host {
        void reportStage(String text);
        void sendInternalDirective(String text);
        void updateTrustedAction(String action);
    }

    private static final String TAG = "CrewNativeLive";

    private final Context appContext;
    private final Host host;
    private final MemoryRuleIndex memoryRuleIndex;

    private String lastDispatchKey = "";
    private long lastDispatchAt;
    private volatile boolean shortcutExecuting;
    private volatile long shortcutGuardUntil;

    MemoryRuleController(Context context, Host host) {
        this.appContext =
                context == null ? null : context.getApplicationContext();
        if (host == null) throw new IllegalArgumentException("host required");
        this.host = host;
        this.memoryRuleIndex = this.appContext == null
                ? null : new MemoryRuleIndex(this.appContext);
    }

    boolean isShortcutExecuting() {
        return shortcutExecuting;
    }

    long shortcutGuardUntil() {
        return shortcutGuardUntil;
    }

    boolean processInput(String inputText) {
        try {
            if (appContext == null) return false;
            MemoryRuleStore store = new MemoryRuleStore(appContext);

            if (isMemoryRuleRequest(inputText)) {
                try {
                    FloatingBubbleManager.getInstance(appContext)
                            .showCompactStatus(
                                    "快捷指令由 Runtime 管理",
                                    "請點懸浮泡泡 → 紅色錄製按鈕，實際操作後再按一次完成");
                } catch (Exception ignored) {}
                host.sendInternalDirective(
                        "【0033 Shortcut UI】使用者想建立/記住快捷指令。"
                                + "不要呼叫任何建立規則或儲存工具，也不要聲稱已儲存。"
                                + "只簡短告訴使用者：從懸浮泡泡按『錄製快捷指令』，完成操作後再按一次即可設定觸發句。");
                return true;
            }

            if (memoryRuleIndex != null) memoryRuleIndex.refresh();
            MemoryRuleIndex.Match matched = memoryRuleIndex == null
                    ? null : memoryRuleIndex.findBest(inputText);
            if (matched == null && memoryRuleIndex == null) {
                MemoryRuleStore.Rule exact = store.findExact(inputText);
                if (exact != null) {
                    matched = new MemoryRuleIndex.Match(
                            exact, "EXACT", 1.0, exact.trigger);
                }
            }

            if (matched == null || matched.rule == null) {
                if (MemoryRuleIndex.looksLikeRecordedShortcut(inputText)) {
                    host.reportStage(
                            "已收到開啟指令，但未命中已學習快捷操作");
                    try {
                        FloatingBubbleManager.getInstance(appContext)
                                .showCompactStatus(
                                        "快捷指令未命中",
                                        "請確認觸發句；可在『已學習操作』查看或重新錄製");
                    } catch (Exception ignored) {}
                }
                return false;
            }

            MemoryRuleStore.Rule rule = matched.rule;
            String dispatchKey =
                    TextMatch.caseFold(rule.id + "|" + matched.mode);
            long now = System.currentTimeMillis();
            if (dispatchKey.equals(lastDispatchKey)
                    && now - lastDispatchAt < 2500L) {
                return true;
            }
            lastDispatchKey = dispatchKey;
            lastDispatchAt = now;

            store.recordMatch(rule.id, matched.mode);
            if (memoryRuleIndex != null) memoryRuleIndex.refresh();

            if (ShortcutPlanStore.isPlanAction(rule.action)) {
                if (shortcutExecuting || ShortcutExecutionRuntime.isRunning()) {
                    host.reportStage(
                            "Shortcut 已在執行中，忽略重複觸發");
                    return true;
                }

                final String planId =
                        ShortcutPlanStore.planIdFromAction(rule.action);
                shortcutExecuting = true;
                shortcutGuardUntil = Long.MAX_VALUE;
                host.reportStage(
                        "Runtime Shortcut 命中：「"
                                + rule.trigger + "」 · " + matched.mode);
                try {
                    FloatingBubbleManager.getInstance(appContext)
                            .showCompactStatus(
                                    "✓ Shortcut HIT",
                                    rule.trigger + " · " + matched.mode);
                } catch (Exception ignored) {}
                host.sendInternalDirective(
                        "【Runtime Shortcut】已由 Android Runtime 接管執行。"
                                + "你不得呼叫任何手機 mutation tool、不得重新規劃或重複操作；"
                                + "保持簡短並等待手機畫面結果。");

                ShortcutExecutionRuntime.executePlanAsync(
                        appContext,
                        planId,
                        new ShortcutExecutionRuntime.Callback() {
                            @Override public void onComplete(
                                    boolean success,
                                    String detail) {
                                shortcutExecuting = false;
                                shortcutGuardUntil =
                                        System.currentTimeMillis() + 1800L;
                                host.reportStage(
                                        (success
                                                ? "Runtime Shortcut 完成："
                                                : "Runtime Shortcut 失敗：")
                                                + detail);
                                try {
                                    FloatingBubbleManager
                                            .getInstance(appContext)
                                            .showCompactStatus(
                                                    success
                                                            ? "✓ 快捷指令完成"
                                                            : "✕ 快捷指令失敗",
                                                    detail);
                                } catch (Exception ignored) {}
                            }
                        });
                return true;
            }

            if (AppLaunchShortcut.isAction(rule.action)) {
                shortcutExecuting = true;
                shortcutGuardUntil = Long.MAX_VALUE;
                host.reportStage(
                        "App 指令命中：「"
                                + rule.trigger + "」 · " + matched.mode);
                try {
                    FloatingBubbleManager.getInstance(appContext)
                            .showCompactStatus(
                                    "✓ App 指令命中",
                                    rule.trigger + " · " + matched.mode);
                } catch (Exception ignored) {}

                try {
                    String detail = AppLaunchShortcut.launch(
                            appContext,
                            AppLaunchShortcut.packageNameFromAction(
                                    rule.action));
                    host.reportStage("App 指令完成：" + detail);
                    try {
                        FloatingBubbleManager.getInstance(appContext)
                                .showCompactStatus(
                                        "✓ App 指令完成", detail);
                    } catch (Exception ignored) {}
                } catch (Exception error) {
                    String detail = error.getMessage() == null
                            ? "無法開啟 App"
                            : error.getMessage();
                    host.reportStage("App 指令失敗：" + detail);
                    try {
                        FloatingBubbleManager.getInstance(appContext)
                                .showCompactStatus(
                                        "✕ App 指令失敗", detail);
                    } catch (Exception ignored) {}
                } finally {
                    shortcutExecuting = false;
                    shortcutGuardUntil =
                            System.currentTimeMillis() + 1800L;
                }
                return true;
            }

            if (!MemoryRuleStore.containsProhibitedShortcutAction(
                    rule.action)) {
                host.updateTrustedAction(rule.action);
            }
            host.reportStage(
                    "Legacy Shortcut 命中：「"
                            + rule.trigger + "」 · " + matched.mode);
            host.sendInternalDirective(
                    "【Legacy Shortcut 命中】Runtime 已確認觸發。現在要完成的任務是：「"
                            + rule.action
                            + "」。依正常 Observe→Action→Verify 執行，仍遵守全部安全政策。");
            return false;
        } catch (Exception error) {
            Log.w(TAG, "Shortcut 處理失敗：" + error.getMessage());
            return false;
        }
    }

    JSONObject listRules() throws Exception {
        JSONArray rules = new JSONArray();
        for (MemoryRuleStore.Rule rule : store().list()) {
            rules.put(new JSONObject()
                    .put("id", rule.id)
                    .put("trigger", rule.trigger)
                    .put("aliases", new JSONArray(rule.aliases))
                    .put("action", rule.action)
                    .put("enabled", rule.enabled)
                    .put("triggerCount", rule.triggerCount)
                    .put("lastUsedAt", rule.lastUsedAt)
                    .put("lastMatchMode", rule.lastMatchMode));
        }
        return new JSONObject()
                .put("success", true)
                .put("shortcuts", rules)
                .put("rules", rules)
                .put("count", rules.length());
    }

    private MemoryRuleStore store() throws Exception {
        if (appContext == null) {
            throw new Exception(
                    "App Context 不可用，無法保存 Memory Rule");
        }
        return new MemoryRuleStore(appContext);
    }

    private boolean isMemoryRuleRequest(String text) {
        String clean =
                text == null ? "" : text.replaceAll("\\s+", "");
        String lower = clean.toLowerCase(Locale.ROOT);
        return lower.contains("memoryrule")
                || lower.contains("shortcut")
                || clean.contains("建立規則")
                || clean.contains("新增規則")
                || clean.contains("建立快捷指令")
                || clean.contains("新增快捷指令")
                || clean.contains("建立快捷命令")
                || clean.contains("設定口令")
                || clean.contains("設一個口令")
                || clean.contains("記住一條規則")
                || clean.contains("記憶一條規則")
                || clean.contains("幫我記住一條規則")
                || clean.contains("幫我建立一個規則")
                || clean.contains("幫我建立一個快捷指令");
    }
}
