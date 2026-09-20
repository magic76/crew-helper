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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-app UI mapping learned from the user.
 *
 * Key space:
 *   packageName + screenSignature + role
 *
 * User teaching is authoritative for a reusable action slot: re-teaching the
 * same app/role/composer-state replaces the previous selector. A learned action
 * that fails verified execution is disabled immediately until the user teaches
 * it again.
 *
 * Never stores text typed by the user. Selectors contain only structural metadata.
 */
final class LearnedUiMappingStore {
    private static final String PREFS = "crew_learned_ui_mappings";
    private static final String KEY_RULES = "rules_v1";
    private static final String KEY_ACTION_MEMORY_LEARNS = "action_memory_learns";
    private static final String KEY_ACTION_MEMORY_RELEARNS = "action_memory_relearns";
    private static final String KEY_ACTION_MEMORY_HITS = "action_memory_hits";
    private static final String KEY_ACTION_MEMORY_INVALIDATIONS = "action_memory_invalidations";
    private static final int MAX_RULES = 300;

    static final class Rule {
        String id = "";
        String packageName = "";
        String screenSignature = "";
        String stableScreenKey = "";
        String role = "";
        String viewId = "";
        String className = "";
        String contentDescription = "";
        String parentClassName = "";
        String relativePosition = "";
        String composerState = ""; // EMPTY | HAS_TEXT | UNKNOWN
        String composerClassName = "";
        String composerViewId = "";
        int centerX = -1;   // absolute screen X of the tapped node center
        int centerY = -1;   // absolute screen Y of the tapped node center
        // 0022 two-point anchored learning. Absolute target coordinates remain
        // debug/supporting data; execution uses the live anchor + offset.
        String anchorViewId = "";
        String anchorClassName = "";
        String anchorContentDescription = "";
        int anchorCenterX = -1;
        int anchorCenterY = -1;
        int targetOffsetX = 0;
        int targetOffsetY = 0;
        boolean anchored = false;
        boolean enabled = true;
        long learnedAt = 0L;
        long lastVerifiedAt = 0L;
        int successCount = 0;
        int failureCount = 0;
        transient int screenAffinityBonus = 0;

        double confidence() {
            int total = successCount + failureCount;
            if (total <= 0) return 0.80d;
            double observed = (successCount + 1d) / (total + 2d);
            return Math.max(0.20d, Math.min(0.99d, observed));
        }

        /** Returns true if this rule has a reliable coordinate fallback. */
        boolean hasCoordinate() { return centerX >= 0 && centerY >= 0; }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("packageName", packageName);
                o.put("screenSignature", screenSignature);
                o.put("stableScreenKey", stableScreenKey);
                o.put("role", role);
                o.put("viewId", viewId);
                o.put("className", className);
                o.put("contentDescription", contentDescription);
                o.put("parentClassName", parentClassName);
                o.put("relativePosition", relativePosition);
                o.put("composerState", composerState);
                o.put("composerClassName", composerClassName);
                o.put("composerViewId", composerViewId);
                o.put("centerX", centerX);
                o.put("centerY", centerY);
                o.put("anchorViewId", anchorViewId);
                o.put("anchorClassName", anchorClassName);
                o.put("anchorContentDescription", anchorContentDescription);
                o.put("anchorCenterX", anchorCenterX);
                o.put("anchorCenterY", anchorCenterY);
                o.put("targetOffsetX", targetOffsetX);
                o.put("targetOffsetY", targetOffsetY);
                o.put("anchored", anchored);
                o.put("enabled", enabled);
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
            r.id = o.optString("id", "");
            r.packageName = o.optString("packageName", "");
            r.screenSignature = o.optString("screenSignature", "");
            r.stableScreenKey = o.optString("stableScreenKey", "");
            r.role = o.optString("role", "");
            r.viewId = o.optString("viewId", "");
            r.className = o.optString("className", "");
            r.contentDescription = o.optString("contentDescription", "");
            r.parentClassName = o.optString("parentClassName", "");
            r.relativePosition = o.optString("relativePosition", "");
            r.composerState = o.optString("composerState", "");
            r.composerClassName = o.optString("composerClassName", "");
            r.composerViewId = o.optString("composerViewId", "");
            r.centerX = o.optInt("centerX", -1);
            r.centerY = o.optInt("centerY", -1);
            r.anchorViewId = o.optString("anchorViewId", "");
            r.anchorClassName = o.optString("anchorClassName", "");
            r.anchorContentDescription = o.optString("anchorContentDescription", "");
            r.anchorCenterX = o.optInt("anchorCenterX", -1);
            r.anchorCenterY = o.optInt("anchorCenterY", -1);
            r.targetOffsetX = o.optInt("targetOffsetX", 0);
            r.targetOffsetY = o.optInt("targetOffsetY", 0);
            r.anchored = o.optBoolean("anchored", false);
            // Backward compatible: mappings created before App Action Memory
            // existed remain active until they fail or are re-taught.
            r.enabled = o.has("enabled") ? o.optBoolean("enabled", true) : true;
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
        migrateMissingIds();
    }

