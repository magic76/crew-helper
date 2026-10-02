package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;

/**
 * Runtime view of app capabilities.
 *
 * Priority: local user definition > synced remote definition > bounded standard
 * Android intent probe. Unknown apps simply return no capabilities and keep the
 * normal Accessibility/Vision Agent fallback.
 */
final class AppCapabilityRegistry {
    private final Context context;
    private final AppCapabilityStore store;

    AppCapabilityRegistry(Context context, AppCapabilityStore store) {
        this.context = context == null ? null : context.getApplicationContext();
        this.store = store == null
                ? new AppCapabilityStore(this.context)
                : store;
    }

    JSONArray capabilitiesFor(String packageName) {
        String pkg = clean(packageName);
        JSONArray out = new JSONArray();
        HashSet<String> seen = new HashSet<String>();

        append(out, seen, store.mergedFor(pkg));
        append(out, seen, AppCapabilityProbe.detect(context, pkg));
        return out;
    }

    JSONObject find(String packageName, String capabilityId) {
        String id = cleanId(capabilityId);
        JSONArray list = capabilitiesFor(packageName);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) {
                try { return new JSONObject(item.toString()); }
                catch (Exception ignored) { return item; }
            }
        }
        return null;
    }

    JSONObject modelContext(String packageName) {
        String pkg = clean(packageName);
        JSONArray source = capabilitiesFor(pkg);
        if (pkg.isEmpty() || source.length() == 0) return new JSONObject();

        JSONArray compact = new JSONArray();
        for (int i = 0; i < source.length() && compact.length() < 16; i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            try {
                compact.put(new JSONObject()
                        .put("id", item.optString("id", ""))
                        .put("label", item.optString("label", ""))
                        .put("params",
                                item.optJSONArray("params") == null
                                        ? new JSONArray()
                                        : item.optJSONArray("params"))
                        .put("source", item.optString("source", "")));
            } catch (Exception ignored) {}
        }

        try {
            return new JSONObject()
                    .put("package", pkg)
                    .put("app", store.labelFor(pkg))
                    .put("capabilities", compact)
                    .put(
                            "instruction",
                            "Use run_app_capability only with an id listed here. "
                                    + "Never invent capability ids or URI templates. "
                                    + "If a capability is absent, use normal phone actions.");
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    String systemInstruction(String packageName) {
        JSONObject context = modelContext(packageName);
        JSONArray list = context.optJSONArray("capabilities");
        if (list == null || list.length() == 0) return "";

        StringBuilder out = new StringBuilder();
        out.append("APP CAPABILITIES: ")
                .append(context.optString("app", packageName))
                .append(" [")
                .append(context.optString("package", packageName))
                .append("]\n");
        for (int i = 0; i < list.length() && i < 12; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            out.append("- ")
                    .append(item.optString("id", ""));
            JSONArray params = item.optJSONArray("params");
            if (params != null && params.length() > 0) {
                out.append("(");
                for (int j = 0; j < params.length(); j++) {
                    if (j > 0) out.append(",");
                    out.append(params.optString(j, ""));
                }
                out.append(")");
            }
            String label = item.optString("label", "");
            if (!label.isEmpty()) out.append(": ").append(label);
            out.append("\n");
        }
        out.append("Use run_app_capability only for the listed ids; "
                + "otherwise fall back to normal semantic phone actions.");
        String text = out.toString();
        return text.length() <= 1600 ? text : text.substring(0, 1600);
    }

    private void append(
            JSONArray out,
            HashSet<String> seen,
            JSONArray source) {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) continue;
            String id = cleanId(item.optString("id", ""));
            if (id.isEmpty() || !seen.add(id)) continue;
            out.put(item);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanId(String value) {
        return clean(value).toUpperCase(java.util.Locale.ROOT);
    }
}
