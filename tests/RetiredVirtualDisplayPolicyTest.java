package com.crewpocket.helper;

public final class RetiredVirtualDisplayPolicyTest {
    private static void verify(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        verify(RetiredVirtualDisplayPolicy.mustBlock("在背景開啟 Apple Music", "phone_action"),
                "explicit background open may not launch foreground");
        verify(RetiredVirtualDisplayPolicy.mustBlock("虛擬螢幕上按播放", "tap_screen"),
                "virtual tap may not touch physical display");
        verify(RetiredVirtualDisplayPolicy.mustBlock("virtual display open maps", "launch_app"),
                "virtual launch may not become physical");
        verify(RetiredVirtualDisplayPolicy.mustBlock("", "background_phone"),
                "stale model tool is rejected");
        verify(!RetiredVirtualDisplayPolicy.mustBlock("打開 Apple Music", "phone_action"),
                "normal foreground App launch preserved");
        verify(!RetiredVirtualDisplayPolicy.mustBlock("幫我檢查錯誤", "inspect_runtime_trace"),
                "read-only diagnostics preserved");
        verify(!RetiredVirtualDisplayPolicy.mustBlock("在背景播放", "inspect_runtime_trace"),
                "safe diagnostic in explicit background context preserved");
        verify(!RetiredVirtualDisplayPolicy.mustBlock("建立背景提醒", "start_screen_monitor"),
                "independent schedule/monitor tools are unrelated");
        System.out.println("RetiredVirtualDisplayPolicyTest OK");
    }
}