    synchronized void clearAll() {
        prefs.edit()
                .putString(KEY_RULES, "[]")
                .putLong(KEY_ACTION_MEMORY_LEARNS, 0L)
                .putLong(KEY_ACTION_MEMORY_RELEARNS, 0L)
                .putLong(KEY_ACTION_MEMORY_HITS, 0L)
                .putLong(KEY_ACTION_MEMORY_INVALIDATIONS, 0L)
                .apply();
    }

    synchronized Rule learn(String packageName,
                            String screenSignature,
                            String stableScreenKey,
                            String role,
                            AccessibilityNodeInfo node,
                            AccessibilityNodeInfo referenceNode) {
        if (node == null) return null;

        Rule rule = new Rule();
        rule.id = UUID.randomUUID().toString();
        rule.packageName = safe(packageName);
        rule.screenSignature = safe(screenSignature);
        rule.stableScreenKey = safe(stableScreenKey);
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
        rule.composerState = composerState(referenceNode);
        if (referenceNode != null) {
            rule.composerClassName = safe(referenceNode.getClassName());
            rule.composerViewId = safe(referenceNode.getViewIdResourceName());
        }
        // Always persist the screen center of the tapped node.
        // This is supporting evidence for apps where the send button has
        // no stable viewId or contentDescription.
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            rule.centerX = bounds.centerX();
            rule.centerY = bounds.centerY();
        }
        rule.enabled = true;
        rule.learnedAt = System.currentTimeMillis();
        rule.lastVerifiedAt = rule.learnedAt;

