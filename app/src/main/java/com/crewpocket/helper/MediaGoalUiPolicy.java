package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Pure goal-aware UI hints for MEDIA:PLAY without app-specific selectors. */
final class MediaGoalUiPolicy {
    private MediaGoalUiPolicy() {}

    static boolean isMediaPlayGoal(String goalIntent) {
        return "MEDIA:PLAY".equals(clean(goalIntent));
    }

    static int scoreItem(
            JSONObject item,
            String goalIntent,
            String goalText) {
        if (item == null) return 0;
        int score = ScreenItemPriorityPolicy.score(
                item.optString("role", ""),
                item.optString("label", ""),
                item.optString("semanticHint", ""),
                item.optString("can", ""));
        if (!isMediaPlayGoal(goalIntent)) return score;

        String label = item.optString("label", "").trim();
        String hint = item.optString("semanticHint", "").trim();
        String metadata = label + " " + hint + " "
                + item.optString("can", "");

        if (MediaPlaybackCompletionPolicy.isPlayControl(metadata)
                || MediaPlaybackCompletionPolicy.isPlayControl(label)
                || MediaPlaybackCompletionPolicy.isPlayControl(hint)) {
            score += 500;
        }

        String normalizedLabel = normalize(label);
        String normalizedGoal = normalize(goalText);
        if (normalizedLabel.length() >= 2
                && !normalizedGoal.isEmpty()
                && (normalizedGoal.contains(normalizedLabel)
                    || normalizedLabel.contains(normalizedGoal))) {
            score += 260;
        }

        if (ScreenItemPriorityPolicy.isActionable(
                item.optString("role", ""),
                label,
                hint,
                item.optString("can", ""))) {
            score += 40;
        }
        return score;
    }

    static String uniquePlayTarget(JSONObject semanticScreen) {
        if (semanticScreen == null
                || !semanticScreen.optBoolean("success", false)) {
            return "";
        }

        JSONArray elements = semanticScreen.optJSONArray("elements");
        if (elements == null) return "";

        String target = "";
        int matches = 0;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item == null
                    || item.optBoolean("sensitive", false)
                    || !item.optBoolean("enabled", true)) {
                continue;
            }

            String role = item.optString("role", "");
            boolean actionable = item.optBoolean("clickable", false)
                    || "button".equals(role)
                    || "icon_button".equals(role);
            if (!actionable) continue;

            String label = item.optString("label", "").trim();
            String hint = item.optString("semanticHint", "").trim();
            String metadata = label + " " + hint;
            if (!MediaPlaybackCompletionPolicy.isPlayControl(metadata)
                    && !MediaPlaybackCompletionPolicy.isPlayControl(label)
                    && !MediaPlaybackCompletionPolicy.isPlayControl(hint)) {
                continue;
            }

            double confidence = item.optDouble("confidence", 0.0);
            if (confidence < 0.85) continue;

            matches++;
            if (matches > 1) return "";
            target = !label.isEmpty() ? label : hint;
        }
        return matches == 1 ? target : "";
    }

    static boolean looksLikeNumericOrdinalTarget(String target) {
        String value = clean(target);
        return !value.isEmpty() && value.matches("[0-9]{1,3}");
    }

    private static String normalize(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
