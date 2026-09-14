package com.crewpocket.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;

/** Registry for optional app-specific deterministic Runtime adapters. */
final class AppRuntimeRegistry {
    private static final AppRuntimeAdapter[] BUILT_INS = new AppRuntimeAdapter[]{
            GoogleMapsRuntimeAdapter.INSTANCE
    };

    private AppRuntimeRegistry() {}

    static AppRuntimeAdapter forPackage(String packageName) {
        String pkg = packageName == null ? "" : packageName.trim();
        if (pkg.isEmpty()) return null;
        for (AppRuntimeAdapter adapter : BUILT_INS) {
            if (adapter != null && adapter.supports(pkg)) return adapter;
        }
        return null;
    }

    static ArrayList<String> builtInPackages() {
        ArrayList<String> out = new ArrayList<String>();
        out.add(GoogleMapsRuntimeAdapter.PACKAGE_NAME);
        return out;
    }

    static String displayName(Context context, String packageName) {
        AppRuntimeAdapter adapter = forPackage(packageName);
        if (adapter != null) return adapter.displayName(context, packageName);
        if (context != null && packageName != null && !packageName.trim().isEmpty()) {
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                CharSequence label = pm.getApplicationLabel(info);
                if (label != null && !label.toString().trim().isEmpty()) {
                    return label.toString().trim();
                }
            } catch (Exception ignored) {}
        }
        return packageName == null ? "" : packageName.trim();
    }
}
