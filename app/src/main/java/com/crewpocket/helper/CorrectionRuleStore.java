package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 0026: persistent user-corrected operation rules.
 *
 * Privacy boundary:
 * - stores app/screen fingerprints and structural action selectors only;
 * - never stores type_text/send_text payloads, passwords, OTPs, or message bodies.
 */
final class CorrectionRuleStore {
    private static final String PREFS = "crew_correction_rules";
    private static final String KEY_RULES = "rules_v1";
    private static final int MAX_RULES = 200;

    static final class Rule {
        String id = "";
        String title = "";
        String note = "";
        boolean enabled = true;
        String packageName = "";
        String screenFingerprint = "";
        String wrongTool = "";
        String wrongArgs = "{}";
        String correctTool = "";
        String correctArgs = "{}";
        String source = "USER_CORRECTED";
        long learnedAt = 0L;
        long lastUsedAt = 0L;
        int successCount = 0;
        int failureCount = 0;

        double confidence() {
            int total = successCount + failureCount;
            if (total <= 0) return 0.95d;
            double observed = (successCount + 3d) / (total + 4d);
            return Math.max(0.20d, Math.min(0.99d, observed));
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title);
                o.put("note", note);
                o.put("enabled", enabled);
                o.put("packageName", packageName);
                o.put("screenFingerprint", screenFingerprint);
                o.put("wrongTool", wrongTool);
                o.put("wrongArgs", wrongArgs);
                o.put("correctTool", correctTool);
                o.put("correctArgs", correctArgs);
                o.put("source", source);
                o.put("learnedAt", learnedAt);
                o.put("lastUsedAt", lastUsedAt);
                o.put("successCount", successCount);
                o.put("failureCount", failureCount);
            } catch (Exception ignored) {}
            return o;
        }

        static Rule fromJson(JSONObject o) {
            Rule r = new Rule();
            if (o == null) return r;
            r.id = o.optString("id", "");
            r.title = o.optString("title", "");
            r.note = o.optString("note", "");
            r.enabled = o.optBoolean("enabled", true);
            r.packageName = o.optString("packageName", "");
            r.screenFingerprint = o.optString("screenFingerprint", "");
            r.wrongTool = o.optString("wrongTool", "");
            r.wrongArgs = o.optString("wrongArgs", "{}");
            r.correctTool = o.optString("correctTool", "");
            r.correctArgs = o.optString("correctArgs", "{}");
            r.source = o.optString("source", "USER_CORRECTED");
            r.learnedAt = o.optLong("learnedAt", 0L);
            r.lastUsedAt = o.optLong("lastUsedAt", 0L);
            r.successCount = o.optInt("successCount", 0);
            r.failureCount = o.optInt("failureCount", 0);
            return r;
        }
    }

    private final SharedPreferences prefs;

    CorrectionRuleStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Rule> listRules() {
        return loadRules();
    }

    synchronized int count() {
        return loadRules().size();
    }

    synchronized int enabledCount() {
        int count = 0;
        for (Rule r : loadRules()) {
            if (r.enabled) count++;
        }
        return count;
    }

    synchronized Rule findBest(String packageName,
                               String screenFingerprint,
                               String wrongTool,
                               JSONObject wrongArgs) {
        String pkg = safe(packageName);
        String screen = safe(screenFingerprint);
        String tool = normalizeTool(wrongTool);
        String argSignature = signature(tool, wrongArgs);
        if (pkg.isEmpty() || screen.isEmpty() || tool.isEmpty()) return null;

        Rule best = null;
        for (Rule r : loadRules()) {
            if (!r.enabled) continue;
            if (!pkg.equals(r.packageName)) continue;
            if (!screen.equals(r.screenFingerprint)) continue;
            if (!tool.equals(r.wrongTool)) continue;
            if (!argSignature.equals(r.wrongArgs)) continue;
            if (r.failureCount >= 2 && r.failureCount > r.successCount) continue;
            if (best == null || r.confidence() > best.confidence()) best = r;
        }
        return best;
    }

    synchronized Rule upsertCorrection(String packageName,
                                       String screenFingerprint,
                                       String wrongTool,
                                       JSONObject wrongArgs,
                                       String correctTool,
                                       JSONObject correctArgs) {
        String pkg = safe(packageName);
        String screen = safe(screenFingerprint);
        String wTool = normalizeTool(wrongTool);
        String cTool = normalizeTool(correctTool);
        JSONObject wArgs = sanitizeArgs(wTool, wrongArgs);
        JSONObject cArgs = sanitizeArgs(cTool, correctArgs);

        if (pkg.isEmpty() || screen.isEmpty()) return null;
        if (!isLearnableTool(wTool) || !isLearnableTool(cTool)) return null;

        String wrongSignature = wArgs.toString();
        List<Rule> rules = loadRules();
        Rule target = null;
        for (Rule r : rules) {
            if (pkg.equals(r.packageName)
                    && screen.equals(r.screenFingerprint)
                    && wTool.equals(r.wrongTool)
                    && wrongSignature.equals(r.wrongArgs)) {
                target = r;
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (target == null) {
            target = new Rule();
            target.id = UUID.randomUUID().toString();
            target.packageName = pkg;
            target.screenFingerprint = screen;
            target.wrongTool = wTool;
            target.wrongArgs = wrongSignature;
            target.learnedAt = now;
            target.title = "修正：" + describeAction(wTool, wArgs)
                    + " → " + describeAction(cTool, cArgs);
            target.note = "使用者明確糾正後學習";
            rules.add(0, target);
        }

        target.correctTool = cTool;
        target.correctArgs = cArgs.toString();
        target.source = "USER_CORRECTED";
        target.enabled = true;
        target.failureCount = 0;
        target.lastUsedAt = now;

        while (rules.size() > MAX_RULES) {
            rules.remove(rules.size() - 1);
        }
        saveRules(rules);
        return target;
    }

    synchronized void recordResult(String ruleId, boolean success) {
        if (ruleId == null || ruleId.isEmpty()) return;
        List<Rule> rules = loadRules();
        for (Rule r : rules) {
            if (!ruleId.equals(r.id)) continue;
            if (success) {
                r.successCount++;
                r.lastUsedAt = System.currentTimeMillis();
            } else {
                r.failureCount++;
                if (r.failureCount >= 2 && r.failureCount > r.successCount) {
                    r.enabled = false;
                }
            }
            break;
        }
        saveRules(rules);
    }

    synchronized boolean updateRule(String id,
                                    String title,
                                    String note,
                                    String correctTool,
                                    String correctArgsRaw) {
        if (id == null || id.isEmpty()) return false;
        String tool = normalizeTool(correctTool);
        if (!isLearnableTool(tool)) return false;

        JSONObject parsed;
        try {
            parsed = new JSONObject(correctArgsRaw == null ? "{}" : correctArgsRaw);
        } catch (Exception error) {
            return false;
        }
        JSONObject sanitized = sanitizeArgs(tool, parsed);

        List<Rule> rules = loadRules();
        for (Rule r : rules) {
            if (!id.equals(r.id)) continue;
            r.title = shortText(title, 120);
            r.note = shortText(note, 240);
            r.correctTool = tool;
            r.correctArgs = sanitized.toString();
            saveRules(rules);
            return true;
        }
        return false;
    }

    synchronized void setEnabled(String id, boolean enabled) {
        List<Rule> rules = loadRules();
        for (Rule r : rules) {
            if (id.equals(r.id)) {
                r.enabled = enabled;
                break;
            }
        }
        saveRules(rules);
    }

    synchronized void delete(String id) {
        List<Rule> rules = loadRules();
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (id.equals(rules.get(i).id)) {
                rules.remove(i);
            }
        }
        saveRules(rules);
    }

    synchronized void clearAll() {
        prefs.edit().putString(KEY_RULES, "[]").apply();
    }

    static boolean isLearnableTool(String tool) {
        String t = normalizeTool(tool);
        return "tap_element".equals(t)
                || "tap_screen".equals(t)
                || "swipe_screen".equals(t)
                || "press_key".equals(t)
                || "launch_app".equals(t);
    }

    static JSONObject sanitizeArgs(String tool, JSONObject args) {
        JSONObject out = new JSONObject();
        if (args == null) return out;
        String t = normalizeTool(tool);
        try {
            if ("tap_element".equals(t)) {
                putShort(out, "element_id", args.optString("element_id", ""), 120);
            } else if ("tap_screen".equals(t)) {
                putShort(out, "label", args.optString("label", ""), 100);
                putShort(out, "id", args.optString("id", ""), 140);
                if (args.has("x")) out.put("x", Math.round(args.optDouble("x", -1)));
                if (args.has("y")) out.put("y", Math.round(args.optDouble("y", -1)));
                putShort(out, "coordinate_space",
                        args.optString("coordinate_space", ""), 32);
            } else if ("swipe_screen".equals(t)) {
                putShort(out, "direction", args.optString("direction", ""), 16);
                putShort(out, "distance", args.optString("distance", ""), 16);
            } else if ("press_key".equals(t)) {
                putShort(out, "key", args.optString("key", ""), 32);
            } else if ("launch_app".equals(t)) {
                putShort(out, "app", args.optString("app", ""), 100);
                if (args.has("index")) out.put("index", args.optInt("index", -1));
            }
        } catch (Exception ignored) {}
        return out;
    }

    static String signature(String tool, JSONObject args) {
        return sanitizeArgs(tool, args).toString();
    }

    static String describeAction(String tool, JSONObject args) {
        JSONObject a = sanitizeArgs(tool, args);
        String t = normalizeTool(tool);
        if ("tap_element".equals(t)) {
            return "點 " + a.optString("element_id", "?");
        }
        if ("tap_screen".equals(t)) {
            String label = a.optString("label", "");
            String id = a.optString("id", "");
            if (!label.isEmpty()) return "點「" + label + "」";
            if (!id.isEmpty()) return "點 " + id;
            if (a.has("x") && a.has("y")) {
                return "點座標 " + a.optInt("x") + "," + a.optInt("y");
            }
            return "點擊";
        }
        if ("swipe_screen".equals(t)) {
            return "滑動 " + a.optString("direction", "?")
                    + " / " + a.optString("distance", "normal");
        }
        if ("press_key".equals(t)) {
            return "系統鍵 " + a.optString("key", "?");
        }
        if ("launch_app".equals(t)) {
            return "開啟 " + a.optString("app", "?");
        }
        return t;
    }

    static String shortScreen(String fingerprint) {
        if (fingerprint == null) return "";
        return fingerprint.length() <= 12
                ? fingerprint
                : fingerprint.substring(0, 12);
    }

    private List<Rule> loadRules() {
        ArrayList<Rule> result = new ArrayList<Rule>();
        String raw = prefs.getString(KEY_RULES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Rule r = Rule.fromJson(arr.optJSONObject(i));
                if (!r.id.isEmpty()
                        && !r.packageName.isEmpty()
                        && !r.wrongTool.isEmpty()
                        && !r.correctTool.isEmpty()) {
                    result.add(r);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void saveRules(List<Rule> rules) {
        JSONArray arr = new JSONArray();
        for (Rule r : rules) {
            arr.put(r.toJson());
        }
        prefs.edit().putString(KEY_RULES, arr.toString()).apply();
    }

    private static void putShort(JSONObject out, String key, String value, int max) {
        String v = shortText(value, max);
        if (v.isEmpty()) return;
        try {
            out.put(key, v);
        } catch (Exception ignored) {}
    }

    private static String shortText(String value, int max) {
        String v = safe(value).trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String normalizeTool(String tool) {
        return safe(tool).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
