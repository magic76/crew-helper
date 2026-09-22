package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ModelScreenViewPriorityTest {
    public static void main(String[] args) throws Exception {
        JSONObject raw = new JSONObject()
                .put("success", true)
                .put("package", "com.google.android.apps.maps");

        JSONArray elements = new JSONArray();
        for (int i = 0; i < 12; i++) {
            elements.put(new JSONObject()
                    .put("role", "button")
                    .put("label", "Generic " + i)
                    .put("clickable", true)
                    .put("enabled", true));
        }
        elements.put(new JSONObject()
                .put("role", "button")
                .put("label", "路線")
                .put("clickable", true)
                .put("enabled", true));
        elements.put(new JSONObject()
                .put("role", "button")
                .put("label", "開始")
                .put("clickable", true)
                .put("enabled", true));

        raw.put("elements", elements);
        JSONObject compact = ModelScreenView.compact(raw, "EXPLICIT_INSPECT");
        JSONArray important = compact.getJSONArray("important");

        expectContains(important, "路線");
        expectContains(important, "開始");

        String first = important.getJSONObject(0).optString("label", "");
        if (!"路線".equals(first) && !"開始".equals(first)) {
            throw new AssertionError(
                    "navigation control should rank first, got " + first);
        }

        System.out.println("ModelScreenViewPriorityTest passed");
    }

    private static void expectContains(JSONArray items, String label) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && label.equals(item.optString("label", ""))) {
                return;
            }
        }
        throw new AssertionError("missing prioritized item " + label);
    }
}
