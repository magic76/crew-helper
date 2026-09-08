package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 0030: global user-taught aliases for installed Android apps. */
final class AppAliasStore {
    private static final String PREFS = "crew_app_aliases";
    private static final String KEY_ALIASES = "aliases_v1";
    private static final int MAX_ALIASES = 120;

    static final class Entry {
        String alias = "";
        String packageName = "";
        String label = "";
        long learnedAt;
        long lastUsedAt;

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try {
                out.put("alias", alias)
                        .put("packageName", packageName)
                        .put("label", label)
                        .put("learnedAt", learnedAt)
                        .put("lastUsedAt", lastUsedAt);
            } catch (Exception ignored) {}
            return out;
        }

        static Entry fromJson(JSONObject in) {
            Entry out = new Entry();
            if (in == null) return out;
            out.alias = in.optString("alias", "");
            out.packageName = in.optString("packageName", "");
            out.label = in.optString("label", "");
            out.learnedAt = in.optLong("learnedAt", 0L);
            out.lastUsedAt = in.optLong("lastUsedAt", 0L);
            return out;
        }
    }

    private final SharedPreferences prefs;

    AppAliasStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Entry resolve(String query) {
        String wanted = normalize(query);
        if (wanted.isEmpty()) return null;
        List<Entry> entries = load();
        Entry hit = null;
        for (Entry e : entries) {
            if (wanted.equals(normalize(e.alias))) {
                hit = e;
                break;
            }
        }
        if (hit == null) return null;
        hit.lastUsedAt = System.currentTimeMillis();
        saveAll(entries);
        return copy(hit);
    }

    synchronized Entry save(String alias, String packageName, String label) {
        String cleanAlias = clip(alias, 100);
        String pkg = clip(packageName, 180);
        String cleanLabel = clip(label, 120);
        if (normalize(cleanAlias).isEmpty() || pkg.isEmpty()) return null;

        List<Entry> entries = load();
        Entry target = null;
        for (Entry e : entries) {
            if (normalize(cleanAlias).equals(normalize(e.alias))) {
                target = e;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (target == null) {
            target = new Entry();
            target.alias = cleanAlias;
            target.learnedAt = now;
            entries.add(0, target);
        }
        target.packageName = pkg;
        target.label = cleanLabel;
        target.lastUsedAt = now;

        while (entries.size() > MAX_ALIASES) entries.remove(entries.size() - 1);
        saveAll(entries);
        return copy(target);
    }

    synchronized void delete(String alias) {
        String wanted = normalize(alias);
        List<Entry> entries = load();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (wanted.equals(normalize(entries.get(i).alias))) entries.remove(i);
        }
        saveAll(entries);
    }

    private List<Entry> load() {
        ArrayList<Entry> out = new ArrayList<Entry>();
        String raw = prefs.getString(KEY_ALIASES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Entry e = Entry.fromJson(arr.optJSONObject(i));
                if (!normalize(e.alias).isEmpty() && !e.packageName.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveAll(List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) arr.put(e.toJson());
        prefs.edit().putString(KEY_ALIASES, arr.toString()).apply();
    }

    private static Entry copy(Entry in) {
        Entry out = new Entry();
        out.alias = in.alias;
        out.packageName = in.packageName;
        out.label = in.label;
        out.learnedAt = in.learnedAt;
        out.lastUsedAt = in.lastUsedAt;
        return out;
    }

    private static String normalize(String value) {
        return TextMatch.caseFold(value == null ? "" : value).trim();
    }

    private static String clip(String value, int max) {
        String out = value == null ? "" : value.trim();
        return out.length() <= max ? out : out.substring(0, max);
    }
}
