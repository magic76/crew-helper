package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Executes one declarative low-risk deep-link/Intent capability. */
final class AppCapabilityExecutor {
    private final Context context;
    private final AppCapabilityRegistry registry;
    private final AppCatalog appCatalog;

    AppCapabilityExecutor(
            Context context,
            AppCapabilityRegistry registry) {
        if (context == null) {
            throw new IllegalArgumentException("context required");
        }
        this.context = context.getApplicationContext();
        this.registry = registry;
        this.appCatalog = new AppCatalog(this.context);
    }

    JSONObject list(
            String packageName,
            String appName,
            String currentPackage) {
        PackageResolution resolved =
                resolvePackage(packageName, appName, currentPackage);
        if (!resolved.success) return resolved.error;

        JSONArray capabilities =
                registry.capabilitiesFor(resolved.packageName);
        try {
            return new JSONObject()
                    .put("success", true)
                    .put("package", resolved.packageName)
                    .put("app",
                            AppRuntimeRegistry.displayName(
                                    context,
                                    resolved.packageName))
                    .put("capabilities", compact(capabilities))
                    .put("count", capabilities.length())
                    .put(
                            "message",
                            capabilities.length() == 0
                                    ? "這個 App 目前沒有已知 deterministic capability；請使用一般 UI Agent。"
                                    : "只可使用列出的 capability id。");
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    JSONObject execute(
            JSONObject args,
            String currentPackage) {
        JSONObject safe = args == null ? new JSONObject() : args;
        PackageResolution resolved =
                resolvePackage(
                        safe.optString("package", ""),
                        safe.optString("app", ""),
                        currentPackage);
        if (!resolved.success) return resolved.error;

        String capabilityId =
                safe.optString("capability", "")
                        .trim()
                        .toUpperCase(java.util.Locale.ROOT);
        if (capabilityId.isEmpty()) {
            return failure(
                    "CAPABILITY_REQUIRED",
                    "先用 list_app_capabilities 取得可用 id。");
        }

        JSONObject capability =
                registry.find(resolved.packageName, capabilityId);
        if (capability == null) {
            return failure(
                    "CAPABILITY_NOT_FOUND",
                    "這個 App 沒有這個 capability；請重新 list_app_capabilities 或改用 UI Agent。");
        }

        String metadata =
                capabilityId + " "
                        + capability.optString("label", "") + " "
                        + capability.optString("uriTemplate", "");
        if (ActionSafetyPolicy.blocks(metadata)
                || PendingActionPolicy.looksLikeHighRiskCommit(metadata)) {
            return failure(
                    "CAPABILITY_SENSITIVE_BLOCKED",
                    "宣告式 capability 只允許低風險導向/開啟類動作；敏感提交必須回到正常 Runtime 安全流程。");
        }

        Map<String, String> params = new HashMap<String, String>();
        JSONObject inputParams = safe.optJSONObject("params");
        if (inputParams != null) {
            java.util.Iterator<String> keys = inputParams.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                params.put(key, inputParams.optString(key, ""));
            }
        }

        final String uri;
        try {
            uri = AppCapabilityTemplate.expand(
                    capability.optString("uriTemplate", ""),
                    params);
        } catch (Exception error) {
            return failure(
                    error.getMessage() == null
                            ? "CAPABILITY_TEMPLATE_FAILED"
                            : error.getMessage(),
                    "Capability 參數不足或 URI 格式不安全。");
        }

        String action =
                capability.optString("action", "").trim();
        if (action.isEmpty()) action = Intent.ACTION_VIEW;
        if (!isAllowedAction(action)) {
            return failure(
                    "CAPABILITY_INTENT_ACTION_BLOCKED",
                    "只允許 VIEW、SENDTO、DIAL 這類低風險外部 Intent。");
        }

        try {
            Intent intent =
                    new Intent(action, Uri.parse(uri));
            intent.setPackage(resolved.packageName);
            String mime = capability.optString("mimeType", "").trim();
            if (!mime.isEmpty()) intent.setType(mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(context.getPackageManager()) == null) {
                return failure(
                        "CAPABILITY_NOT_RESOLVABLE",
                        "目前安裝版本無法處理這個 capability；請改用 UI Agent。");
            }

            context.startActivity(intent);
            return new JSONObject()
                    .put("success", true)
                    .put("package", resolved.packageName)
                    .put("capability", capabilityId)
                    .put("source", capability.optString("source", ""))
                    .put("dispatch",
                            Intent.ACTION_VIEW.equals(action)
                                    ? "DEEP_LINK"
                                    : "ANDROID_INTENT")
                    .put("verified", false)
                    .put("taskState", "EVIDENCE_AVAILABLE")
                    .put(
                            "completionEvidence",
                            "APP_CAPABILITY_DISPATCHED")
                    .put("nextRequirement", "CONTINUE_GOAL")
                    .put(
                            "message",
                            "Capability 已交給目標 App；Runtime 仍需依實際 foreground/UI 驗證結果。");
        } catch (Exception error) {
            return failure(
                    "CAPABILITY_LAUNCH_FAILED",
                    error.getMessage() == null
                            ? "Capability launch failed"
                            : error.getMessage());
        }
    }

    private PackageResolution resolvePackage(
            String packageName,
            String appName,
            String currentPackage) {
        String pkg = cleanPackage(packageName);
        if (!pkg.isEmpty()) {
            return PackageResolution.ok(pkg);
        }

        String app = appName == null ? "" : appName.trim();
        if (!app.isEmpty()) {
            AppCatalog.Resolution resolution = appCatalog.resolve(app);
            if (AppCatalog.Resolution.FOUND.equals(resolution.status)
                    && resolution.chosen != null) {
                return PackageResolution.ok(
                        resolution.chosen.packageName);
            }
            JSONObject error = failure(
                    resolution.status,
                    "無法唯一解析 App；請指定 package 或更完整的 App 名稱。");
            try {
                JSONArray candidates = new JSONArray();
                for (AppCatalog.Entry entry : resolution.matches) {
                    candidates.put(new JSONObject()
                            .put("app", entry.label)
                            .put("package", entry.packageName));
                }
                error.put("candidates", candidates);
            } catch (Exception ignored) {}
            return PackageResolution.fail(error);
        }

        pkg = cleanPackage(currentPackage);
        if (!pkg.isEmpty()) return PackageResolution.ok(pkg);
        return PackageResolution.fail(
                failure(
                        "APP_PACKAGE_REQUIRED",
                        "請指定 app/package，或先讓目標 App 位於前景。"));
    }

    private JSONArray compact(JSONArray source) {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        for (int i = 0; i < source.length() && i < 24; i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            try {
                out.put(new JSONObject()
                        .put("id", item.optString("id", ""))
                        .put("label", item.optString("label", ""))
                        .put("params",
                                item.optJSONArray("params") == null
                                        ? new JSONArray()
                                        : item.optJSONArray("params"))
                        .put("source", item.optString("source", "")));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private boolean isAllowedAction(String action) {
        return Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_SENDTO.equals(action)
                || Intent.ACTION_DIAL.equals(action);
    }

    private static JSONObject failure(
            String error,
            String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("error", error)
                    .put("recoverable", true)
                    .put("taskState", "IN_PROGRESS")
                    .put("nextRequirement", "TRY_ALTERNATIVE")
                    .put("message", message);
        } catch (Exception ignored) {}
        return out;
    }

    private static String cleanPackage(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static final class PackageResolution {
        final boolean success;
        final String packageName;
        final JSONObject error;

        private PackageResolution(
                boolean success,
                String packageName,
                JSONObject error) {
            this.success = success;
            this.packageName = packageName == null ? "" : packageName;
            this.error = error == null ? new JSONObject() : error;
        }

        static PackageResolution ok(String packageName) {
            return new PackageResolution(true, packageName, null);
        }

        static PackageResolution fail(JSONObject error) {
            return new PackageResolution(false, "", error);
        }
    }
}
