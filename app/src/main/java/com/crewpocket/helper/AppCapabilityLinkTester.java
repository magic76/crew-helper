package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/** Safe preflight/test launcher used by the capability setup wizard. */
final class AppCapabilityLinkTester {
    private AppCapabilityLinkTester() {}

    static JSONObject inspect(
            Context context,
            String packageName,
            String rawUri) {
        JSONObject out = new JSONObject();
        try {
            if (context == null) {
                return out.put("success", false)
                        .put("error", "CONTEXT_REQUIRED");
            }
            String pkg = cleanPackage(packageName);
            if (pkg.isEmpty()) {
                return out.put("success", false)
                        .put("error", "PACKAGE_REQUIRED");
            }

            String uri = rawUri == null ? "" : rawUri.trim();
            AppCapabilityTemplate.validateUri(uri);

            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(uri));
            intent.setPackage(pkg);
            boolean resolvable =
                    intent.resolveActivity(
                            context.getPackageManager()) != null;

            return out.put("success", resolvable)
                    .put("resolvable", resolvable)
                    .put("package", pkg)
                    .put("uri", uri)
                    .put(
                            "message",
                            resolvable
                                    ? "這個連結可以交給目標 App 開啟"
                                    : "目前安裝版本沒有宣告可處理這個連結");
        } catch (Exception error) {
            try {
                out.put("success", false)
                        .put(
                                "error",
                                error.getMessage() == null
                                        ? "BAD_LINK"
                                        : error.getMessage());
            } catch (Exception ignored) {}
            return out;
        }
    }

    static JSONObject launch(
            Context context,
            String packageName,
            String rawUri) {
        JSONObject inspected =
                inspect(context, packageName, rawUri);
        if (!inspected.optBoolean("success", false)) {
            return inspected;
        }
        try {
            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(rawUri.trim()));
            intent.setPackage(cleanPackage(packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            inspected.put("launched", true);
            return inspected;
        } catch (Exception error) {
            try {
                inspected.put("success", false)
                        .put("launched", false)
                        .put("error", "TEST_LAUNCH_FAILED");
            } catch (Exception ignored) {}
            return inspected;
        }
    }

    private static String cleanPackage(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
