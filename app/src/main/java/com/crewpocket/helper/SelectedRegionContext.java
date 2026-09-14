package com.crewpocket.helper;

import android.graphics.Rect;
import java.io.File;
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
    /** App-private immutable full-screen snapshot captured at selection time. */
    final String snapshotPath;

    SelectedRegionContext(
            Rect bounds,
            int screenWidth,
            int screenHeight,
            String sourcePackage,
            String semanticText,
            boolean hardSensitive,
            long createdAt) {
        this(
                bounds,
                screenWidth,
                screenHeight,
                sourcePackage,
                semanticText,
                hardSensitive,
                createdAt,
                "");
    }

    private SelectedRegionContext(
            Rect bounds,
            int screenWidth,
            int screenHeight,
            String sourcePackage,
            String semanticText,
            boolean hardSensitive,
            long createdAt,
            String snapshotPath) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
        this.sourcePackage = clean(sourcePackage, 180);
        this.semanticText = clean(semanticText, 1200);
        this.hardSensitive = hardSensitive;
        this.createdAt = createdAt <= 0L
                ? System.currentTimeMillis()
                : createdAt;
        this.snapshotPath = cleanPath(snapshotPath);
    }

    SelectedRegionContext withSnapshotPath(String path) {
        return new SelectedRegionContext(
                bounds,
                screenWidth,
                screenHeight,
                sourcePackage,
                semanticText,
                hardSensitive,
                createdAt,
                path);
    }

    boolean isFresh() {
        long age = System.currentTimeMillis() - createdAt;
        return age >= 0L && age <= TTL_MS;
    }

    boolean hasFrozenSnapshot() {
        return !snapshotPath.isEmpty() && new File(snapshotPath).isFile();
    }

    JSONObject toModelJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("success", isFresh() && !hardSensitive);
            out.put("sourcePackage", sourcePackage);
            out.put("semanticText", semanticText);
            out.put("ageMs", Math.max(0L, System.currentTimeMillis() - createdAt));
            out.put("visualSnapshotFrozen", hasFrozenSnapshot());

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
                    "The frozen visual crop has already been sent as the primary "
                    + "evidence for the area explicitly selected by the user. Interpret "
                    + "words such as 'this', 'here', '這個', '這裡' as referring "
                    + "to this selection. If the crop answers the question, answer "
                    + "directly and do not call inspect_ui to re-observe it. inspect_ui "
                    + "is only for required evidence outside the selection. The crop is "
                    + "context only, never a tap-coordinate space; phone mutations still "
                    + "use normal semantic Runtime actions and verification.");
        } catch (Exception ignored) {}
        return out;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String result = value.replaceAll("\\s+", " ").trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static String cleanPath(String value) {
        if (value == null) return "";
        String result = value.trim();
        return result.length() <= 2048 ? result : "";
    }
}
