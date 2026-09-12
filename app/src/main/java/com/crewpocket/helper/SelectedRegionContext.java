package com.crewpocket.helper;

import android.graphics.Rect;
import org.json.JSONObject;

/**
 * Ephemeral user-selected screen region.
 *
 * This is a reference/context object, not an execution target. Runtime must
 * never convert this crop directly into a tap coordinate. Phone mutations
 * continue through semantic Runtime resolution and normal verification.
 */
final class SelectedRegionContext {
    static final long TTL_MS = 120_000L;

    final Rect bounds;
    final int screenWidth;
    final int screenHeight;
    final String sourcePackage;
    final String semanticText;
    final boolean hardSensitive;
    final long createdAt;

    SelectedRegionContext(
            Rect bounds,
            int screenWidth,
            int screenHeight,
            String sourcePackage,
            String semanticText,
            boolean hardSensitive,
            long createdAt) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
        this.sourcePackage = clean(sourcePackage, 180);
        this.semanticText = clean(semanticText, 1200);
        this.hardSensitive = hardSensitive;
        this.createdAt = createdAt <= 0L
                ? System.currentTimeMillis()
                : createdAt;
    }

    boolean isFresh() {
        long age = System.currentTimeMillis() - createdAt;
        return age >= 0L && age <= TTL_MS;
    }

    JSONObject toModelJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("success", isFresh() && !hardSensitive);
            out.put("sourcePackage", sourcePackage);
            out.put("semanticText", semanticText);
            out.put("ageMs", Math.max(0L, System.currentTimeMillis() - createdAt));

            JSONObject region = new JSONObject();
            region.put("left", bounds.left);
            region.put("top", bounds.top);
            region.put("right", bounds.right);
            region.put("bottom", bounds.bottom);
            out.put("bounds", region);

            JSONObject normalized = new JSONObject();
            normalized.put(
                    "left",
                    Math.round(bounds.left * 1000f / screenWidth));
            normalized.put(
                    "top",
                    Math.round(bounds.top * 1000f / screenHeight));
            normalized.put(
                    "right",
                    Math.round(bounds.right * 1000f / screenWidth));
            normalized.put(
                    "bottom",
                    Math.round(bounds.bottom * 1000f / screenHeight));
            out.put("normalized1000", normalized);

            out.put(
                    "instruction",
                    "The latest visual crop is the area explicitly selected by "
                    + "the user. Interpret words such as 'this', 'here', '這個', "
                    + "'這裡' as referring to this selection. The crop is context "
                    + "only, never a tap-coordinate space. Use normal semantic "
                    + "Runtime actions and fresh inspect_ui verification for phone actions.");
        } catch (Exception ignored) {}
        return out;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String result = value.replaceAll("\\s+", " ").trim();
        return result.length() <= max ? result : result.substring(0, max);
    }
}
