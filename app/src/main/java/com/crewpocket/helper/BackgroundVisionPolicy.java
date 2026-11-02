package com.crewpocket.helper;

import java.util.Locale;

/**
 * Fail-closed policy for screenshot-guided TAP on an isolated virtual display.
 * Never authorizes general coordinate execution or operations on display 0.
 */
final class BackgroundVisionPolicy {
    static final int MAX_FRAMES = 3;
    static final long MAX_FRAME_AGE_MS = 1500L;
    static final long TAP_LEASE_MS = 8_000L;
    static final int MAX_JPEG_BYTES = 750_000;

    private BackgroundVisionPolicy() {}

    static boolean permittedPackage(String pkg) {
        return "com.apple.android.music".equals(pkg)
                || "com.google.android.apps.maps".equals(pkg);
    }

    static boolean explicitVisualRequest(String text) {
        String v = lower(text);
        return v.contains("看背景") || v.contains("背景畫面")
                || v.contains("背景螢幕畫面") || v.contains("看一下")
                || v.contains("截圖") || v.contains("視覺")
                || v.contains("看看") || v.contains("look at")
                || v.contains("screen image") || v.contains("screenshot");
    }

    static boolean lowRiskTarget(String target) {
        String t = lower(target);
        if (t.length() < 2 || t.length() > 65
                || BackgroundPhonePolicy.isSensitiveTarget(t)
                || containsAny(t, "送信", "傳訊", "儲存", "確認", "同意",
                        "授權", "subscribe", "follow", "publish", "save",
                        "confirm", "accept", "permission", "account",
                        "login", "sign in", "sign out", "logout", "安裝",
                        "下載", "download", "install", "付款", "buy", "訂閱"))
            return false;
        return containsAny(t, "播放", "暫停", "上一首", "下一首",
                "搜尋", "搜索", "返回", "上一頁", "放大", "縮小",
                "play", "pause", "search", "back", "previous",
                "next track", "skip", "zoom", "magnify");
    }

    static boolean validCoordinate(double normalized) {
        return Double.isFinite(normalized) && normalized >= 0d && normalized <= 1000d;
    }

    static int coordinate(double normalized, int extent) {
        if (!validCoordinate(normalized) || extent <= 0)
            throw new IllegalArgumentException("Invalid visual coordinate");
        return Math.min(extent - 1, Math.max(0, (int) Math.round(
                normalized * (extent - 1) / 1000d)));
    }

    static boolean fresh(long now, long timestamp, long ttl) {
        long diff = now - timestamp;
        return timestamp > 0 && diff >= 0 && diff <= ttl;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String t, String... words) {
        for (String word : words) if (t.contains(word)) return true;
        return false;
    }
}
