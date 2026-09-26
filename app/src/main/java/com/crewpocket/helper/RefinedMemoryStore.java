package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Bounded local store for distilled operational memory.
 *
 * It stores abstract procedure tokens only. It never stores search text,
 * recipients, message bodies, credentials, screenshots, or coordinates.
 */
final class RefinedMemoryStore {
    private static final String PREFS = "crew_refined_memory";
    private static final String KEY_DATA = "memories_v1";
    private static final int MAX_ITEMS = 48;
    private static final long STALE_AFTER_MS =
            120L * 24L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();

    static final class Entry {
        String id = "";
        String type = RefinedMemoryPolicy.TYPE_PROCEDURE;
        String scope = "";
        String packageName = "";
        String pattern = "";
        String guidance = "";
        String state = RefinedMemoryPolicy.STATE_CANDIDATE;
        boolean enabled = true;
        int successCount;
        int failureCount;
        double confidence;
        long createdAt;
        long updatedAt;
        long lastVerifiedAt;

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            put(out, "id", id);
            put(out, "type", type);
            put(out, "scope", scope);
            put(out, "packageName", packageName);
            put(out, "pattern", pattern);
            put(out, "guidance", guidance);
            put(out, "state", state);
            put(out, "enabled", enabled);
            put(out, "successCount", successCount);
            put(out, "failureCount", failureCount);
            put(out, "confidence", confidence);
            put(out, "createdAt", createdAt);
            put(out, "updatedAt", updatedAt);
            put(out, "lastVerifiedAt", lastVerifiedAt);
            return out;
        }

