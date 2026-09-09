package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Compact, model-facing projection of Runtime's full semantic screen state. */
final class ModelScreenView {
    private static final int MAX_IMPORTANT = 10;
    private static final int MAX_LABEL = 96;

    private static final class Ranked {
        final JSONObject source;
        final int score;
        Ranked(JSONObject source, int score) { this.source = source; this.score = score; }
    }

    private ModelScreenView() {}

    static JSONObject compact(JSONObject raw, String source) {
        if (raw == null) return new JSONObject();
        JSONObject out = new JSONObject();
        try {
            boolean success = raw.optBoolean("success", false);
            out.put("success", success).put("source", source == null ? "" : source).put("fresh", success);
            copyString(raw, out, "package");
            copyString(raw, out, "fingerprint");
            copyString(raw, out, "stableScreenKey");
            if (!success) {
                copyString(raw, out, "error");
                out.put("visionRecommended", raw.optBoolean("visionRecommended", false));
                copyString(raw, out, "visionReason");
                return out;
            }

            JSONArray elements = raw.optJSONArray("elements");
            ArrayList<Ranked> ranked = new ArrayList<Ranked>();
            JSONObject focused = null;
            if (elements != null) {
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i);
                    if (element == null) continue;
                    if (element.optBoolean("focused", false) && focused == null) focused = compactElement(element, false);
                    int score = importance(element);
                    if (score > 0) insertRanked(ranked, new Ranked(element, score));
                }
            }

            JSONArray important = new JSONArray();
            boolean includeIds = "EXPLICIT_INSPECT".equals(source);
            for (int i = 0; i < ranked.size() && i < MAX_IMPORTANT; i++) {
                important.put(compactElement(ranked.get(i).source, includeIds));
            }
            out.put("important", important).put("importantCount", important.length())
                    .put("hasMoreElements", elements != null && elements.length() > important.length())
                    .put("visionRecommended", raw.optBoolean("visionRecommended", false));
            copyString(raw, out, "visionReason");
            if (focused != null) out.put("focus", focused);
            JSONObject runtimeContext = raw.optJSONObject("runtimeContext");
            if (runtimeContext != null) out.put("runtimeContext", runtimeContext);
            if ("AUTO_AFTER_ACTION".equals(source)) {
                out.put("observationPolicy", "AUTO_AFTER_ACTION: this is already the fresh post-action screen. Do not call inspect_ui again after STEP_OK.");
            } else if ("EXPLICIT_INSPECT".equals(source)) {
                out.put("observationPolicy", "EXPLICIT_FALLBACK: use this state for exactly one next semantic action.");
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void insertRanked(ArrayList<Ranked> ranked, Ranked candidate) {
        int at = 0;
        while (at < ranked.size() && ranked.get(at).score >= candidate.score) at++;
        ranked.add(at, candidate);
        if (ranked.size() > MAX_IMPORTANT) ranked.remove(ranked.size() - 1);
    }

    private static int importance(JSONObject element) {
        if (!element.optBoolean("enabled", true)) return 0;
        int score = 0;
        String label = element.optString("label", "").trim();
        String hint = element.optString("semanticHint", "").trim();
        String role = element.optString("role", "");
        if (element.optBoolean("focused", false)) score += 120;
        if (element.optBoolean("editable", false)) score += 90;
        if (!hint.isEmpty()) score += 70;
        if (element.optBoolean("clickable", false)) score += 55;
        if (element.optBoolean("selected", false)) score += 25;
        if ("switch".equals(role) || "checkbox".equals(role) || "radio".equals(role)) score += 35;
        if (element.optBoolean("scrollable", false)) score += 18;
        if (!label.isEmpty()) score += 15;
        if (!element.optBoolean("clickable", false) && !element.optBoolean("editable", false)
                && !element.optBoolean("scrollable", false) && hint.isEmpty() && !"text".equals(role)) return 0;
        return score;
    }

    private static JSONObject compactElement(JSONObject source, boolean includeId) {
        JSONObject out = new JSONObject();
        try {
            String role = source.optString("role", "");
            String label = clip(source.optString("label", ""));

            // Runtime keeps semanticHint/viewId/focus/selection/sensitive/bounds
            // in the full SemanticScreenState. The model only needs identity,
            // human label, semantic role and what it can do next.
            if (includeId) copyString(source, out, "id");
            if (!role.isEmpty()) out.put("role", role);
            if (!label.isEmpty()) out.put("label", label);

            String can = capabilities(source);
            if (!can.isEmpty()) out.put("can", can);
        } catch (Exception ignored) {}
        return out;
    }

    private static String capabilities(JSONObject source) {
        StringBuilder can = new StringBuilder();
        if (source.optBoolean("clickable", false)) can.append("click");
        if (source.optBoolean("editable", false)) {
            if (can.length() > 0) can.append("|");
            can.append("type");
        }
        if (source.optBoolean("scrollable", false)) {
            if (can.length() > 0) can.append("|");
            can.append("scroll");
        }
        return can.toString();
    }

    private static void copyString(JSONObject from, JSONObject to, String key) {
        try { String value = from.optString(key, ""); if (!value.isEmpty()) to.put(key, value); }
        catch (Exception ignored) {}
    }

    private static String clip(String value) {
        String out = value == null ? "" : value.trim();
        return out.length() <= MAX_LABEL ? out : out.substring(0, MAX_LABEL);
    }
}
