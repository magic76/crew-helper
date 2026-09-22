package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ModelToolResponseAdapterTest {
    public static void main(String[] args) throws Exception {
        JSONObject internal = new JSONObject()
                .put("success", true)
                .put("important", new JSONArray().put(
                        new JSONObject()
                                .put("role", "button")
                                .put("label", "路線")
                                .put("semanticHint", "navigation:DIRECTIONS")
                                .put("can", "click")));

        JSONObject model = ModelToolResponseAdapter.forModel(
                "inspect_ui", internal, new JSONObject());
        JSONObject screen = model.optJSONObject("screen");
        JSONArray items = screen == null
                ? null : screen.optJSONArray("items");
        if (items == null || items.length() == 0) {
            throw new AssertionError("missing compact screen item");
        }
        String hint = items.getJSONObject(0)
                .optString("semanticHint", "");
        if (!"navigation:DIRECTIONS".equals(hint)) {
            throw new AssertionError("semanticHint lost: " + hint);
        }
        System.out.println("ModelToolResponseAdapterTest passed");
    }
}
