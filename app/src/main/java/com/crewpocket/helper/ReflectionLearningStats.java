package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Privacy-safe counters for developer observability of Crew Experience learning. */
final class ReflectionLearningStats {
    private static final String PREFS = "crew_reflection_learning";
    private static final String KEY_ITEMS = "rules_v2";

    private ReflectionLearningStats() {}

    static String buildReport(Context context) {
        int candidate = 0;
        int verified = 0;
        int suspect = 0;
        int total = 0;
        long latestAt = 0L;

        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray items = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
                total = items.length();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String state = item.optString("state", "CANDIDATE");
                    if ("VERIFIED".equals(state)) verified++;
                    else if ("SUSPECT".equals(state)) suspect++;
                    else candidate++;
                    latestAt = Math.max(latestAt, item.optLong("updatedAt", 0L));
                }
            } catch (Exception ignored) {}
        }

        StringBuilder out = new StringBuilder();
        out.append("Experience learning\n");
        out.append("Identity: Runtime evidence rule key\n");
        out.append("Trigger: proven recovery or repeated routine evidence\n");
        out.append("Model role: compress qualified evidence into a lesson\n");
        out.append("Model: ").append(GeminiTaskReflector.MODEL).append("\n");
        out.append("Candidates: ").append(candidate).append("\n");
        out.append("Verified: ").append(verified).append("\n");
        out.append("Suspect: ").append(suspect).append("\n");
        out.append("Total retained: ").append(total).append("\n");
        out.append("Last learning update: ")
                .append(latestAt <= 0L ? "none" : String.valueOf(latestAt));
        return out.toString();
    }
}