        List<Rule> rules = loadRules();
        boolean replaced = false;
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule old = rules.get(i);
            if (sameActionSlot(old, rule)) {
                rules.remove(i);
                replaced = true;
            }
        }
        rules.add(0, rule);
        while (rules.size() > MAX_RULES) rules.remove(rules.size() - 1);
        saveRules(rules);
        incrementCounter(replaced ? KEY_ACTION_MEMORY_RELEARNS : KEY_ACTION_MEMORY_LEARNS);
        return rule;
    }

    synchronized Rule learnAnchored(String packageName,
                                    String screenSignature,
                                    String stableScreenKey,
                                    String role,
                                    AccessibilityNodeInfo anchor,
                                    AccessibilityNodeInfo target) {
        Rule rule = learn(
                packageName,
                screenSignature,
                stableScreenKey,
                role,
                target,
                anchor);
        if (rule == null) return null;
        if (anchor == null || target == null) return rule;
        android.graphics.Rect a = new android.graphics.Rect();
        android.graphics.Rect t = new android.graphics.Rect();
        anchor.getBoundsInScreen(a);
        target.getBoundsInScreen(t);
        if (a.isEmpty() || t.isEmpty()) return rule;
        rule.anchorViewId = safe(anchor.getViewIdResourceName());
        rule.anchorClassName = safe(anchor.getClassName());
        rule.anchorContentDescription = sanitizeDescription(anchor.getContentDescription());
        rule.anchorCenterX = a.centerX();
        rule.anchorCenterY = a.centerY();
        rule.targetOffsetX = t.centerX() - a.centerX();
        rule.targetOffsetY = t.centerY() - a.centerY();
        rule.anchored = true;

        List<Rule> rules = loadRules();
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule old = rules.get(i);
            if (sameActionSlot(old, rule)) rules.remove(i);
        }
        rules.add(0, rule);
        while (rules.size() > MAX_RULES) rules.remove(rules.size() - 1);
        saveRules(rules);
        return rule;
    }

    synchronized List<Rule> findRules(
            String packageName,
            String screenSignature,
            String stableScreenKey,
            String role) {
        String pkg = safe(packageName);
        String normalizedRole = normalizeRole(role);
        String currentStructure = safe(stableScreenKey);
        List<Rule> result = new ArrayList<>();
        for (Rule r : loadRules()) {
            if (!pkg.equals(r.packageName)) continue;
            if (!normalizedRole.equals(r.role)) continue;
            if (!r.enabled) continue;

            // Full screen signatures are deliberately not a hard requirement:
            // message/chat content changes them constantly. The structural key
            // is a soft affinity signal consumed by LearnedUiResolver.
            r.screenAffinityBonus = LearnedUiScopePolicy.affinityBonus(
                    r.stableScreenKey,
                    currentStructure);
            result.add(r);
        }
        return result;
    }

    synchronized List<Rule> findRules(
            String packageName,
            String screenSignature,
            String role) {
        return findRules(packageName, screenSignature, "", role);
    }

    synchronized void recordResultByIdentity(Rule target, boolean success) {
        recordResult(target, success);
    }

    synchronized void recordResult(Rule target, boolean success) {
        if (target == null) return;
        List<Rule> rules = loadRules();
        boolean hit = false;
        boolean invalidated = false;
        for (Rule r : rules) {
            if (!sameIdentity(r, target)) continue;
            if (success) {
                r.successCount++;
                r.lastVerifiedAt = System.currentTimeMillis();
                hit = true;
            } else {
                r.failureCount++;
                if (r.enabled) {
                    r.enabled = false;
                    invalidated = true;
                }
            }
            break;
        }
        saveRules(rules);
        if (hit) incrementCounter(KEY_ACTION_MEMORY_HITS);
        if (invalidated) incrementCounter(KEY_ACTION_MEMORY_INVALIDATIONS);
    }

    synchronized JSONObject updateRule(
            String id,
            String role,
            boolean enabled) {
        JSONObject out = new JSONObject();
        try {
            String targetId = safe(id).trim();
            String nextRole = normalizeRole(role);
            if (targetId.isEmpty() || nextRole.isEmpty()) {
                return out.put("success", false).put("error", "INVALID_ACTION_MEMORY_RULE");
            }

            List<Rule> rules = loadRules();
            Rule target = null;
            for (Rule rule : rules) {
                if (targetId.equals(rule.id)) {
                    target = rule;
                    break;
                }
            }
            if (target == null) {
                return out.put("success", false).put("error", "ACTION_MEMORY_NOT_FOUND");
            }

            target.role = nextRole;
            target.enabled = enabled;
            saveRules(rules);
            return out.put("success", true);
        } catch (Exception error) {
            try {
                return out.put("success", false).put("error", "ACTION_MEMORY_UPDATE_FAILED");
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    synchronized boolean deleteRule(String id) {
        String targetId = safe(id).trim();
        if (targetId.isEmpty()) return false;

        List<Rule> rules = loadRules();
        boolean removed = false;
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (targetId.equals(rules.get(i).id)) {
                rules.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) saveRules(rules);
        return removed;
    }

    synchronized int exactDuplicateCount() {
        List<Rule> rules = loadRules();
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        int duplicates = 0;
        for (Rule rule : rules) {
            String key = exactDuplicateKey(rule);
            int count = counts.containsKey(key) ? counts.get(key) : 0;
            if (count >= 1) duplicates++;
            counts.put(key, count + 1);
        }
        return duplicates;
    }

    /**
     * Conservative cleanup: merges only mappings with the same app, role and
     * structural selector. Screen signatures/stable keys may differ; those are
     * treated as observations of the same control, not separate controls.
     */
    synchronized int deduplicateExact() {
        List<Rule> rules = loadRules();
        HashMap<String, Rule> kept = new HashMap<String, Rule>();
        ArrayList<Rule> next = new ArrayList<Rule>();
        int removed = 0;

        for (Rule rule : rules) {
            String key = exactDuplicateKey(rule);
            Rule existing = kept.get(key);
            if (existing == null) {
                kept.put(key, rule);
                next.add(rule);
                continue;
            }

            mergeEvidence(existing, rule);
            removed++;
        }

        if (removed > 0) saveRules(next);
        return removed;
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

    static synchronized String buildActionMemoryReport(Context context) {
        if (context == null) return "App Action Memory\nNo context.";
        LearnedUiMappingStore store = new LearnedUiMappingStore(context);
        List<Rule> rules = store.loadRules();
        int active = 0;
        int invalidated = 0;
        for (Rule rule : rules) {
            if (rule.enabled) active++;
            else invalidated++;
        }

        StringBuilder out = new StringBuilder();
        out.append("App Action Memory\n");
        out.append("User-taught UI mappings; separate from Crew Experience.\n");
        out.append("Active mappings: ").append(active).append("\n");
        out.append("Invalidated mappings: ").append(invalidated).append("\n");
        out.append("Learned: ").append(store.prefs.getLong(KEY_ACTION_MEMORY_LEARNS, 0L))
                .append(" · relearned: ")
                .append(store.prefs.getLong(KEY_ACTION_MEMORY_RELEARNS, 0L)).append("\n");
        out.append("Verified hits: ").append(store.prefs.getLong(KEY_ACTION_MEMORY_HITS, 0L))
                .append(" · invalidations: ")
                .append(store.prefs.getLong(KEY_ACTION_MEMORY_INVALIDATIONS, 0L)).append("\n");
        out.append("Policy: first failed verified execution disables the mapping until re-taught.\n");
        out.append("Privacy: structural selector metadata and counters only; no typed or message text.");
        return out.toString();
    }

    private void migrateMissingIds() {
        synchronized (this) {
            List<Rule> rules = loadRules();
            boolean changed = false;
            for (Rule rule : rules) {
                if (rule.id == null || rule.id.trim().isEmpty()) {
                    rule.id = UUID.randomUUID().toString();
                    changed = true;
                }
            }
            if (changed) saveRules(rules);
        }
    }

    private static String exactDuplicateKey(Rule rule) {
        if (rule == null) return "";
        return safe(rule.packageName) + "|"
                + normalizeRole(rule.role) + "|"
                + safe(rule.viewId) + "|"
                + safe(rule.className) + "|"
                + safe(rule.contentDescription) + "|"
                + safe(rule.parentClassName) + "|"
                + safe(rule.relativePosition) + "|"
                + safe(rule.composerState) + "|"
                + safe(rule.composerClassName) + "|"
                + safe(rule.composerViewId) + "|"
                + safe(rule.anchorViewId) + "|"
                + safe(rule.anchorClassName) + "|"
                + safe(rule.anchorContentDescription) + "|"
                + rule.anchored + "|"
                + rule.targetOffsetX + "|"
                + rule.targetOffsetY;
    }

    private static void mergeEvidence(Rule keep, Rule duplicate) {
        if (keep == null || duplicate == null) return;
        keep.successCount += Math.max(0, duplicate.successCount);
        keep.failureCount += Math.max(0, duplicate.failureCount);
        keep.lastVerifiedAt = Math.max(keep.lastVerifiedAt, duplicate.lastVerifiedAt);
        keep.learnedAt = Math.max(keep.learnedAt, duplicate.learnedAt);
        keep.enabled = keep.enabled || duplicate.enabled;

        if (keep.screenSignature.isEmpty()) keep.screenSignature = duplicate.screenSignature;
        if (keep.stableScreenKey.isEmpty()) keep.stableScreenKey = duplicate.stableScreenKey;
        if (!keep.hasCoordinate() && duplicate.hasCoordinate()) {
            keep.centerX = duplicate.centerX;
            keep.centerY = duplicate.centerY;
        }
        if (!keep.anchored && duplicate.anchored) {
            keep.anchorViewId = duplicate.anchorViewId;
            keep.anchorClassName = duplicate.anchorClassName;
            keep.anchorContentDescription = duplicate.anchorContentDescription;
            keep.anchorCenterX = duplicate.anchorCenterX;
            keep.anchorCenterY = duplicate.anchorCenterY;
            keep.targetOffsetX = duplicate.targetOffsetX;
            keep.targetOffsetY = duplicate.targetOffsetY;
            keep.anchored = true;
        }
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

    private void incrementCounter(String key) {
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply();
    }

    private static boolean sameActionSlot(Rule a, Rule b) {
        if (a == null || b == null) return false;
        return LearnedUiScopePolicy.sameActionSlot(
                a.packageName,
                a.role,
                a.composerState,
                a.composerClassName,
                a.composerViewId,
                a.stableScreenKey,
                b.packageName,
                b.role,
                b.composerState,
                b.composerClassName,
                b.composerViewId,
                b.stableScreenKey);
    }

    private static boolean sameIdentity(Rule a, Rule b) {
        if (a == null || b == null) return false;
        if (!safe(a.id).isEmpty() && !safe(b.id).isEmpty()) {
            return a.id.equals(b.id);
        }
        return a.packageName.equals(b.packageName)
                && a.screenSignature.equals(b.screenSignature)
                && a.stableScreenKey.equals(b.stableScreenKey)
                && a.role.equals(b.role)
                && a.viewId.equals(b.viewId)
                && a.className.equals(b.className)
                && a.contentDescription.equals(b.contentDescription)
                && a.composerState.equals(b.composerState);
    }

    static String composerState(AccessibilityNodeInfo composer) {
        if (composer == null) return "UNKNOWN";
        CharSequence text = composer.getText();
        return text != null && text.length() > 0 ? "HAS_TEXT" : "EMPTY";
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
