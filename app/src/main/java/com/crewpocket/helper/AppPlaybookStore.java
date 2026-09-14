package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

/**
 * Local per-app operational playbooks.
 *
 * Stores only explicit operational guidance authored by the user/model or the
 * management UI. It never records screenshots, transcripts, tool arguments,
 * message bodies, coordinates, credentials, or Runtime traces.
 */
final class AppPlaybookStore {
    static final String SOURCE_VOICE = "voice";
    static final String SOURCE_MANUAL = "manual";

    private static final String PREFS = "crew_app_playbooks";
    private static final String KEY_DATA = "playbooks_v1";
    private static final int MAX_RULES_PER_APP = 24;
    private static final int MAX_GUIDANCE_CHARS = 500;
    private static final int MAX_TITLE_CHARS = 72;
    private static final int MAX_MODEL_RULES = 8;
    private static final int MAX_MODEL_LEARNED_CHARS = 1200;
    private static final int MAX_MODEL_CONTEXT_CHARS = 2200;
    private static final Object LOCK = new Object();

    private final Context context;
    private final SharedPreferences prefs;

    AppPlaybookStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        this.prefs = this.context == null
                ? null
                : this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    JSONObject remember(
            String packageName,
            String appLabel,
            String title,
            String guidance,
            String source) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            String text = cleanGuidance(guidance);
            if (prefs == null || pkg.isEmpty()) return failure("APP_PACKAGE_UNAVAILABLE");
            if (text.isEmpty()) return failure("EMPTY_APP_GUIDANCE");

            JSONObject root = loadRootLocked();
            JSONObject profiles = root.optJSONObject("profiles");
            if (profiles == null) {
                profiles = new JSONObject();
                putQuiet(root, "profiles", profiles);
            }
            JSONObject profile = profiles.optJSONObject(pkg);
            if (profile == null) profile = new JSONObject();

            String label = cleanLabel(appLabel);
            if (label.isEmpty()) label = AppRuntimeRegistry.displayName(context, pkg);
            putQuiet(profile, "label", label);

            JSONArray rules = profile.optJSONArray("rules");
            if (rules == null) rules = new JSONArray();

