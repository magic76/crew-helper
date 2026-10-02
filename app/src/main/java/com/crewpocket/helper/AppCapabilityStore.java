package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

/**
 * Declarative per-app capability storage.
 *
 * Local entries are user-owned and override remote entries with the same
 * package + capability id. Remote manifests are data only: no Java/JS/code is
 * downloaded or evaluated.
 */
final class AppCapabilityStore {
    static final String SOURCE_LOCAL = "local";
    static final String SOURCE_REMOTE = "remote";

    private static final String PREFS = "crew_app_capabilities";
    private static final String KEY_DATA = "capabilities_v1";
    private static final String KEY_REMOTE_URL = "remote_registry_url";
    private static final String KEY_REMOTE_REVISION = "remote_registry_revision";
    private static final String KEY_LAST_SYNC = "remote_registry_last_sync";
    private static final int MAX_APPS = 300;
    private static final int MAX_CAPABILITIES_PER_APP = 40;
    private static final Object LOCK = new Object();

    private final Context context;
    private final SharedPreferences prefs;

    AppCapabilityStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        this.prefs = this.context == null
                ? null
                : this.context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);
    }

    JSONObject saveLocal(
            String packageName,
            String appLabel,
            String capabilityId,
            String capabilityLabel,
            String kind,
            String uriTemplate,
            String intentAction,
            String mimeType) {
        synchronized (LOCK) {
            if (prefs == null) return failure("STORE_UNAVAILABLE");
            String pkg = cleanPackage(packageName);
            String id = cleanId(capabilityId);
            if (pkg.isEmpty()) return failure("PACKAGE_REQUIRED");
            if (!AppCapabilityTemplate.validCapabilityId(id)) {
                return failure("BAD_CAPABILITY_ID");
            }

            JSONObject normalized = normalizeCapability(
                    new JSONObject(),
                    id,
                    capabilityLabel,
                    kind,
                    uriTemplate,
                    intentAction,
                    mimeType,
                    SOURCE_LOCAL);
            if (normalized == null) {
                return failure("BAD_CAPABILITY_DEFINITION");
            }

            JSONObject root = loadRootLocked();
            JSONObject localApps = ensureObject(root, "localApps");
            JSONObject profile = localApps.optJSONObject(pkg);
            if (profile == null) profile = new JSONObject();

            put(profile, "label", cleanLabel(appLabel));
            JSONArray current = profile.optJSONArray("capabilities");
            if (current == null) current = new JSONArray();

            JSONArray next = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null) continue;
                if (id.equals(item.optString("id", ""))) {
                    next.put(normalized);
                    replaced = true;
                } else {
                    next.put(item);
                }
            }
            if (!replaced) {
                if (next.length() >= MAX_CAPABILITIES_PER_APP) {
                    return failure("APP_CAPABILITY_LIMIT");
                }
                next.put(normalized);
            }

            put(profile, "capabilities", next);
            put(profile, "updatedAt", System.currentTimeMillis());
            put(localApps, pkg, profile);
            put(root, "localApps", localApps);
            saveRootLocked(root);

            JSONObject out = success("SAVED");
            put(out, "package", pkg);
            put(out, "capability", id);
            return out;
        }
    }

    boolean deleteLocal(String packageName, String capabilityId) {
        synchronized (LOCK) {
            if (prefs == null) return false;
            String pkg = cleanPackage(packageName);
            String id = cleanId(capabilityId);
            JSONObject root = loadRootLocked();
            JSONObject localApps = root.optJSONObject("localApps");
            JSONObject profile = localApps == null
                    ? null
                    : localApps.optJSONObject(pkg);
            JSONArray current = profile == null
                    ? null
                    : profile.optJSONArray("capabilities");
            if (current == null) return false;

            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null) continue;
                if (id.equals(item.optString("id", ""))) {
                    removed = true;
                } else {
                    next.put(item);
                }
            }
            if (!removed) return false;

            if (next.length() == 0) {
                localApps.remove(pkg);
            } else {
                put(profile, "capabilities", next);
                put(localApps, pkg, profile);
            }
            saveRootLocked(root);
            return true;
        }
    }

    JSONArray mergedFor(String packageName) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            JSONArray out = new JSONArray();
            HashSet<String> seen = new HashSet<String>();

            JSONObject root = loadRootLocked();
            appendCapabilities(
                    out,
                    seen,
                    profile(root, "localApps", pkg));
            appendCapabilities(
                    out,
                    seen,
                    profile(root, "remoteApps", pkg));
            return copyArray(out);
        }
    }

    JSONArray localFor(String packageName) {
        synchronized (LOCK) {
            JSONObject profile = profile(
                    loadRootLocked(),
                    "localApps",
                    cleanPackage(packageName));
            return profile == null
                    ? new JSONArray()
                    : copyArray(profile.optJSONArray("capabilities"));
        }
    }

    ArrayList<String> profilePackages() {
        synchronized (LOCK) {
            HashSet<String> unique = new HashSet<String>();
            JSONObject root = loadRootLocked();
            collectPackages(root.optJSONObject("localApps"), unique);
            collectPackages(root.optJSONObject("remoteApps"), unique);
            return new ArrayList<String>(unique);
        }
    }

    int capabilityCount() {
        synchronized (LOCK) {
            int count = 0;
            for (String pkg : profilePackages()) {
                count += mergedFor(pkg).length();
            }
            return count;
        }
    }

    int appCount() {
        return profilePackages().size();
    }

    String labelFor(String packageName) {
        synchronized (LOCK) {
            String pkg = cleanPackage(packageName);
            JSONObject root = loadRootLocked();
            JSONObject local = profile(root, "localApps", pkg);
            JSONObject remote = profile(root, "remoteApps", pkg);
            String label = local == null
                    ? ""
                    : cleanLabel(local.optString("label", ""));
            if (label.isEmpty() && remote != null) {
                label = cleanLabel(remote.optString("label", ""));
            }
            if (label.isEmpty()) {
                label = AppRuntimeRegistry.displayName(context, pkg);
            }
            return label;
        }
    }

    String remoteUrl() {
        return prefs == null
                ? ""
                : safe(prefs.getString(KEY_REMOTE_URL, "")).trim();
    }

    void setRemoteUrl(String url) {
        if (prefs == null) return;
        String clean = safe(url).trim();
        if (clean.length() > 2048) clean = clean.substring(0, 2048);
        prefs.edit().putString(KEY_REMOTE_URL, clean).apply();
    }

    long lastSyncAt() {
        return prefs == null ? 0L : prefs.getLong(KEY_LAST_SYNC, 0L);
    }

    String remoteRevision() {
        return prefs == null
                ? ""
                : safe(prefs.getString(KEY_REMOTE_REVISION, ""));
    }

    JSONObject importRemoteManifest(String rawJson) {
        synchronized (LOCK) {
            if (prefs == null) return failure("STORE_UNAVAILABLE");
            try {
                JSONObject manifest = new JSONObject(rawJson == null ? "" : rawJson);
                int version = manifest.optInt("version", 0);
                if (version != 1) return failure("UNSUPPORTED_MANIFEST_VERSION");

                JSONArray apps = manifest.optJSONArray("apps");
                if (apps == null) return failure("MANIFEST_APPS_REQUIRED");
                if (apps.length() > MAX_APPS) return failure("MANIFEST_TOO_MANY_APPS");

                JSONObject remoteApps = new JSONObject();
                int capabilityCount = 0;
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject sourceProfile = apps.optJSONObject(i);
                    if (sourceProfile == null) continue;

                    String pkg = cleanPackage(sourceProfile.optString("package", ""));
                    if (pkg.isEmpty()) continue;

                    JSONArray sourceCapabilities =
                            sourceProfile.optJSONArray("capabilities");
                    if (sourceCapabilities == null) continue;
                    if (sourceCapabilities.length() > MAX_CAPABILITIES_PER_APP) {
                        return failure("MANIFEST_APP_CAPABILITY_LIMIT");
                    }

                    JSONArray cleanCapabilities = new JSONArray();
                    HashSet<String> ids = new HashSet<String>();
                    for (int j = 0; j < sourceCapabilities.length(); j++) {
                        JSONObject item = sourceCapabilities.optJSONObject(j);
                        if (item == null) continue;
                        String id = cleanId(item.optString("id", ""));
                        if (!AppCapabilityTemplate.validCapabilityId(id)
                                || !ids.add(id)) {
                            continue;
                        }

                        JSONObject normalized = normalizeCapability(
                                item,
                                id,
                                item.optString("label", id),
                                item.optString("kind", "URI"),
                                item.optString(
                                        "uriTemplate",
                                        item.optString("uri", "")),
                                item.optString("action", ""),
                                item.optString("mimeType", ""),
                                SOURCE_REMOTE);
                        if (normalized != null) {
                            cleanCapabilities.put(normalized);
                            capabilityCount++;
                        }
                    }
                    if (cleanCapabilities.length() == 0) continue;

                    JSONObject profile = new JSONObject();
                    put(profile, "label",
                            cleanLabel(sourceProfile.optString("label", "")));
                    put(profile, "capabilities", cleanCapabilities);
                    put(remoteApps, pkg, profile);
                }

                JSONObject root = loadRootLocked();
                put(root, "remoteApps", remoteApps);
                saveRootLocked(root);

                String revision = safe(manifest.optString("revision", ""));
                prefs.edit()
                        .putString(KEY_REMOTE_REVISION, revision)
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .apply();

                JSONObject out = success("IMPORTED");
                put(out, "apps", remoteApps.length());
                put(out, "capabilities", capabilityCount);
                put(out, "revision", revision);
                return out;
            } catch (Exception error) {
                return failure("BAD_REMOTE_MANIFEST");
            }
        }
    }

    private JSONObject normalizeCapability(
            JSONObject source,
            String id,
            String label,
            String kind,
            String uriTemplate,
            String intentAction,
            String mimeType,
            String sourceName) {
        String resolvedKind = safe(kind).trim().toUpperCase(java.util.Locale.ROOT);
        String uri = safe(uriTemplate).trim();
        String action = safe(intentAction).trim();
        if (resolvedKind.isEmpty()) resolvedKind = "URI";
        if (!"URI".equals(resolvedKind) && !"INTENT".equals(resolvedKind)) {
            return null;
        }
        if (uri.isEmpty()) return null;
        try {
            // Replace placeholders with harmless sample text solely to validate
            // final URI structure without executing anything.
            java.util.HashMap<String, String> samples =
                    new java.util.HashMap<String, String>();
            for (String param : AppCapabilityTemplate.requiredParams(uri)) {
                samples.put(param,
                        "url".equals(param)
                                ? "https://example.com/"
                                : "sample");
            }
            AppCapabilityTemplate.expand(uri, samples);
        } catch (Exception ignored) {
            return null;
        }

        JSONObject out = new JSONObject();
        put(out, "id", id);
        put(out, "label",
                cleanLabel(label).isEmpty() ? id : cleanLabel(label));
        put(out, "kind", resolvedKind);
        put(out, "uriTemplate", uri);
        if (!action.isEmpty()) put(out, "action", action);
        String mime = safe(mimeType).trim();
        if (!mime.isEmpty()) put(out, "mimeType", mime);
        put(out, "enabled", source.optBoolean("enabled", true));
        put(out, "source", sourceName);
        put(out, "risk", "LOW");
        put(out, "params",
                new JSONArray(AppCapabilityTemplate.requiredParams(uri)));
        return out;
    }

    private void appendCapabilities(
            JSONArray out,
            HashSet<String> seen,
            JSONObject profile) {
        JSONArray list = profile == null
                ? null
                : profile.optJSONArray("capabilities");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) continue;
            String id = cleanId(item.optString("id", ""));
            if (id.isEmpty() || !seen.add(id)) continue;
            out.put(item);
        }
    }

    private JSONObject profile(
            JSONObject root,
            String bucket,
            String pkg) {
        JSONObject apps = root == null ? null : root.optJSONObject(bucket);
        return apps == null ? null : apps.optJSONObject(pkg);
    }

    private void collectPackages(
            JSONObject source,
            HashSet<String> out) {
        if (source == null) return;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String pkg = cleanPackage(keys.next());
            if (!pkg.isEmpty()) out.add(pkg);
        }
    }

    private JSONObject loadRootLocked() {
        if (prefs == null) return freshRoot();
        String raw = prefs.getString(KEY_DATA, "");
        if (raw == null || raw.trim().isEmpty()) return freshRoot();
        try {
            JSONObject root = new JSONObject(raw);
            ensureObject(root, "localApps");
            ensureObject(root, "remoteApps");
            return root;
        } catch (Exception ignored) {
            return freshRoot();
        }
    }

    private void saveRootLocked(JSONObject root) {
        if (prefs == null || root == null) return;
        prefs.edit().putString(KEY_DATA, root.toString()).apply();
    }

    private JSONObject freshRoot() {
        JSONObject root = new JSONObject();
        put(root, "version", 1);
        put(root, "localApps", new JSONObject());
        put(root, "remoteApps", new JSONObject());
        return root;
    }

    private JSONObject ensureObject(JSONObject root, String key) {
        JSONObject object = root.optJSONObject(key);
        if (object == null) {
            object = new JSONObject();
            put(root, key, object);
        }
        return object;
    }

    private static JSONArray copyArray(JSONArray source) {
        if (source == null) return new JSONArray();
        try { return new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject success(String result) {
        JSONObject out = new JSONObject();
        put(out, "success", true);
        put(out, "result", result);
        return out;
    }

    private static JSONObject failure(String error) {
        JSONObject out = new JSONObject();
        put(out, "success", false);
        put(out, "error", error);
        return out;
    }

    private static String cleanPackage(String value) {
        String clean = safe(value).trim();
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return clean.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String cleanId(String value) {
        return safe(value).trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
    }

    private static String cleanLabel(String value) {
        String clean = safe(value).replaceAll("\\s+", " ").trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) {}
    }
}