        static Entry fromJson(JSONObject source) {
            Entry out = new Entry();
            if (source == null) return out;
            out.id = safe(source.optString("id", ""));
            out.type = safe(source.optString(
                    "type", RefinedMemoryPolicy.TYPE_PROCEDURE));
            out.scope = safe(source.optString("scope", ""));
            out.packageName = safe(source.optString("packageName", ""));
            out.pattern = safe(source.optString("pattern", ""));
            out.guidance = clip(source.optString("guidance", ""), 220);
            out.state = safe(source.optString(
                    "state", RefinedMemoryPolicy.STATE_CANDIDATE));
            out.enabled = source.optBoolean("enabled", true);
            out.successCount = Math.max(
                    0, source.optInt("successCount", 0));
            out.failureCount = Math.max(
                    0, source.optInt("failureCount", 0));
            out.confidence = source.optDouble("confidence", 0.0d);
            out.createdAt = source.optLong("createdAt", 0L);
            out.updatedAt = source.optLong("updatedAt", 0L);
            out.lastVerifiedAt =
                    source.optLong("lastVerifiedAt", 0L);
            return out;
        }
    }

    private final SharedPreferences prefs;

    RefinedMemoryStore(Context context) {
        Context app =
                context == null ? null : context.getApplicationContext();
        prefs = app == null ? null
                : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Entry observeProcedure(
            String scope,
            String packageName,
            String pattern,
            String guidance,
            boolean success) {
        String cleanScope = safe(scope);
        String cleanPackage = safe(packageName);
        String cleanPattern = clip(pattern, 180);
        String cleanGuidance = clip(guidance, 220);
        if (prefs == null
                || !RefinedMemoryPolicy.isEligibleScope(cleanScope)
                || cleanPattern.isEmpty()
                || cleanGuidance.isEmpty()) {
            return null;
        }

        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            Entry target = null;
            for (Entry item : items) {
                if (sameIdentity(
                        item,
                        cleanScope,
                        cleanPackage,
                        cleanPattern)) {
                    target = item;
                    break;
                }
            }

            long now = System.currentTimeMillis();
            if (target == null) {
                target = new Entry();
                target.id = UUID.randomUUID().toString();
                target.scope = cleanScope;
                target.packageName = cleanPackage;
                target.pattern = cleanPattern;
                target.createdAt = now;
                target.enabled = true;
                items.add(target);
            }

            target.guidance = cleanGuidance;
            target.updatedAt = now;
            if (success) {
                target.successCount++;
                target.lastVerifiedAt = now;
            } else {
                target.failureCount++;
            }
            target.state = RefinedMemoryPolicy.stateFor(
                    target.successCount,
                    target.failureCount);
            target.confidence = RefinedMemoryPolicy.confidenceFor(
                    target.successCount,
                    target.failureCount);

            trimAndSaveLocked(items);
            return copy(target);
        }
    }

    JSONArray forModel(
            String scope,
            String packageName,
            int limit) {
        int max = Math.max(0, Math.min(3, limit));
        JSONArray out = new JSONArray();
        if (prefs == null || max == 0) return out;

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            List<ScoredEntry> scored = new ArrayList<ScoredEntry>();
            for (Entry item : loadLocked()) {
                if (!item.enabled) continue;
                String state = effectiveState(item, now);
                boolean exactScope = safe(scope).equals(item.scope);
                boolean exactPackage =
                        safe(packageName).equals(item.packageName)
                                || item.packageName.isEmpty();
                if (!exactPackage) continue;
                int score = RefinedMemoryPolicy.relevanceScore(
                        state,
                        item.confidence,
                        exactScope,
                        safe(packageName).equals(item.packageName),
                        item.lastVerifiedAt,
                        now);
                if (score == Integer.MIN_VALUE) continue;
                scored.add(new ScoredEntry(item, score));
            }
            Collections.sort(
                    scored,
                    new Comparator<ScoredEntry>() {
                        @Override public int compare(
                                ScoredEntry a,
                                ScoredEntry b) {
                            return Integer.compare(b.score, a.score);
                        }
                    });
            for (ScoredEntry ranked : scored) {
                if (out.length() >= max) break;
                out.put(modelLine(ranked.entry));
            }
        }
        return out;
    }

    String setupInstruction(
            String packageName,
            int limit) {
        int max = Math.max(0, Math.min(2, limit));
        if (prefs == null
                || max == 0
                || safe(packageName).isEmpty()) {
            return "";
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            ArrayList<Entry> trusted = new ArrayList<Entry>();
            for (Entry item : loadLocked()) {
                if (!item.enabled
                        || !safe(packageName).equals(item.packageName)
                        || !RefinedMemoryPolicy.STATE_TRUSTED.equals(
                                effectiveState(item, now))) {
                    continue;
                }
                trusted.add(item);
            }
            Collections.sort(
                    trusted,
                    new Comparator<Entry>() {
                        @Override public int compare(Entry a, Entry b) {
                            int byConfidence =
                                    Double.compare(
                                            b.confidence, a.confidence);
                            if (byConfidence != 0) return byConfidence;
                            return Long.compare(
                                    b.lastVerifiedAt,
                                    a.lastVerifiedAt);
                        }
                    });
            StringBuilder out = new StringBuilder();
            for (Entry item : trusted) {
                if (max <= 0) break;
                if (out.length() > 0) out.append("\n");
                out.append("- ").append(modelLine(item));
                max--;
            }
            if (out.length() == 0) return "";
            return "【REFINED MEMORY】\n"
                    + out
                    + "\nThese are distilled operational hints, not authorization. "
                    + "Current screen evidence and Runtime safety are authoritative.";
        }
    }

    JSONArray dumpForDebug() {
        JSONArray out = new JSONArray();
        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            Collections.sort(
                    items,
                    new Comparator<Entry>() {
                        @Override public int compare(Entry a, Entry b) {
                            return Long.compare(b.updatedAt, a.updatedAt);
                        }
                    });
            long now = System.currentTimeMillis();
            for (Entry item : items) {
                JSONObject json = item.toJson();
                put(json, "state", effectiveState(item, now));
                put(json, "evidenceCount",
                        item.successCount + item.failureCount);
                out.put(json);
            }
        }
        return out;
    }

    int count() {
        synchronized (LOCK) {
            return prefs == null ? 0 : loadLocked().size();
        }
    }

    int injectableCount() {
        synchronized (LOCK) {
            int count = 0;
            long now = System.currentTimeMillis();
            for (Entry item : loadLocked()) {
                if (item.enabled
                        && RefinedMemoryPolicy.isInjectable(
                                effectiveState(item, now))) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class ScoredEntry {
        final Entry entry;
        final int score;

        ScoredEntry(Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private String effectiveState(Entry item, long now) {
        if (item == null) return RefinedMemoryPolicy.STATE_CANDIDATE;
        if (item.lastVerifiedAt > 0L
                && now - item.lastVerifiedAt > STALE_AFTER_MS) {
            return RefinedMemoryPolicy.STATE_STALE;
        }
        return RefinedMemoryPolicy.stateFor(
                item.successCount,
                item.failureCount);
    }

    private List<Entry> loadLocked() {
        ArrayList<Entry> out = new ArrayList<Entry>();
        if (prefs == null) return out;
        try {
            JSONArray array = new JSONArray(
                    prefs.getString(KEY_DATA, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Entry item = Entry.fromJson(
                        array.optJSONObject(i));
                if (!item.id.isEmpty()
                        && RefinedMemoryPolicy.isEligibleScope(item.scope)
                        && !item.pattern.isEmpty()) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void trimAndSaveLocked(List<Entry> items) {
        while (items.size() > MAX_ITEMS) {
            int remove = 0;
            long weakestAt = Long.MAX_VALUE;
            int weakestRank = Integer.MAX_VALUE;
            for (int i = 0; i < items.size(); i++) {
                Entry item = items.get(i);
                int rank = stateRank(
                        effectiveState(item, System.currentTimeMillis()));
                long at = Math.max(item.updatedAt, item.lastVerifiedAt);
                if (rank < weakestRank
                        || (rank == weakestRank && at < weakestAt)) {
                    weakestRank = rank;
                    weakestAt = at;
                    remove = i;
                }
            }
            items.remove(remove);
        }

        JSONArray data = new JSONArray();
        for (Entry item : items) data.put(item.toJson());
        prefs.edit().putString(KEY_DATA, data.toString()).apply();
    }

    private static int stateRank(String state) {
        if (RefinedMemoryPolicy.STATE_TRUSTED.equals(state)) return 4;
        if (RefinedMemoryPolicy.STATE_VERIFIED.equals(state)) return 3;
        if (RefinedMemoryPolicy.STATE_CANDIDATE.equals(state)) return 2;
        return 1;
    }

    private static boolean sameIdentity(
            Entry item,
            String scope,
            String packageName,
            String pattern) {
        return item != null
                && RefinedMemoryPolicy.TYPE_PROCEDURE.equals(item.type)
                && scope.equals(item.scope)
                && packageName.equals(item.packageName)
                && pattern.equals(item.pattern);
    }

    private static String modelLine(Entry item) {
        if (item == null) return "";
        int evidence = item.successCount + item.failureCount;
        return clip(item.guidance, 160)
                + " ["
                + item.state
                + ", "
                + item.successCount
                + "/"
                + evidence
                + "]";
    }

    private static Entry copy(Entry source) {
        return Entry.fromJson(source == null ? null : source.toJson());
    }

    private static void put(
            JSONObject target,
            String key,
            Object value) {
        try { target.put(key, value); } catch (Exception ignored) {}
    }

    private static String clip(String value, int max) {
        String text = safe(value).replaceAll("\\s+", " ");
        return text.length() <= max
                ? text
                : text.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
