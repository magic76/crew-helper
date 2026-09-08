package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 0032 Shortcut Runtime v1.
 *
 * Backward compatible with the old crew_memory_rules/rules_v1 storage.
 */
final class MemoryRuleStore {
    private static final String PREFS = "crew_memory_rules";
    private static final String KEY_RULES = "rules_v1";
    private static final int MAX_RULES = 100;
    private static final int MAX_ALIASES = 8;

    static final class Rule {
        String id = "";
        String trigger = "";
        String action = "";
        ArrayList<String> aliases = new ArrayList<String>();
        boolean enabled = true;
        long createdAt;
        long triggerCount;
        long lastUsedAt;
        String lastMatchMode = "";

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try {
                JSONArray aliasArray = new JSONArray();
                for (String alias : aliases) aliasArray.put(alias);
                out.put("id", id)
                        .put("trigger", trigger)
                        .put("action", action)
                        .put("aliases", aliasArray)
                        .put("enabled", enabled)
                        .put("createdAt", createdAt)
                        .put("triggerCount", triggerCount)
                        .put("lastUsedAt", lastUsedAt)
                        .put("lastMatchMode", lastMatchMode);
            } catch (Exception ignored) {}
            return out;
        }

        static Rule fromJson(JSONObject source) {
            Rule out = new Rule();
            if (source == null) return out;
            out.id = source.optString("id", "");
            out.trigger = source.optString("trigger", "");
            out.action = source.optString("action", "");
            out.enabled = source.optBoolean("enabled", true);
            out.createdAt = source.optLong("createdAt", 0L);
            out.triggerCount = source.optLong("triggerCount", 0L);
            out.lastUsedAt = source.optLong("lastUsedAt", 0L);
            out.lastMatchMode = source.optString("lastMatchMode", "");
            JSONArray aliasArray = source.optJSONArray("aliases");
            if (aliasArray != null) {
                for (int i = 0; i < aliasArray.length() && out.aliases.size() < MAX_ALIASES; i++) {
                    String alias = clip(aliasArray.optString(i, ""), 100);
                    if (!alias.isEmpty() && !samePhrase(alias, out.trigger)
                            && !containsPhrase(out.aliases, alias)) {
                        out.aliases.add(alias);
                    }
                }
            }
            return out;
        }
    }

    private final SharedPreferences prefs;

    MemoryRuleStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Rule save(String trigger, String action) {
        return save(trigger, action, (List<String>) null);
    }

    synchronized Rule save(String trigger, String action, JSONArray aliases) {
        ArrayList<String> list = new ArrayList<String>();
        if (aliases != null) {
            for (int i = 0; i < aliases.length() && list.size() < MAX_ALIASES; i++) {
                list.add(aliases.optString(i, ""));
            }
        }
        return save(trigger, action, list);
    }

    synchronized Rule save(String trigger, String action, List<String> aliases) {
        String cleanTrigger = clip(trigger, 100);
        String cleanAction = clip(action, 500);
        if (cleanTrigger.length() < 2 || cleanAction.isEmpty()
                || containsSensitiveText(cleanAction)
                || containsProhibitedShortcutAction(cleanAction)) {
            return null;
        }

        List<Rule> rules = load();
        String key = normalize(cleanTrigger);
        Rule target = null;
        for (Rule rule : rules) {
            if (key.equals(normalize(rule.trigger))) {
                target = rule;
                break;
            }
        }

        if (target == null) {
            target = new Rule();
            target.id = UUID.randomUUID().toString();
            target.createdAt = System.currentTimeMillis();
            rules.add(0, target);
        }

        target.trigger = cleanTrigger;
        target.action = cleanAction;
        target.aliases = sanitizeAliases(cleanTrigger, aliases);
        target.enabled = true;

        while (rules.size() > MAX_RULES) rules.remove(rules.size() - 1);
        saveAll(rules);
        return copy(target);
    }

    synchronized Rule findExact(String spokenText) {
        String input = normalize(spokenText);
        Rule best = null;
        for (Rule rule : load()) {
            if (!rule.enabled) continue;
            if (input.equals(normalize(rule.trigger))) {
                if (best == null || normalize(rule.trigger).length() > normalize(best.trigger).length()) {
                    best = rule;
                }
                continue;
            }
            for (String alias : rule.aliases) {
                if (input.equals(normalize(alias))) {
                    best = rule;
                    break;
                }
            }
        }
        return best == null ? null : copy(best);
    }

    synchronized void recordMatch(String id, String mode) {
        if (id == null || id.isEmpty()) return;
        List<Rule> rules = load();
        boolean changed = false;
        for (Rule rule : rules) {
            if (!id.equals(rule.id)) continue;
            rule.triggerCount++;
            rule.lastUsedAt = System.currentTimeMillis();
            rule.lastMatchMode = clip(mode, 32);
            changed = true;
            break;
        }
        if (changed) saveAll(rules);
    }

    synchronized List<Rule> list() {
        List<Rule> source = load();
        ArrayList<Rule> out = new ArrayList<Rule>();
        for (Rule rule : source) out.add(copy(rule));
        return out;
    }

    synchronized int count() { return load().size(); }

    synchronized int enabledCount() {
        int count = 0;
        for (Rule rule : load()) if (rule.enabled) count++;
        return count;
    }

    synchronized void setEnabled(String id, boolean enabled) {
        List<Rule> rules = load();
        for (Rule rule : rules) if (id.equals(rule.id)) rule.enabled = enabled;
        saveAll(rules);
    }

    synchronized void delete(String id) {
        List<Rule> rules = load();
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (id.equals(rules.get(i).id)) rules.remove(i);
        }
        saveAll(rules);
    }

    synchronized void clearAll() {
        prefs.edit().putString(KEY_RULES, "[]").apply();
    }

    static Rule parseTeaching(String spokenText) {
        String raw = spokenText == null ? "" : spokenText.trim();
        if (raw.isEmpty() || !hasTeachingIntent(raw)) return null;

        int triggerStart = -1;
        String marker = "";
        String[] markers = new String[]{"以後我說", "當我說", "如果我說", "我說"};
        for (String candidate : markers) {
            int at = raw.indexOf(candidate);
            if (at >= 0 && (triggerStart < 0 || at < triggerStart)) {
                triggerStart = at;
                marker = candidate;
            }
        }
        if (triggerStart < 0) return null;

        String remaining = raw.substring(triggerStart + marker.length()).trim();
        int split = firstIndex(
                remaining,
                "的時候，就", "的時候就", "時，就", "時就",
                "，就", ",就", " 就", "就是", "代表", "等於", "等于");
        if (split < 0) return null;

        String trigger = trimQuotes(remaining.substring(0, split));
        String tail = remaining.substring(split).trim();
        String action = tail.replaceFirst(
                "^(的時候[，,]?|時[，,]?|[，,\\s]*)?(就是|代表|等於|等于|就幫我做|就幫我|就做|就)?",
                "").trim();
        action = trimEnd(action);

        if (trigger.length() < 2 || action.isEmpty()) return null;
        Rule rule = new Rule();
        rule.trigger = trigger;
        rule.action = action;
        return rule;
    }

    static boolean containsProhibitedShortcutAction(String text) {
        String value = normalize(text);
        return containsAny(value,
                "傳訊息", "传讯息", "發訊息", "发讯息", "發送訊息", "发送讯息",
                "sendmessage", "sendtext", "replyto",
                "刪除", "删除", "付款", "支付", "購買", "购买", "下單", "下单",
                "轉帳", "转账", "匯款", "汇款",
                "修改帳戶", "修改账户", "更改密碼", "更改密码",
                "結束通話", "结束通话", "hangup");
    }

    private List<Rule> load() {
        ArrayList<Rule> out = new ArrayList<Rule>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_RULES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Rule rule = Rule.fromJson(array.optJSONObject(i));
                if (!rule.id.isEmpty() && !rule.trigger.isEmpty() && !rule.action.isEmpty()) {
                    out.add(rule);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveAll(List<Rule> rules) {
        JSONArray array = new JSONArray();
        for (Rule rule : rules) array.put(rule.toJson());
        prefs.edit().putString(KEY_RULES, array.toString()).apply();
    }

    private static ArrayList<String> sanitizeAliases(String trigger, List<String> aliases) {
        ArrayList<String> out = new ArrayList<String>();
        if (aliases == null) return out;
        for (String raw : aliases) {
            if (out.size() >= MAX_ALIASES) break;
            String alias = clip(raw, 100);
            if (alias.length() < 2 || samePhrase(alias, trigger)
                    || containsPhrase(out, alias)) continue;
            out.add(alias);
        }
        return out;
    }

    private static Rule copy(Rule in) {
        Rule out = new Rule();
        out.id = in.id;
        out.trigger = in.trigger;
        out.action = in.action;
        out.aliases = new ArrayList<String>(in.aliases);
        out.enabled = in.enabled;
        out.createdAt = in.createdAt;
        out.triggerCount = in.triggerCount;
        out.lastUsedAt = in.lastUsedAt;
        out.lastMatchMode = in.lastMatchMode;
        return out;
    }

    private static boolean hasTeachingIntent(String raw) {
        String value = normalize(raw);
        return containsAny(value,
                "記住", "记住", "記憶", "记忆", "規則", "规则",
                "快捷指令", "快捷命令", "口令", "shortcut");
    }

    private static boolean containsPhrase(List<String> values, String candidate) {
        for (String value : values) if (samePhrase(value, candidate)) return true;
        return false;
    }

    private static boolean samePhrase(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static int firstIndex(String text, String... tokens) {
        int result = -1;
        for (String token : tokens) {
            int at = text.indexOf(token);
            if (at >= 0 && (result < 0 || at < result)) result = at;
        }
        return result;
    }

    private static String trimQuotes(String text) {
        return clip(text, 100).replaceAll("^[「『\\\"'\\s]+|[」』\\\"'\\s]+$", "");
    }

    private static String trimEnd(String text) {
        return clip(text, 500).replaceAll("[。！!\\s]+$", "");
    }

    private static String clip(String text, int max) {
        String out = text == null ? "" : text.trim();
        return out.length() <= max ? out : out.substring(0, max);
    }

    private static String normalize(String text) {
        return TextMatch.caseFold(text == null ? "" : text)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
    }

    private static boolean containsSensitiveText(String text) {
        String value = normalize(text);
        return containsAny(value,
                "密碼", "密码", "password", "otp",
                "驗證碼", "验证码", "簡訊碼", "短信码", "cvv", "卡號", "卡号");
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            String normalizedNeedle = normalize(needle);
            if (!normalizedNeedle.isEmpty() && value.contains(normalizedNeedle)) return true;
        }
        return false;
    }
}
