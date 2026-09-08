package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;

/** A one-step, deterministic voice shortcut for opening one installed app. */
final class AppLaunchShortcut {
    private static final String ACTION_PREFIX = "runtime_open_app:";

    private AppLaunchShortcut() {}

    static String actionFor(String packageName) {
        return ACTION_PREFIX + (packageName == null ? "" : packageName.trim());
    }

    static boolean isAction(String action) {
        return action != null && action.startsWith(ACTION_PREFIX)
                && action.length() > ACTION_PREFIX.length();
    }

    static String packageNameFromAction(String action) {
        return isAction(action) ? action.substring(ACTION_PREFIX.length()) : "";
    }

    static String launch(Context context, String packageName) throws Exception {
        if (context == null) throw new Exception("App Context 不可用");
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new Exception("快捷指令缺少 App");
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) throw new Exception("App 已不存在或無法開啟");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return "已開啟 " + appLabel(context, packageName);
    }

    static String describe(Context context, String action) {
        String packageName = packageNameFromAction(action);
        return packageName.isEmpty() ? "開啟 App" : "開啟 " + appLabel(context, packageName);
    }

    private static String appLabel(Context context, String packageName) {
        try {
            CharSequence label = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception ignored) {}
        return packageName;
    }
}
