package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.List;

/** Cached launcher app catalog for fast voice launch. */
final class AppCatalog {
    private static final long TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_MATCHES = 12;

    static final class Entry {
        final String label;
        final String packageName;
        Entry(String label, String packageName) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
        }
    }

    static final class Resolution {
        static final String FOUND = "FOUND";
        static final String MULTIPLE = "MULTIPLE_MATCHES";
        static final String NOT_FOUND = "NOT_FOUND";
        final String status;
        final Entry chosen;
        final ArrayList<Entry> matches;
        final long resolveMs;
        Resolution(String status, Entry chosen, ArrayList<Entry> matches, long resolveMs) {
            this.status = status;
            this.chosen = chosen;
            this.matches = matches == null ? new ArrayList<Entry>() : matches;
            this.resolveMs = resolveMs;
        }
    }

    private final Context context;
    private final AppAliasStore aliasStore;
    private final Object lock = new Object();
    private ArrayList<Entry> entries = new ArrayList<Entry>();
    private long loadedAtMs = 0L;

    AppCatalog(Context context) {
        this.context = context.getApplicationContext();
        this.aliasStore = new AppAliasStore(this.context);
    }

    void prewarm() {
        new Thread(new Runnable() {
            @Override public void run() { try { ensureFresh(false); } catch (Exception ignored) {} }
        }, "crew-app-catalog-prewarm").start();
    }

    Resolution resolve(String query) {
        long started = System.currentTimeMillis();
        AppAliasStore.Entry alias = aliasStore.resolve(query);
        if (alias != null) {
            try {
                if (context.getPackageManager().getLaunchIntentForPackage(alias.packageName) != null) {
                    Entry chosen = new Entry(alias.label.isEmpty() ? query : alias.label, alias.packageName);
                    ArrayList<Entry> one = new ArrayList<Entry>();
                    one.add(chosen);
                    return new Resolution(Resolution.FOUND, chosen, one,
                            System.currentTimeMillis() - started);
                }
                aliasStore.delete(query);
            } catch (Exception ignored) {}
        }
        ensureFresh(false);
        Resolution result = resolveCached(query, started);
        if (Resolution.NOT_FOUND.equals(result.status)) {
            ensureFresh(true);
            result = resolveCached(query, started);
        }
        return result;
    }

    ArrayList<Entry> search(String query) {
        ensureFresh(false);
        return matchingEntries(query);
    }

    /** A display-safe snapshot for the explicit App shortcut picker. */
    ArrayList<Entry> listLaunchable() {
        ensureFresh(false);
        synchronized (lock) { return new ArrayList<Entry>(entries); }
    }

    private Resolution resolveCached(String query, long started) {
        String q = normalize(query);
        ArrayList<Entry> matches = matchingEntries(query);
        if (matches.isEmpty()) return new Resolution(Resolution.NOT_FOUND, null, matches, System.currentTimeMillis() - started);
        ArrayList<Entry> exact = new ArrayList<Entry>();
        for (Entry e : matches) {
            if (normalize(e.label).equals(q) || normalize(e.packageName).equals(q)) exact.add(e);
        }
        if (exact.size() == 1) return new Resolution(Resolution.FOUND, exact.get(0), matches, System.currentTimeMillis() - started);
        if (matches.size() == 1) return new Resolution(Resolution.FOUND, matches.get(0), matches, System.currentTimeMillis() - started);
        return new Resolution(Resolution.MULTIPLE, null, matches, System.currentTimeMillis() - started);
    }

    private ArrayList<Entry> matchingEntries(String query) {
        String q = normalize(query);
        ArrayList<Entry> result = new ArrayList<Entry>();
        if (q.isEmpty()) return result;
        String[] tokens = q.split("[^\\p{L}\\p{N}]+");
        synchronized (lock) {
            for (Entry e : entries) {
                String haystack = normalize(e.label + " " + e.packageName);
                boolean all = true;
                for (String token : tokens) {
                    if (token == null || token.isEmpty()) continue;
                    if (!haystack.contains(token)) { all = false; break; }
                }
                if (!all) continue;
                result.add(e);
                if (result.size() >= MAX_MATCHES) break;
            }
        }
        return result;
    }

    private void ensureFresh(boolean force) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (!force && !entries.isEmpty() && now - loadedAtMs >= 0L && now - loadedAtMs < TTL_MS) return;
            ArrayList<Entry> next = new ArrayList<Entry>();
            PackageManager pm = context.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN, null);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> results = pm.queryIntentActivities(launcher, 0);
            for (ResolveInfo info : results) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                String label;
                try { label = String.valueOf(info.loadLabel(pm)); }
                catch (Exception ignored) { label = pkg; }
                next.add(new Entry(label, pkg));
            }
            entries = next;
            loadedAtMs = now;
        }
    }

    private static String normalize(String value) {
        return TextMatch.caseFold(value).trim();
    }
}
