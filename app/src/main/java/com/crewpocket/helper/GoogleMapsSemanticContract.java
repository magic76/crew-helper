package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Stable shared vocabulary between Gemini Live and the Google Maps Runtime. */
final class GoogleMapsSemanticContract {
    static final String ROUTE_MODE_DRIVING = "route_mode:DRIVING";
    static final String ROUTE_MODE_TRANSIT = "route_mode:TRANSIT";
    static final String ROUTE_MODE_WALKING = "route_mode:WALKING";
    static final String ROUTE_MODE_CYCLING = "route_mode:CYCLING";
    static final String START_NAVIGATION = "navigation:START";
    static final String DIRECTIONS = "navigation:DIRECTIONS";

    private static final List<AppSemanticConcept> CONCEPTS;
    static {
        ArrayList<AppSemanticConcept> concepts = new ArrayList<AppSemanticConcept>();
        concepts.add(new AppSemanticConcept(
                ROUTE_MODE_DRIVING, "TAP", "汽車／開車", "Driving",
                "driving", "drive", "car", "汽車", "車子", "開車", "駕車",
                "รถยนต์", "ขับรถ"));
        concepts.add(new AppSemanticConcept(
                ROUTE_MODE_TRANSIT, "TAP", "大眾運輸", "Transit",
                "transit", "public transport", "public transportation",
                "大眾運輸", "公共交通", "公交", "ขนส่งสาธารณะ"));
        concepts.add(new AppSemanticConcept(
                ROUTE_MODE_WALKING, "TAP", "步行", "Walking",
                "walking", "walk", "步行", "走路", "เดิน"));
        concepts.add(new AppSemanticConcept(
                ROUTE_MODE_CYCLING, "TAP", "單車／自行車", "Cycling",
                "cycling", "bicycle", "bike", "單車", "自行車", "腳踏車", "จักรยาน"));
        concepts.add(new AppSemanticConcept(
                DIRECTIONS, "TAP", "路線", "Directions",
                "directions", "route", "路線", "路徑", "導航路線"));
        concepts.add(new AppSemanticConcept(
                START_NAVIGATION, "TAP", "開始導航", "Start navigation",
                "start navigation", "start", "navigate", "開始導航", "開始", "導航", "เริ่ม"));
        CONCEPTS = Collections.unmodifiableList(concepts);
    }

    private GoogleMapsSemanticContract() {}

    static List<AppSemanticConcept> concepts() {
        return CONCEPTS;
    }

    /** Returns a stable concept id, or empty when this is not a Maps concept. */
    static String canonicalTarget(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return "";
        for (AppSemanticConcept concept : CONCEPTS) {
            if (normalize(concept.id).equals(normalized)) return concept.id;
            if (normalize(concept.labelZh).equals(normalized)
                    || normalize(concept.labelEn).equals(normalized)) {
                return concept.id;
            }
            for (String alias : concept.aliases) {
                if (normalize(alias).equals(normalized)) return concept.id;
            }
        }
        return "";
    }

    static boolean isCanonicalId(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return false;
        for (AppSemanticConcept concept : CONCEPTS) {
            if (normalize(concept.id).equals(normalized)) return true;
        }
        return false;
    }

    /**
     * Accessibility-first fallback label used when Live sends the canonical id.
     * This is not the memory itself; learned selectors may replace it later.
     */
    static String runtimeLabel(String canonicalId) {
        if (ROUTE_MODE_DRIVING.equals(canonicalId)) return "Driving";
        if (ROUTE_MODE_TRANSIT.equals(canonicalId)) return "Transit";
        if (ROUTE_MODE_WALKING.equals(canonicalId)) return "Walking";
        if (ROUTE_MODE_CYCLING.equals(canonicalId)) return "Cycling";
        if (DIRECTIONS.equals(canonicalId)) return "Directions";
        if (START_NAVIGATION.equals(canonicalId)) return "Start";
        return canonicalId == null ? "" : canonicalId.trim();
    }

    static String modelGuidance() {
        StringBuilder out = new StringBuilder();
        out.append("Maps semantic targets: use these exact target ids with phone_action TAP when the intent matches. ");
        for (int i = 0; i < CONCEPTS.size(); i++) {
            if (i > 0) out.append(" | ");
            out.append(CONCEPTS.get(i).modelLine());
        }
        out.append(". These ids describe WHAT; Runtime owns the current icon/node selector and verification.");
        return out.toString();
    }

    static String displayGuidance(boolean chinese) {
        StringBuilder out = new StringBuilder();
        out.append(chinese
                ? "Live 與 Runtime 共用的語意目標：\n"
                : "Semantic targets shared by Live and Runtime:\n");
        for (AppSemanticConcept concept : CONCEPTS) {
            out.append("• ").append(concept.displayLine(chinese)).append('\n');
        }
        out.append(chinese
                ? "這些名稱代表使用者意圖；實際 icon、Accessibility node、位置與驗證由 Runtime 處理。"
                : "These names represent user intent; Runtime owns the actual icon, Accessibility node, position and verification.");
        return out.toString();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!：:；;、_\\-/]+", "");
    }
}
