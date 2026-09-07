package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent voice trigger → action-intent rules. Execution still keeps all Live safety checks. */
final class MemoryRuleStore {
    private static final String PREFS = "crew_memory_rules";
    private static final String KEY_RULES = "rules_v1";
    private static final int MAX_RULES = 100;

    static final class Rule {
        String id = "";
        String trigger = "";
        String action = "";
        boolean enabled = true;
        long createdAt;

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try { out.put("id", id).put("trigger", trigger).put("action", action).put("enabled", enabled).put("createdAt", createdAt); }
            catch (Exception ignored) {}
            return out;
        }
        static Rule fromJson(JSONObject source) {
            Rule out = new Rule(); if (source == null) return out;
            out.id = source.optString("id", ""); out.trigger = source.optString("trigger", "");
            out.action = source.optString("action", ""); out.enabled = source.optBoolean("enabled", true);
            out.createdAt = source.optLong("createdAt", 0L); return out;
        }
    }

    private final SharedPreferences prefs;
    MemoryRuleStore(Context context) { prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    synchronized Rule save(String trigger, String action) {
        String cleanTrigger = clip(trigger, 100), cleanAction = clip(action, 500);
        if (cleanTrigger.length() < 2 || cleanAction.isEmpty() || containsSensitiveText(cleanAction)) return null;
        List<Rule> rules = load(); String key = normalize(cleanTrigger); Rule target = null;
        for (Rule rule : rules) if (key.equals(normalize(rule.trigger))) { target = rule; break; }
        if (target == null) { target = new Rule(); target.id = UUID.randomUUID().toString(); target.createdAt = System.currentTimeMillis(); rules.add(0, target); }
        target.trigger = cleanTrigger; target.action = cleanAction; target.enabled = true;
        while (rules.size() > MAX_RULES) rules.remove(rules.size() - 1);
        saveAll(rules); return target;
    }

    synchronized Rule findExact(String spokenText) {
        String input = normalize(spokenText); Rule best = null;
        for (Rule rule : load()) {
            String trigger = normalize(rule.trigger);
            if (!rule.enabled || trigger.length() < 2) continue;
            if (input.equals(trigger) && (best == null || trigger.length() > normalize(best.trigger).length())) best = rule;
        }
        return best;
    }

    synchronized List<Rule> list() { return load(); }
    synchronized int count() { return load().size(); }
    synchronized int enabledCount() { int count = 0; for (Rule rule : load()) if (rule.enabled) count++; return count; }
    synchronized void setEnabled(String id, boolean enabled) { List<Rule> rules = load(); for (Rule rule : rules) if (id.equals(rule.id)) rule.enabled = enabled; saveAll(rules); }
    synchronized void delete(String id) { List<Rule> rules = load(); for (int i = rules.size() - 1; i >= 0; i--) if (id.equals(rules.get(i).id)) rules.remove(i); saveAll(rules); }
    synchronized void clearAll() { prefs.edit().putString(KEY_RULES, "[]").apply(); }

    static Rule parseTeaching(String spokenText) {
        String raw = spokenText == null ? "" : spokenText.trim();
        if (raw.isEmpty() || !(raw.contains("記住") || raw.contains("記憶") || raw.contains("規則"))) return null;
        int triggerStart = raw.indexOf("以後我說");
        String marker = "以後我說";
        if (triggerStart < 0) { triggerStart = raw.indexOf("當我說"); marker = "當我說"; }
        if (triggerStart < 0) return null;
        String remaining = raw.substring(triggerStart + marker.length()).trim();
        int split = firstIndex(remaining, "，就", ",就", "時，就", "時,就", "都做", "就做", "就幫我", "，幫我");
        if (split < 0) return null;
        String trigger = trimQuotes(remaining.substring(0, split));
        String action = remaining.substring(split).replaceFirst("^[，,時\\s]*(都做|就做|就幫我做|就幫我|幫我做|幫我|就)", "").trim();
        action = trimEnd(action);
        if (trigger.length() < 2 || action.isEmpty()) return null;
        Rule rule = new Rule(); rule.trigger = trigger; rule.action = action; return rule;
    }

    private List<Rule> load() {
        ArrayList<Rule> out = new ArrayList<Rule>();
        try { JSONArray array = new JSONArray(prefs.getString(KEY_RULES, "[]")); for (int i = 0; i < array.length(); i++) { Rule rule = Rule.fromJson(array.optJSONObject(i)); if (!rule.id.isEmpty() && !rule.trigger.isEmpty() && !rule.action.isEmpty()) out.add(rule); } }
        catch (Exception ignored) {}
        return out;
    }
    private void saveAll(List<Rule> rules) { JSONArray array = new JSONArray(); for (Rule rule : rules) array.put(rule.toJson()); prefs.edit().putString(KEY_RULES, array.toString()).apply(); }
    private static int firstIndex(String text, String... tokens) { int result = -1; for (String token : tokens) { int at = text.indexOf(token); if (at >= 0 && (result < 0 || at < result)) result = at; } return result; }
    private static String trimQuotes(String text) { return clip(text, 100).replaceAll("^[「『\"'\\s]+|[」』\"'\\s]+$", ""); }
    private static String trimEnd(String text) { return clip(text, 500).replaceAll("[。！!\\s]+$", ""); }
    private static String clip(String text, int max) { String out = text == null ? "" : text.trim(); return out.length() <= max ? out : out.substring(0, max); }
    private static String normalize(String text) { return TextMatch.caseFold(text).replaceAll("[\\s，,。！？!「」『』\"']", ""); }
    private static boolean containsSensitiveText(String text) { String value = normalize(text); return value.contains("密碼") || value.contains("password") || value.contains("otp") || value.contains("驗證碼") || value.contains("簡訊碼"); }
}
