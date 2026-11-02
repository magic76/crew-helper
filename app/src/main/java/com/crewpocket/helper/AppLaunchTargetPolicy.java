package com.crewpocket.helper;

import java.util.Locale;

/**
 * Exact, non-generative app-name shortcuts. Never interpret an entire spoken
 * sentence as a package name: model-supplied targets must already be App names.
 * Shared by foreground App launching and the virtual-display catalog.
 */
final class AppLaunchTargetPolicy {
    private AppLaunchTargetPolicy() {}

    static String knownPackage(String rawName) {
        if (rawName == null) return "";
        String raw = rawName.trim();
        if (raw.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return raw;
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、：；（）()!?,;:]+", " ")
                .trim();
        switch (normalized) {
            case "google maps":
            case "google map":
            case "google 地圖":
            case "google 地图":
            case "googlemaps":
            case "maps":
            case "地圖":
            case "地图":
                return "com.google.android.apps.maps";
            case "apple music":
            case "蘋果音樂":
            case "苹果音乐":
                return "com.apple.android.music";
            case "chrome":
            case "google chrome":
                return "com.android.chrome";
            case "youtube":
                return "com.google.android.youtube";
            default:
                return "";
        }
    }

    /**
     * A narrowly bounded fallback for segmented speech such as:
     * "那 你 前 进 打 开 Google Maps". Do not extract from unrelated
     * sentences, negations, compound goals, or multiple app names.
     */
    static String knownPackageForOpenTarget(String text) {
        String exact = knownPackage(text);
        if (!exact.isEmpty()) return exact;
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) return "";
        for (String blocked : new String[]{"不要", "不准", "別", "别",
                "don't", "do not", "not open", "然後", "然后",
                "接著", "接着", "順便", "顺便", "搜尋", "搜索",
                "導航", "导航", "播放", "payment", "delete"}) {
            if (s.contains(blocked)) return "";
        }
        if (!s.matches("(?s).*(打\\s*[開开]|[開开]\\s*[啟启]|"
                + "[啟启]\\s*[動动]|幫\\s*我\\s*[開开]|帮\\s*我\\s*[開开]|"
                + "(?<![a-z])open(?![a-z])).*")) return "";
        boolean maps = s.contains("google maps") || s.contains("google map")
                || s.contains("地圖") || s.contains("地图");
        boolean music = s.contains("apple music") || s.contains("蘋果音樂")
                || s.contains("苹果音乐");
        boolean chrome = s.contains("chrome");
        boolean youtube = s.contains("youtube");
        int named = (maps ? 1 : 0) + (music ? 1 : 0)
                + (chrome ? 1 : 0) + (youtube ? 1 : 0);
        if (named != 1) return "";
        return maps ? "com.google.android.apps.maps"
                : music ? "com.apple.android.music"
                : chrome ? "com.android.chrome"
                : "com.google.android.youtube";
    }

    static boolean observedOnExpectedPackage(String expected, String observed) {
        return expected != null && !expected.isEmpty()
                && expected.equals(observed);
    }

    static String failureInstruction(String code) {
        if ("APP_NOT_FOUND".equals(code) || "LAUNCH_INTENT_NOT_FOUND".equals(code)) {
            return "找不到可啟動的 App，請確認已安裝，或改用完整 App 名稱。不要反覆執行相同啟動命令。";
        }
        if ("LAUNCH_SECURITY_DENIED".equals(code)) {
            return "Android 拒絕啟動這個 App，需使用者檢查手機限制；不要透過點擊舊畫面繞過。";
        }
        if ("LAUNCH_TIMED_OUT".equals(code) || "LAUNCH_MAIN_HANDLER_UNAVAILABLE".equals(code)) {
            return "Android 啟動服務沒有及時回應。先重新觀察目前前景 App，不要盲目重複啟動。";
        }
        return "";
    }
}
