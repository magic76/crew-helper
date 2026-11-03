package com.crewpocket.helper;

public final class BackgroundToolRoutePolicyTest {
    private static int checks;
    private static void eq(BackgroundToolRoutePolicy.Decision want,
                           BackgroundToolRoutePolicy.Decision got, String reason) {
        checks++;
        if (want != got) throw new AssertionError(reason + ": expected " + want + ", got " + got);
    }
    public static void main(String[] args) {
        BackgroundToolRoutePolicy.Decision F =
                BackgroundToolRoutePolicy.Decision.FOREGROUND;
        BackgroundToolRoutePolicy.Decision R =
                BackgroundToolRoutePolicy.Decision.REDIRECT_BACKGROUND_OPEN;
        BackgroundToolRoutePolicy.Decision M =
                BackgroundToolRoutePolicy.Decision.BLOCK_BACKGROUND_ROUTE_MISMATCH;
        BackgroundToolRoutePolicy.Decision D =
                BackgroundToolRoutePolicy.Decision.BLOCK_BACKGROUND_DISABLED;
        BackgroundToolRoutePolicy.Decision B =
                BackgroundToolRoutePolicy.Decision.BACKGROUND_TOOL;
        eq(F, BackgroundToolRoutePolicy.decide("打開 Apple Music", true,
                "phone_action", "OPEN_APP", "Apple Music"), "ordinary foreground");
        eq(F, BackgroundToolRoutePolicy.decide("背景音樂繼續播放", true,
                "phone_action", "OPEN_APP", "Apple Music"), "background playback is not virtual");
        eq(F, BackgroundToolRoutePolicy.decide("不要在背景開 Apple Music", true,
                "phone_action", "OPEN_APP", "Apple Music"), "negation");
        eq(R, BackgroundToolRoutePolicy.decide("在背景螢幕開啟 Apple Music", true,
                "phone_action", "OPEN_APP", "Apple Music"), "explicit opt-in redirect");
        eq(D, BackgroundToolRoutePolicy.decide("在背景螢幕開啟 Apple Music", false,
                "phone_action", "OPEN_APP", "Apple Music"), "not enabled");
        eq(M, BackgroundToolRoutePolicy.decide("在背景螢幕開啟 Apple Music", true,
                "phone_action", "OPEN_APP", ""), "no app target");
        eq(M, BackgroundToolRoutePolicy.decide("在背景點播放", true,
                "phone_action", "TAP", "播放"), "must never remap tap");
        eq(M, BackgroundToolRoutePolicy.decide("在背景顯示畫面", true,
                "inspect_ui", "", ""), "foreground inspection forbidden");
        eq(M, BackgroundToolRoutePolicy.decide("在背景開地圖", true,
                "run_app_capability", "", ""), "no deep link foreground bypass");
        eq(M, BackgroundToolRoutePolicy.decide("在背景點播放", true,
                "send_text", "", ""), "no foreground send");
        eq(M, BackgroundToolRoutePolicy.decide("在背景打開 Google Maps", true,
                "launch_app", "OPEN_APP", "Google Maps"), "unmapped legacy launch blocked");
        eq(B, BackgroundToolRoutePolicy.decide("在背景打開地圖", true,
                "background_phone", "OPEN_APP", ""), "normal background tool allowed through");
        eq(F, BackgroundToolRoutePolicy.decide("在背景打開地圖", true,
                "inspect_runtime_trace", "", ""), "read-only diagnostics unaffected");
        if (!"OTHER".equals(BackgroundToolRoutePolicy.safeReceivedTool("token-value")))
            throw new AssertionError("unknown tool may not enter trace");
        if (!"NONE".equals(BackgroundToolRoutePolicy.safeReceivedAction("private text")))
            throw new AssertionError("arbitrary action may not enter trace");
        BackgroundToolRouteTrace.record(R, "phone_action", "OPEN_APP");
        BackgroundToolRouteTrace.record(M, "inspect_ui", "private payload");
        String report = BackgroundToolRouteTrace.report();
        if (!report.contains("redirect=1") || !report.contains("mismatch=1")
                || !report.contains("expected=background_phone.INSPECT")
                || report.contains("private payload")) throw new AssertionError("sanitized trace");
        System.out.println("BackgroundToolRoutePolicyTest passed " + checks + " route checks");
    }
}
