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
