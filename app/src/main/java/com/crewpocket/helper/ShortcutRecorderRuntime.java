package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User-driven shortcut recorder.
 *
 * Records only stable semantic actions. It never persists raw coordinates,
 * typed text, message bodies, passwords, OTPs, send/delete/payment controls.
 */
final class ShortcutRecorderRuntime {
    private static final String PREFS = "crew_shortcut_recorder";
    private static final String KEY_PENDING = "pending_v1";
    private static final int MAX_STEPS = 20;
    private static final long DEDUPE_MS = 700L;

    private static ShortcutRecorderRuntime instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final JSONArray steps = new JSONArray();

    private boolean recording;
    private int skippedUnsafe;
    private int skippedUnstable;
    private boolean ignoredTextInput;
    private String lastSignature = "";
    private long lastSignatureAt;
    private String lastOpenedPackage = "";

    static synchronized ShortcutRecorderRuntime getInstance(Context context) {
        if (instance == null) {
            instance = new ShortcutRecorderRuntime(context.getApplicationContext());
        }
        return instance;
    }

    private ShortcutRecorderRuntime(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean isRecording() { return recording; }

    synchronized int stepCount() { return steps.length(); }

    synchronized boolean start() {
        if (!CrewAccessibilityService.isServiceRunning()) {
            showStatus("無法開始錄製", "請先啟用 Crew Helper 無障礙服務");
            return false;
        }
        recording = true;
        clearSteps();
        skippedUnsafe = 0;
        skippedUnstable = 0;
        ignoredTextInput = false;
        lastSignature = "";
        lastSignatureAt = 0L;
        lastOpenedPackage = "";
        prefs.edit().remove(KEY_PENDING).apply();
        showStatus("● 正在錄製快捷指令", "請直接操作手機；完成後再點泡泡的錄製按鈕");
        return true;
    }

    synchronized void undo() {
        if (!recording || steps.length() == 0) {
            showStatus("錄製快捷指令", "目前沒有可復原的步驟");
            return;
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < steps.length() - 1; i++) next.put(steps.optJSONObject(i));
        clearSteps();
        for (int i = 0; i < next.length(); i++) steps.put(next.optJSONObject(i));
        showStatus("已復原上一步", "目前 " + steps.length() + " 個步驟");
    }

    synchronized boolean finishAndOpenEditor() {
        if (!recording) return false;
        recording = false;
        if (steps.length() == 0) {
            showStatus("沒有可儲存的操作", "只會記錄開啟 App 與可穩定定位的點擊");
            return false;
        }

        JSONObject pending = new JSONObject();
        try {
            pending.put("steps", new JSONArray(steps.toString()))
                    .put("skippedUnsafe", skippedUnsafe)
                    .put("skippedUnstable", skippedUnstable)
                    .put("ignoredTextInput", ignoredTextInput)
                    .put("capturedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY_PENDING, pending.toString()).apply();

        showStatus("錄製完成", steps.length() + " 個步驟 · 正在開啟設定");
        Intent intent = new Intent(context, ShortcutRecorderActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
        return true;
    }

    synchronized JSONObject loadPendingDraft() {
        try { return new JSONObject(prefs.getString(KEY_PENDING, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    synchronized void clearPendingDraft() {
        prefs.edit().remove(KEY_PENDING).apply();
    }

    synchronized void onAccessibilityEvent(AccessibilityEvent event) {
        if (!recording || event == null || steps.length() >= MAX_STEPS) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            ignoredTextInput = true;
            return;
        }

        CharSequence rawPackage = event.getPackageName();
        String packageName = rawPackage == null ? "" : rawPackage.toString();
        if (packageName.isEmpty()
                || context.getPackageName().equals(packageName)
                || isHomePackage(packageName)) {
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!packageName.equals(lastOpenedPackage) && isLaunchable(packageName)) {
                JSONObject step = new JSONObject();
                try {
                    step.put("type", "OPEN_APP")
                            .put("packageName", packageName)
                            .put("label", appLabel(packageName));
                } catch (Exception ignored) {}
                addStep(step, "OPEN_APP|" + packageName,
                        "開啟 " + appLabel(packageName));
                lastOpenedPackage = packageName;
            }
            return;
        }

        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED) return;

        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            skippedUnstable++;
            return;
        }

        try {
            CharSequence sourcePackage = source.getPackageName();
            String nodePackage = sourcePackage == null ? packageName : sourcePackage.toString();
            if (context.getPackageName().equals(nodePackage) || isHomePackage(nodePackage)) return;
            if (source.isPassword()) {
                skippedUnsafe++;
                return;
            }

            String viewId = safe(source.getViewIdResourceName(), 160);
            String desc = safe(source.getContentDescription(), 80);
            String className = safe(source.getClassName(), 100);
            boolean editable = source.isEditable();

            String structural = normalize(viewId + "|" + desc);
            if (containsForbidden(structural)) {
                skippedUnsafe++;
                showStatus("略過敏感操作", "傳送、刪除、付款等操作不會被錄進快捷指令");
                return;
            }

            // Fail closed: no coordinates, no arbitrary visible text fallback.
            if (viewId.isEmpty() && desc.isEmpty() && !editable) {
                skippedUnstable++;
                showStatus("略過不穩定點擊", "這個元素沒有穩定 selector，不會保存座標");
                return;
            }

            JSONObject step = new JSONObject();
            try {
                String summary = !desc.isEmpty()
                        ? desc
                        : editable
                        ? "輸入框"
                        : compactViewId(viewId);
                step.put("type", "TAP_ELEMENT")
                        .put("packageName", nodePackage)
                        .put("viewId", viewId)
                        .put("contentDescription", desc)
                        .put("className", className)
                        .put("editable", editable)
                        .put("summary", summary);
                addStep(step,
                        "TAP|" + nodePackage + "|" + viewId + "|" + desc + "|" + editable,
                        "點擊 " + summary);
            } catch (Exception ignored) {}
        } finally {
            try { source.recycle(); } catch (Exception ignored) {}
        }
    }

    private void addStep(JSONObject step, String signature, String summary) {
        long now = System.currentTimeMillis();
        if (signature.equals(lastSignature) && now - lastSignatureAt < DEDUPE_MS) return;
        if (steps.length() >= MAX_STEPS) {
            showStatus("錄製已達上限", "最多 " + MAX_STEPS + " 個步驟");
            return;
        }
        steps.put(step);
        lastSignature = signature;
        lastSignatureAt = now;
        showStatus("✓ 已記錄", summary + " · 共 " + steps.length() + " 步");
    }

    private boolean isLaunchable(String packageName) {
        try {
            return context.getPackageManager().getLaunchIntentForPackage(packageName) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isHomePackage(String packageName) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                    && packageName.equals(info.activityInfo.packageName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String appLabel(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    private static String compactViewId(String value) {
        if (value == null || value.isEmpty()) return "畫面元素";
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private static String safe(CharSequence value, int max) {
        if (value == null) return "";
        String out = value.toString().trim();
        return out.length() <= max ? out : out.substring(0, max);
    }

    private static String normalize(String text) {
        return TextMatch.caseFold(text == null ? "" : text)
                .replaceAll("[\\s_\\-./:，,。！？!]+", "");
    }

    private static boolean containsForbidden(String value) {
        String[] blocked = new String[]{
                "send", "submitmessage", "reply", "傳送", "发送", "發送", "回覆",
                "delete", "remove", "刪除", "删除",
                "pay", "purchase", "buy", "checkout", "付款", "支付", "購買", "购买",
                "transfer", "轉帳", "转账", "confirmorder", "確認訂單", "确认订单"
        };
        for (String token : blocked) {
            if (value.contains(normalize(token))) return true;
        }
        return false;
    }

    private void clearSteps() {
        while (steps.length() > 0) steps.remove(steps.length() - 1);
    }

    private void showStatus(String title, String detail) {
        try {
            FloatingBubbleManager.getInstance(context).showCompactStatus(title, detail);
        } catch (Exception ignored) {}
    }
}
