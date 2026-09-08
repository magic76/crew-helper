package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic replay for recorded Runtime shortcuts. */
final class ShortcutExecutionRuntime {
    interface Callback {
        void onComplete(boolean success, String detail);
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ShortcutExecutionRuntime() {}

    static boolean isRunning() { return RUNNING.get(); }

    static void executePlanAsync(final Context context,
                                 final String planId,
                                 final Callback callback) {
        ShortcutPlanStore.Plan plan = new ShortcutPlanStore(context).get(planId);
        if (plan == null) {
            complete(callback, false, "快捷指令內容不存在");
            return;
        }
        executeStepsAsync(context, plan.steps, callback);
    }

    static void executeStepsAsync(final Context context,
                                  final JSONArray steps,
                                  final Callback callback) {
        if (context == null || steps == null || steps.length() == 0) {
            complete(callback, false, "沒有可執行步驟");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            complete(callback, false, "另一個快捷指令正在執行");
            return;
        }

        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean success = false;
                String detail = "";
                try {
                    for (int i = 0; i < steps.length(); i++) {
                        JSONObject step = steps.optJSONObject(i);
                        if (step == null) throw new Exception("第 " + (i + 1) + " 步格式錯誤");
                        String type = step.optString("type", "");
                        if ("OPEN_APP".equals(type)) {
                            executeOpenApp(app, step);
                        } else if ("TAP_ELEMENT".equals(type)) {
                            executeTap(step);
                        } else {
                            throw new Exception("不支援的錄製步驟：" + type);
                        }
                    }
                    success = true;
                    detail = "快捷指令完成";
                } catch (Exception error) {
                    detail = error.getMessage() == null ? "快捷指令失敗" : error.getMessage();
                } finally {
                    RUNNING.set(false);
                }
                complete(callback, success, detail);
            }
        }, "CrewShortcutReplay").start();
    }

    private static void executeOpenApp(Context context, JSONObject step) throws Exception {
        String packageName = step.optString("packageName", "");
        if (packageName.isEmpty()) throw new Exception("缺少 App package");
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) throw new Exception("App 已不存在：" + packageName);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        if (!waitForPackage(packageName, 4000L)) {
            throw new Exception("沒有確認 App 已開啟：" + step.optString("label", packageName));
        }
    }

    private static void executeTap(JSONObject step) throws Exception {
        CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        if (service == null) throw new Exception("無障礙服務未啟用");

        AccessibilityNodeInfo target = waitForSelector(service, step, 3500L);
        if (target == null) {
            throw new Exception("找不到已錄製元素：" + step.optString("summary", "畫面元素"));
        }

        boolean editable = step.optBoolean("editable", false);
        AccessibilityNodeInfo clickable = target;
        while (clickable != null && !clickable.isClickable()) {
            clickable = clickable.getParent();
        }
        if (clickable == null) {
            throw new Exception("元素目前不可點擊：" + step.optString("summary", "畫面元素"));
        }

        boolean clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            throw new Exception("點擊失敗：" + step.optString("summary", "畫面元素"));
        }

        if (editable && !waitForFocused(service, step, 1500L)) {
            throw new Exception("輸入框沒有取得焦點：" + step.optString("summary", "輸入框"));
        }
        sleep(220L);
    }

    private static boolean waitForPackage(String packageName, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            CrewAccessibilityService service = CrewAccessibilityService.getInstance();
            if (service != null) {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null
                        && packageName.equals(root.getPackageName().toString())) {
                    return true;
                }
            }
            sleep(120L);
        }
        return false;
    }

    private static AccessibilityNodeInfo waitForSelector(CrewAccessibilityService service,
                                                         JSONObject step,
                                                         long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                String expectedPackage = step.optString("packageName", "");
                String actualPackage = root.getPackageName() == null
                        ? "" : root.getPackageName().toString();
                if (expectedPackage.isEmpty() || expectedPackage.equals(actualPackage)) {
                    AccessibilityNodeInfo found = findSelector(root, step);
                    if (found != null) return found;
                }
            }
            sleep(100L);
        }
        return null;
    }

    private static boolean waitForFocused(CrewAccessibilityService service,
                                          JSONObject step,
                                          long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            AccessibilityNodeInfo found = root == null ? null : findSelector(root, step);
            if (found != null && (found.isFocused() || found.isAccessibilityFocused())) return true;
            sleep(80L);
        }
        return false;
    }

    private static AccessibilityNodeInfo findSelector(AccessibilityNodeInfo root, JSONObject step) {
        if (root == null) return null;
        String viewId = step.optString("viewId", "");
        String desc = step.optString("contentDescription", "");
        boolean editable = step.optBoolean("editable", false);

        if (!viewId.isEmpty()) {
            AccessibilityNodeInfo exact = findByViewId(root, viewId);
            if (exact != null) return exact;
            return null;
        }
        if (!desc.isEmpty()) {
            AccessibilityNodeInfo exact = findByDescription(root, desc);
            if (exact != null) return exact;
            return null;
        }
        if (editable) {
            ArrayList<AccessibilityNodeInfo> editableNodes = new ArrayList<AccessibilityNodeInfo>();
            collectEditable(root, editableNodes, 3);
            return editableNodes.size() == 1 ? editableNodes.get(0) : null;
        }
        return null;
    }

    private static AccessibilityNodeInfo findByViewId(AccessibilityNodeInfo node, String viewId) {
        if (node == null) return null;
        CharSequence id = node.getViewIdResourceName();
        if (id != null && viewId.equals(id.toString())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByViewId(node.getChild(i), viewId);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo node, String desc) {
        if (node == null) return null;
        CharSequence value = node.getContentDescription();
        if (value != null && desc.equals(value.toString().trim())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByDescription(node.getChild(i), desc);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectEditable(AccessibilityNodeInfo node,
                                        List<AccessibilityNodeInfo> out,
                                        int limit) {
        if (node == null || out.size() >= limit) return;
        if (node.isEditable()) out.add(node);
        for (int i = 0; i < node.getChildCount() && out.size() < limit; i++) {
            collectEditable(node.getChild(i), out, limit);
        }
    }

    private static void complete(final Callback callback,
                                 final boolean success,
                                 final String detail) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                callback.onComplete(success, detail == null ? "" : detail);
            }
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
