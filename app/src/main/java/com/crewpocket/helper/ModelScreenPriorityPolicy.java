package com.crewpocket.helper;

/** Pure priority boost for model-facing actionable controls. */
final class ModelScreenPriorityPolicy {
    private ModelScreenPriorityPolicy() {}

    static int actionControlPriority(
            String label,
            String semanticHint) {
        String text = ((label == null ? "" : label)
                + " "
                + (semanticHint == null ? "" : semanticHint))
                .toLowerCase(java.util.Locale.ROOT);

        String mapsTarget =
                GoogleMapsSemanticContract.canonicalTarget(label);
        if (GoogleMapsSemanticContract.DIRECTIONS.equals(mapsTarget)
                || GoogleMapsSemanticContract.START_NAVIGATION.equals(
                        mapsTarget)) {
            return 130;
        }
        if (!mapsTarget.isEmpty()) return 80;

        String[] strong = new String[] {
                "路線", "路线", "開始", "开始", "導航", "导航",
                "directions", "start navigation", "navigate",
                "send", "送出", "傳送", "发送",
                "next", "下一步", "繼續", "继续",
                "confirm", "確認", "确认"
        };
        for (String token : strong) {
            if (text.contains(token)) return 95;
        }
        return 0;
    }
}
