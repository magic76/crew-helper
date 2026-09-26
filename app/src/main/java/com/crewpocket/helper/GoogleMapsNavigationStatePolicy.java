package com.crewpocket.helper;

import java.util.Locale;

/**
 * Conservative Google Maps terminal-state detector.
 *
 * A successful tap or changed screen is not navigation completion. Runtime
 * accepts only strong guidance-mode UI evidence. False negatives simply cause
 * one more observation; false positives would make Crew lie about navigation.
 */
final class GoogleMapsNavigationStatePolicy {
    private GoogleMapsNavigationStatePolicy() {}

    static boolean isActiveNavigationScreen(
            String packageName,
            String visibleEvidence) {
        if (!GoogleMapsRuntimeAdapter.PACKAGE_NAME.equals(
                clean(packageName))) {
            return false;
        }

        String text = normalize(visibleEvidence);
        if (text.isEmpty()) return false;

        // Explicit exit/end controls are the strongest cross-layout signal that
        // Maps has entered turn-by-turn guidance rather than route planning.
        if (containsAny(
                text,
                "exitnavigation",
                "endnavigation",
                "stopnavigation",
                "exitguidance",
                "endguidance",
                "退出導航",
                "退出导航",
                "結束導航",
                "结束导航",
                "停止導航",
                "停止导航",
                "ออกจากการนำทาง",
                "หยุดการนำทาง",
                "สิ้นสุดการนำทาง")) {
            return true;
        }

        // Some Maps builds expose only guidance controls. Require a pair so a
        // single generic route-planning control cannot satisfy the terminal
        // check.
        int guidanceSignals = 0;
        if (containsAny(
                text,
                "recenter",
                "re-centre",
                "重新置中",
                "重新居中",
                "จัดกึ่งกลาง")) {
            guidanceSignals++;
        }
        if (containsAny(
                text,
                "routeoverview",
                "overviewroute",
                "路線總覽",
                "路线总览",
                "ภาพรวมเส้นทาง")) {
            guidanceSignals++;
        }
        if (containsAny(
                text,
                "muteguidance",
                "unmuteguidance",
                "voiceguidance",
                "guidancevolume",
                "語音導航",
                "语音导航",
                "เสียงนำทาง")) {
            guidanceSignals++;
        }
        if (containsAny(
                text,
                "reportincident",
                "reporttraffic",
                "回報路況",
                "报告路况",
                "รายงานเหตุการณ์")) {
            guidanceSignals++;
        }

        return guidanceSignals >= 2;
    }

    private static boolean containsAny(
            String value,
            String... needles) {
        for (String needle : needles) {
            String normalized = normalize(needle);
            if (!normalized.isEmpty()
                    && value.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_\\-]+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