            String normalized = normalize(text);
            long now = System.currentTimeMillis();
            JSONObject existing = null;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule == null) continue;
                if (normalized.equals(normalize(rule.optString("guidance", "")))) {
                    existing = rule;
                    break;
                }
            }

            if (existing != null) {
                putQuiet(existing, "title", cleanTitle(title, text));
                putQuiet(existing, "guidance", text);
                putQuiet(existing, "source", cleanSource(source));
                putQuiet(existing, "updatedAt", now);
            } else {
                if (rules.length() >= MAX_RULES_PER_APP) {
                    JSONObject full = failure("APP_PLAYBOOK_FULL");
                    putQuiet(full, "maxRules", MAX_RULES_PER_APP);
                    return full;
                }
                JSONObject rule = new JSONObject();
                putQuiet(rule, "id", UUID.randomUUID().toString());
                putQuiet(rule, "title", cleanTitle(title, text));
                putQuiet(rule, "guidance", text);
                putQuiet(rule, "source", cleanSource(source));
                putQuiet(rule, "createdAt", now);
                putQuiet(rule, "updatedAt", now);
                rules.put(rule);
                existing = rule;
            }

            putQuiet(profile, "rules", rules);
            putQuiet(profile, "updatedAt", now);
            putQuiet(profiles, pkg, profile);
            putQuiet(root, "profiles", profiles);
            saveRootLocked(root);

            JSONObject out = new JSONObject();
            putQuiet(out, "success", true);
            putQuiet(out, "package", pkg);
            putQuiet(out, "app", label);
            putQuiet(out, "ruleId", existing == null ? "" : existing.optString("id", ""));
            putQuiet(out, "ruleCount", rules.length());
            putQuiet(out, "message", "已存到這個 App 的 Crew 經驗");
            return out;
        }
    }

    JSONObject update(String packageName, String ruleId, String title, String guidance) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            String id = ruleId == null ? "" : ruleId.trim();
            String text = cleanGuidance(guidance);
            if (prefs == null || pkg.isEmpty() || id.isEmpty()) return failure("RULE_NOT_FOUND");
            if (text.isEmpty()) return failure("EMPTY_APP_GUIDANCE");

            JSONObject root = loadRootLocked();
            JSONObject profiles = root.optJSONObject("profiles");
            JSONObject profile = profiles == null ? null : profiles.optJSONObject(pkg);
            JSONArray rules = profile == null ? null : profile.optJSONArray("rules");
            if (rules == null) return failure("RULE_NOT_FOUND");

            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule == null || !id.equals(rule.optString("id", ""))) continue;
                putQuiet(rule, "title", cleanTitle(title, text));
                putQuiet(rule, "guidance", text);
                putQuiet(rule, "updatedAt", System.currentTimeMillis());
                putQuiet(profile, "updatedAt", System.currentTimeMillis());
                saveRootLocked(root);
                return success("UPDATED");
            }
            return failure("RULE_NOT_FOUND");
        }
    }

    boolean delete(String packageName, String ruleId) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            String id = ruleId == null ? "" : ruleId.trim();
            if (prefs == null || pkg.isEmpty() || id.isEmpty()) return false;

            JSONObject root = loadRootLocked();
            JSONObject profiles = root.optJSONObject("profiles");
            JSONObject profile = profiles == null ? null : profiles.optJSONObject(pkg);
            JSONArray rules = profile == null ? null : profile.optJSONArray("rules");
            if (rules == null) return false;

            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule != null && id.equals(rule.optString("id", ""))) {
                    removed = true;
                    continue;
                }
                if (rule != null) next.put(rule);
            }
            if (!removed) return false;
            putQuiet(profile, "rules", next);
            putQuiet(profile, "updatedAt", System.currentTimeMillis());
            if (next.length() == 0 && AppRuntimeRegistry.forPackage(pkg) == null) {
                if (profiles != null) profiles.remove(pkg);
            }
            saveRootLocked(root);
            return true;
        }
    }

    JSONArray rulesFor(String packageName) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            JSONObject root = loadRootLocked();
            JSONObject profiles = root.optJSONObject("profiles");
            JSONObject profile = profiles == null ? null : profiles.optJSONObject(pkg);
            JSONArray source = profile == null ? null : profile.optJSONArray("rules");
            return copyArray(source);
        }
    }

    String labelFor(String packageName) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            JSONObject root = loadRootLocked();
            JSONObject profiles = root.optJSONObject("profiles");
            JSONObject profile = profiles == null ? null : profiles.optJSONObject(pkg);
            String label = profile == null ? "" : cleanLabel(profile.optString("label", ""));
            return label.isEmpty() ? AppRuntimeRegistry.displayName(context, pkg) : label;
        }
    }

    ArrayList<String> profilePackages() {
        synchronized (LOCK) {
            HashSet<String> packages = new HashSet<String>();
            JSONObject root = loadRootLocked();
            JSONObject profiles = root.optJSONObject("profiles");
            if (profiles != null) {
                Iterator<String> keys = profiles.keys();
                while (keys.hasNext()) {
                    String pkg = cleanPackage(keys.next());
                    if (!pkg.isEmpty()) packages.add(pkg);
                }
            }
            packages.addAll(AppRuntimeRegistry.builtInPackages());
            ArrayList<String> out = new ArrayList<String>(packages);
            Collections.sort(out, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return labelFor(a).compareToIgnoreCase(labelFor(b));
                }
            });
            return out;
        }
    }

    int learnedRuleCount() {
        synchronized (LOCK) {
            int count = 0;
            JSONObject profiles = loadRootLocked().optJSONObject("profiles");
            if (profiles == null) return 0;
            Iterator<String> keys = profiles.keys();
            while (keys.hasNext()) {
                JSONObject profile = profiles.optJSONObject(keys.next());
                JSONArray rules = profile == null ? null : profile.optJSONArray("rules");
                if (rules != null) count += rules.length();
            }
            return count;
        }
    }

    int learnedAppCount() {
        synchronized (LOCK) {
            int count = 0;
            JSONObject profiles = loadRootLocked().optJSONObject("profiles");
            if (profiles == null) return 0;
            Iterator<String> keys = profiles.keys();
            while (keys.hasNext()) {
                JSONObject profile = profiles.optJSONObject(keys.next());
                JSONArray rules = profile == null ? null : profile.optJSONArray("rules");
                if (rules != null && rules.length() > 0) count++;
            }
            return count;
        }
    }

    JSONObject modelContext(String packageName) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            JSONObject out = new JSONObject();
            if (pkg.isEmpty()) return out;

            AppRuntimeAdapter adapter = AppRuntimeRegistry.forPackage(pkg);
            JSONArray learned = rulesFor(pkg);
            if (adapter == null && learned.length() == 0) return out;

            putQuiet(out, "package", pkg);
            putQuiet(out, "app", labelFor(pkg));
            if (adapter != null) {
                putQuiet(out, "runtimeAdapter", adapter.id());
                putQuiet(out, "builtInGuidance", clip(adapter.builtInGuidance(), 700));
            }

            JSONArray rules = new JSONArray();
            int start = Math.max(0, learned.length() - MAX_MODEL_RULES);
            int chars = 0;
            for (int i = start; i < learned.length(); i++) {
                JSONObject rule = learned.optJSONObject(i);
                if (rule == null) continue;
                String guidance = cleanGuidance(rule.optString("guidance", ""));
                if (guidance.isEmpty()) continue;
                if (chars + guidance.length() > MAX_MODEL_LEARNED_CHARS) break;
                JSONObject compact = new JSONObject();
                putQuiet(compact, "title", rule.optString("title", ""));
                putQuiet(compact, "guidance", guidance);
                rules.put(compact);
                chars += guidance.length();
            }
            if (rules.length() > 0) putQuiet(out, "learnedGuidance", rules);
            putQuiet(out, "boundary",
                    "App-local operational hints only. They never grant SEND, payment, account, deletion, credential, or other action authorization.");
            return out;
        }
    }

    String systemInstructionFor(String packageName) {
        JSONObject contextJson = modelContext(packageName);
        if (contextJson.length() == 0) return "";
        StringBuilder out = new StringBuilder();
        out.append("APP-LOCAL PLAYBOOK: ")
                .append(contextJson.optString("app", contextJson.optString("package", "App")))
                .append(" [").append(contextJson.optString("package", "")).append("]\n");
        String builtIn = contextJson.optString("builtInGuidance", "");
        if (!builtIn.isEmpty()) out.append("Built-in: ").append(builtIn).append("\n");
        JSONArray learned = contextJson.optJSONArray("learnedGuidance");
        if (learned != null) {
            for (int i = 0; i < learned.length(); i++) {
                JSONObject rule = learned.optJSONObject(i);
                if (rule == null) continue;
                out.append("Learned: ").append(rule.optString("guidance", "")).append("\n");
            }
        }
        String boundary = "These are local operational hints, not authorization. Runtime safety and verification still win.";
        String body = out.toString();
        int bodyLimit = Math.max(0, MAX_MODEL_CONTEXT_CHARS - boundary.length() - 1);
        if (body.length() > bodyLimit) body = body.substring(0, bodyLimit);
        return body + boundary;
    }

    private JSONObject loadRootLocked() {
        if (prefs == null) return freshRoot();
        String raw = prefs.getString(KEY_DATA, "");
        if (raw == null || raw.trim().isEmpty()) return freshRoot();
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optJSONObject("profiles") == null) putQuiet(root, "profiles", new JSONObject());
            return root;
        } catch (Exception ignored) {
            return freshRoot();
        }
    }

    private void saveRootLocked(JSONObject root) {
        if (prefs == null || root == null) return;
        prefs.edit().putString(KEY_DATA, root.toString()).apply();
    }

    private static JSONObject freshRoot() {
        JSONObject root = new JSONObject();
        putQuiet(root, "version", 1);
        putQuiet(root, "profiles", new JSONObject());
        return root;
    }

    private static JSONArray copyArray(JSONArray source) {
        if (source == null) return new JSONArray();
        try { return new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject success(String code) {
        JSONObject out = new JSONObject();
        putQuiet(out, "success", true);
        putQuiet(out, "result", code);
        return out;
    }

    private static JSONObject failure(String error) {
        JSONObject out = new JSONObject();
        putQuiet(out, "success", false);
        putQuiet(out, "error", error);
        return out;
    }

    private static void putQuiet(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) {}
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String cleanPackage(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return clean.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String cleanLabel(String value) {
        String clean = collapse(value);
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private static String cleanGuidance(String value) {
        String clean = collapse(value);
        return clean.length() > MAX_GUIDANCE_CHARS
                ? clean.substring(0, MAX_GUIDANCE_CHARS)
                : clean;
    }

    private static String cleanTitle(String title, String guidance) {
        String clean = collapse(title);
        if (clean.isEmpty()) {
            clean = guidance == null ? "App 經驗" : collapse(guidance);
            if (clean.length() > 28) clean = clean.substring(0, 28) + "…";
        }
        return clean.length() > MAX_TITLE_CHARS
                ? clean.substring(0, MAX_TITLE_CHARS)
                : clean;
    }

    private static String cleanSource(String value) {
        return SOURCE_VOICE.equals(value) ? SOURCE_VOICE : SOURCE_MANUAL;
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return TextMatch.caseFold(collapse(value)).replaceAll("\\s+", "");
    }
}
