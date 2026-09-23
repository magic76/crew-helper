package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

/** User-controlled per-app trust for low-risk autonomous UI execution. */
final class AppAutonomyStore {
    private static final String PREFS = "crew_app_autonomy";
    private static final String PREFIX = "trusted:";

    private final SharedPreferences prefs;

    AppAutonomyStore(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        prefs = app == null
                ? null
                : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isTrusted(String packageName) {
        String pkg = cleanPackage(packageName);
        if (pkg.isEmpty()) return false;
        if (prefs != null && prefs.contains(PREFIX + pkg)) {
            return prefs.getBoolean(PREFIX + pkg, false);
        }
        // Built-in deterministic adapters and explicitly approved low-risk
        // media apps are safe defaults. Users can still turn them off from
        // App Playbooks because an explicit pref always wins above.
        return AppRuntimeRegistry.forPackage(pkg) != null
                || MediaPlaybackCompletionPolicy
                        .isDefaultTrustedPackage(pkg);
    }

    void setTrusted(String packageName, boolean trusted) {
        String pkg = cleanPackage(packageName);
        if (prefs == null || pkg.isEmpty()) return;
        prefs.edit().putBoolean(PREFIX + pkg, trusted).apply();
    }

    ArrayList<String> trustedPackages() {
        HashSet<String> out = new HashSet<String>();
        for (String pkg : AppRuntimeRegistry.builtInPackages()) {
            if (isTrusted(pkg)) out.add(pkg);
        }
        if (isTrusted(MediaPlaybackCompletionPolicy.APPLE_MUSIC_PACKAGE)) {
            out.add(MediaPlaybackCompletionPolicy.APPLE_MUSIC_PACKAGE);
        }
        if (prefs != null) {
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.startsWith(PREFIX)) continue;
                if (!(entry.getValue() instanceof Boolean)
                        || !((Boolean) entry.getValue()).booleanValue()) {
                    continue;
                }
                String pkg = cleanPackage(key.substring(PREFIX.length()));
                if (!pkg.isEmpty()) out.add(pkg);
            }
        }
        ArrayList<String> list = new ArrayList<String>(out);
        Collections.sort(list);
        return list;
    }

    int trustedCount() {
        return trustedPackages().size();
    }

    private static String cleanPackage(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return clean.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
