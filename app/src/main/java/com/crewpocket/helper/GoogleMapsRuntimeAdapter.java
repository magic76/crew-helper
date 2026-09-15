package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONObject;

/**
 * 0116 first formal App Runtime Adapter.
 *
 * Existing Maps result-row detection remains deterministic in
 * SearchResultSelectionRuntime; this adapter is now the single Maps-facing
 * entry point and the home for Maps-local model guidance.
 */
final class GoogleMapsRuntimeAdapter implements AppRuntimeAdapter {
    static final String PACKAGE_NAME = "com.google.android.apps.maps";
    static final GoogleMapsRuntimeAdapter INSTANCE = new GoogleMapsRuntimeAdapter();

    private GoogleMapsRuntimeAdapter() {}

    static JSONObject analyzeSearchCandidates(
            CrewAccessibilityService service, String query) {
        return SearchResultSelectionRuntime.analyze(service, query);
    }

    @Override public String id() { return "google_maps"; }

    @Override public boolean supports(String packageName) {
        return PACKAGE_NAME.equals(packageName == null ? "" : packageName.trim());
    }

    @Override public String displayName(Context context, String packageName) {
        return "Google Maps";
    }

    @Override public String builtInGuidance() {
        return "SEARCH owns query entry/submission; every SEARCH must include a non-empty text query in the same phone_action call. "
                + "Do not manually TAP a search box then TYPE. "
                + "If Runtime returns SEARCH_NEEDS_QUERY, immediately retry SEARCH with text instead of ending the task. "
                + "Autocomplete suggestions are not final place results; wait for Runtime result rows. "
                + "When several plausible places remain, ask the user instead of guessing. "
                + "Opening Directions only enters route planning; navigation is complete only after the actual guidance/navigation state is visible.";
    }

    @Override public String displayGuidance(Context context) {
        return I18n.get(context,
                "搜尋由 Runtime 一次完成，而且 SEARCH 必須在同一次呼叫帶搜尋文字；若收到 SEARCH_NEEDS_QUERY，要補上文字立刻重試，不要結束任務；自動完成建議不等於真正地點結果；多個可信地點要讓使用者選；只有真正進入導航指引狀態才算開始導航。",
                "Runtime owns search submission; every SEARCH must include a non-empty text query in the same call. Retry immediately after SEARCH_NEEDS_QUERY; autocomplete suggestions are not final place results; ask the user when several places are plausible; navigation is complete only after actual guidance starts.");
    }
}
