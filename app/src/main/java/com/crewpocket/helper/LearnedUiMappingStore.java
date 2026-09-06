package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistent per-app UI mapping learned from the user.
 *
 * Key space:
 *   packageName + screenSignature + role
 *
 * Never stores text typed by the user. Selectors contain only structural metadata.
 */
final class LearnedUiMappingStore {
    private static final String PREFS = "crew_learned_ui_mappings";
    private static final String KEY_RULES = "rules_v1";
    private static final int MAX_RULES = 300;

    static final class Rule {
        String packageName = "";
        String screenSignature = "";
        String role = "";
        String viewId = "";
        String className = "";
        String contentDescription = "";
        String parentClassName = "";
        String relativePosition = "";
        long learnedAt = 0L;
        long lastVerifiedAt = 0L;
        int successCount = 0;
        int failureCount = 0;

        double confidence() {
            int total = successCount + failureCount;
            if (total <= 0) return 0.80d;
            double observed = (successCount + 1d) / (total + 2d);
            return Math.max(0.20d, Math.min(0.99d, observed));
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("packageName", packageName);
                o.put("screenSignature", screenSignature);
                o.put("role", role);
                o.put("viewId", viewId);
                o.put("className", className);
                o.put("contentDescription", contentDescription);
                o.put("parentClassName", parentClassName);
                o.put("relativePosition", relativePosition);
                o.put("learnedAt", learnedAt);
                o.put("lastVerifiedAt", lastVerifiedAt);
                o.put("successCount", successCount);
                o.put("failureCount", failureCount);
            } catch (Exception ignored) {}
            return o;
        }

        static Rule fromJson(JSONObject o) {
            Rule r = new Rule();
            if (o == null) return r;
            r.packageName = o.optString("packageName", "");
            r.screenSignature = o.optString("screenSignature", "");
            r.role = o.optString("role", "");
            r.viewId = o.optString("viewId", "");
            r.className = o.optString("className", "");
            r.contentDescription = o.optString("contentDescription", "");
            r.parentClassName = o.optString("parentClassName", "");
            r.relativePosition = o.optString("relativePosition", "");
            r.learnedAt = o.optLong("learnedAt", 0L);
            r.lastVerifiedAt = o.optLong("lastVerifiedAt", 0L);
            r.successCount = o.optInt("successCount", 0);
            r.failureCount = o.optInt("failureCount", 0);
            return r;
        }
    }

    private final SharedPreferences prefs;

    LearnedUiMappingStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Rule learn(String packageName,
                            String screenSignature,
                            String role,
                            AccessibilityNodeInfo node,
                            AccessibilityNodeInfo referenceNode) {
        if (node == null) return null;

        Rule rule = new Rule();
        rule.packageName = safe(packageName);
        rule.screenSignature = safe(screenSignature);
        rule.role = normalizeRole(role);
        rule.viewId = safe(node.getViewIdResourceName());
        rule.className = safe(node.getClassName());
        rule.contentDescription = sanitizeDescription(node.getContentDescription());
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            try { rule.parentClassName = safe(parent.getClassName()); }
            finally { parent.recycle(); }
        }
        rule.relativePosition = relativePosition(node, referenceNode);
        rule.learnedAt = System.currentTimeMillis();
        rule.lastVerifiedAt = rule.learnedAt;

        List<Rule> rules = loadRules();
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule old = rules.get(i);
            if (sameIdentity(old, rule)) rules.remove(i);
        }
        rules.add(0, rule);
        while (rules.size() > MAX_RULES) rules.remove(rules.size() - 1);
        saveRules(rules);
        return rule;
    }

    synchronized List<Rule> findRules(String packageName, String screenSignature, String role) {
        String pkg = safe(packageName);
        String sig = safe(screenSignature);
        String normalizedRole = normalizeRole(role);
        List<Rule> result = new ArrayList<>();
        for (Rule r : loadRules()) {
            if (!pkg.equals(r.packageName)) continue;
            if (!normalizedRole.equals(r.role)) continue;
            // Exact screen signature first, but allow package-level fallback when
            // a rule was intentionally learned with an empty signature.
            if (!r.screenSignature.isEmpty() && !sig.equals(r.screenSignature)) continue;
            if (r.failureCount >= 3 && r.successCount == 0) continue;
            result.add(r);
        }
        return result;
    }

    synchronized void recordResult(Rule target, boolean success) {
        if (target == null) return;
        List<Rule> rules = loadRules();
        for (Rule r : rules) {
            if (!sameIdentity(r, target)) continue;
            if (success) {
                r.successCount++;
                r.lastVerifiedAt = System.currentTimeMillis();
            } else {
                r.failureCount++;
            }
            break;
        }
        saveRules(rules);
    }

    synchronized JSONArray dumpForDebug(String packageName) {
        JSONArray arr = new JSONArray();
        for (Rule r : loadRules()) {
            if (packageName == null || packageName.isEmpty() || packageName.equals(r.packageName)) {
                arr.put(r.toJson());
            }
        }
        return arr;
    }

    private List<Rule> loadRules() {
        ArrayList<Rule> result = new ArrayList<>();
        String raw = prefs.getString(KEY_RULES, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Rule r = Rule.fromJson(a.optJSONObject(i));
                if (!r.packageName.isEmpty() && !r.role.isEmpty()) result.add(r);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void saveRules(List<Rule> rules) {
        JSONArray a = new JSONArray();
        for (Rule r : rules) a.put(r.toJson());
        prefs.edit().putString(KEY_RULES, a.toString()).apply();
    }

    private static boolean sameIdentity(Rule a, Rule b) {
        return a.packageName.equals(b.packageName)
                && a.screenSignature.equals(b.screenSignature)
                && a.role.equals(b.role)
                && a.viewId.equals(b.viewId)
                && a.className.equals(b.className)
                && a.contentDescription.equals(b.contentDescription);
    }

    private static String relativePosition(AccessibilityNodeInfo node, AccessibilityNodeInfo ref) {
        if (node == null || ref == null) return "";
        Rect a = new Rect();
        Rect b = new Rect();
        node.getBoundsInScreen(a);
        ref.getBoundsInScreen(b);
        if (a.centerX() > b.right) return "RIGHT_OF_REFERENCE";
        if (a.centerX() < b.left) return "LEFT_OF_REFERENCE";
        if (a.centerY() < b.top) return "ABOVE_REFERENCE";
        if (a.centerY() > b.bottom) return "BELOW_REFERENCE";
        return "OVERLAPS_REFERENCE";
    }

    private static String normalizeRole(String role) {
        return safe(role).trim().toUpperCase(Locale.ROOT);
    }

    private static String sanitizeDescription(CharSequence value) {
        String s = safe(value).trim();
        // Do not persist long/free-form content descriptions that may contain
        // user-generated text.
        if (s.length() > 80) return "";
        return s;
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
