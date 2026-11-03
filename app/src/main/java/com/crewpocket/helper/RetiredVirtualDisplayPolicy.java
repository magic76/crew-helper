package com.crewpocket.helper;

import java.util.Locale;

/**
 * Small fail-closed guard retained after removal of the privileged
 * virtual-display experiment. Explicit virtual-screen user requests
 * never become physical-screen mutations without fresh user intent.
 */
final class RetiredVirtualDisplayPolicy {
    private RetiredVirtualDisplayPolicy() {}

    static boolean explicitlyVirtual(String finalizedText) {
        if (finalizedText == null) return false;
        String value = finalizedText.toLowerCase(Locale.ROOT);
        for (String phrase : new String[]{
                "在背景", "背景螢幕", "背景屏幕", "虛擬螢幕", "虚拟屏",
                "背景操作", "背景執行", "背景開啟", "背景打開",
                "背景點", "背景輸入", "背景搜尋",
                "virtual screen", "virtual display", "background display",
                "background screen", "in the background"
        }) {
            if (value.contains(phrase)) return true;
        }
        return false;
    }

    static boolean mustBlock(String finalizedText, String tool) {
        if ("background_phone".equals(tool)) return true;
        if (!explicitlyVirtual(finalizedText)) return false;
        if (tool == null) return false;
        switch (tool) {
            case "phone_action":
            case "launch_app":
            case "tap_screen":
            case "tap_element":
            case "scroll_screen":
            case "type_text":
            case "search_current_app":
            case "commit_search":
            case "send_text":
            case "run_app_capability":
            case "wait_then_action":
            case "inspect_ui":
            case "take_screenshot":
            case "press_back":
            case "press_home":
                return true;
            default:
                return false; // read-only diagnostics remain available
        }
    }
}
