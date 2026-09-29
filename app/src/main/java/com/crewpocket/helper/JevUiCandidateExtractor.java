package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a small transient set of UI entity candidates for Jev.
 * Candidate labels are never persisted by Crew telemetry.
 */
final class JevUiCandidateExtractor {
    private static final int MAX_CANDIDATES = 10;

    private JevUiCandidateExtractor() {}

    static JSONArray extract(JSONObject semanticScreen) {
        JSONArray out = new JSONArray();
        if (semanticScreen == null
                || !semanticScreen.optBoolean("success", false)) {
            return out;
        }

        String pkg = semanticScreen.optString("package", "");
        if (isPrivateMessagingPackage(pkg)) return out;

        JSONArray elements = semanticScreen.optJSONArray("elements");
        if (elements == null) return out;

        Set<String> seen = new HashSet<String>();
        for (int i = 0;
             i < elements.length() && out.length() < MAX_CANDIDATES;
             i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item == null
                    || item.optBoolean("sensitive", false)
                    || item.optBoolean("editable", false)) {
                continue;
            }

            String label = clean(item.optString("label", ""));
            if (!usefulLabel(label)) continue;

            String folded = label.toLowerCase(Locale.ROOT);
            if (!seen.add(folded)) continue;

            try {
                out.put(new JSONObject()
                        .put("id", "C" + (out.length() + 1))
                        .put("label", label)
                        .put("role", clean(item.optString("role", "")))
                        .put("semanticHint",
                                clean(item.optString("semanticHint", ""))));
            } catch (Exception ignored) {}
        }
        return out;
    }

    static JSONArray fromSearchOptions(JSONArray options) {
        JSONArray out = new JSONArray();
        if (options == null) return out;
        Set<String> seen = new HashSet<String>();
        for (int i = 0;
             i < options.length() && out.length() < MAX_CANDIDATES;
             i++) {
            JSONObject item = options.optJSONObject(i);
            if (item == null) continue;
            String label = clean(item.optString("label", ""));
            if (!usefulLabel(label)) continue;
            String folded = label.toLowerCase(Locale.ROOT);
            if (!seen.add(folded)) continue;
            try {
                out.put(new JSONObject()
                        .put("id", "C" + (out.length() + 1))
                        .put("label", label)
                        .put("sourceIndex", i));
            } catch (Exception ignored) {}
        }
        return out;
    }

    static int sourceIndex(JSONArray candidates, String candidateId) {
        if (candidates == null || candidateId == null) return -1;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item != null
                    && candidateId.equals(item.optString("id", ""))) {
                return item.optInt("sourceIndex", -1);
            }
        }
        return -1;
    }

    private static boolean usefulLabel(String label) {
        if (label.length() < 2 || label.length() > 80) return false;
        String value = label.toLowerCase(Locale.ROOT);
        if ("[redacted]".equals(value)) return false;
        return !value.matches(
                "^(搜尋|搜索|search|返回|back|更多|more|播放|play|暫停|暂停|pause|開始|开始|start|取消|cancel|確定|确定|ok)$");
    }

    private static boolean isPrivateMessagingPackage(String pkg) {
        String value = clean(pkg).toLowerCase(Locale.ROOT);
        return value.contains("whatsapp")
                || value.contains("telegram")
                || value.contains("facebook.orca")
                || value.contains("messaging")
                || value.contains("naver.line");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
