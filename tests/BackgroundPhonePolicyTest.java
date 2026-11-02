package com.crewpocket.helper;

public final class BackgroundPhonePolicyTest {
    private static void ok(boolean b, String m) {
        if (!b) throw new AssertionError(m);
    }
    public static void main(String[] args) {
        ok(!BackgroundPhonePolicy.explicitlyBackground("打開音樂"), "front default");
        ok(!BackgroundPhonePolicy.explicitlyBackground("背景音樂繼續播放"), "music background not virtual");
        ok(!BackgroundPhonePolicy.explicitlyBackground("不要在背景打開音樂"), "negated background");
        ok(!BackgroundPhonePolicy.explicitlyBackground("不要用背景螢幕"), "negated display");
        ok(BackgroundPhonePolicy.explicitlyBackground("在背景點播放"), "explicit background action");
        ok(!BackgroundPhonePolicy.explicitlyHandoff("給我看搜尋結果"), "inspection not handoff");
        ok(!BackgroundPhonePolicy.explicitlyHandoff("不要切到主螢幕"), "negated handoff");
        ok(!BackgroundPhonePolicy.explicitlyClose("不要關閉背景"), "negated close");
        ok(BackgroundPhonePolicy.blocksForegroundTool("phone_action",
                "在背景螢幕開啟地圖"), "reject foreground fallback");
        ok(!BackgroundPhonePolicy.blocksForegroundTool("phone_action",
                "背景音樂繼續播放"), "normal music playback");
        ok(!BackgroundPhonePolicy.blocksForegroundTool("background_phone",
                "在背景螢幕開啟地圖"), "background tool authorized");
        ok(BackgroundPhonePolicy.explicitlyBackground("在背景螢幕打開音樂"), "background intent");
        ok("BACKGROUND_LIVE_DISABLED".equals(
                BackgroundPhonePolicy.deny("OPEN_APP", "在背景開地圖", false, false, false)), "opt-in");
        ok("BACKGROUND_REQUEST_REQUIRED".equals(
                BackgroundPhonePolicy.deny("OPEN_APP", "開地圖", true, false, false)), "explicit");
        ok("".equals(BackgroundPhonePolicy.deny("OPEN_APP", "在背景開地圖", true, false, false)), "open");
        ok("".equals(BackgroundPhonePolicy.deny("TAP", "在背景點播放", true, true, false)), "tap");
        ok("".equals(BackgroundPhonePolicy.deny("TAP", "播放", true, true, true)), "same turn");
        ok("BACKGROUND_REQUEST_REQUIRED".equals(
                BackgroundPhonePolicy.deny("TAP", "播放", true, true, false)), "new intent");
        ok("HANDOFF_REQUIRES_USER_REQUEST".equals(
                BackgroundPhonePolicy.deny("HANDOFF", "播放", true, true, true)), "handoff");
        ok("".equals(BackgroundPhonePolicy.deny("HANDOFF", "交給我看", true, true, true)), "handoff yes");
        ok("".equals(BackgroundPhonePolicy.deny("CLOSE", "關閉背景", true, true, true)), "close");
        ok(BackgroundPhonePolicy.isSensitiveTarget("付款"), "payment");
        ok(!BackgroundPhonePolicy.isSensitiveTarget("播放"), "play");
        System.out.println("BackgroundPhonePolicyTest OK");
    }
}
