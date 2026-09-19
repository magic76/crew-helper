package com.crewpocket.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Collects a deliberately small, privacy-bounded snapshot for Gemini Live. */
final class SessionContextSnapshot {
    private static final long MAX_LOCATION_AGE_MS = 30L * 60L * 1000L;

    private SessionContextSnapshot() {}

    static String systemInstruction(
            Context context,
            String foregroundPackage) {
        if (context == null) return "";

        TimeZone zone = TimeZone.getDefault();
        Locale locale = Locale.getDefault();
        Date now = new Date();

        SimpleDateFormat local =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        local.setTimeZone(zone);

        String offset = formatOffset(zone.getOffset(now.getTime()));
        String localeTag;
        try {
            localeTag = locale.toLanguageTag();
        } catch (Throwable ignored) {
            localeTag = locale.toString();
        }

        String location = approximateLocation(context);
        String externalPackage = foregroundPackage == null
                ? ""
                : foregroundPackage.trim();
        if (externalPackage.equals(context.getPackageName())) {
            externalPackage = "";
        }

        return SessionContextPrompt.build(
                local.format(now),
                zone.getID(),
                offset,
                localeTag,
                location,
                externalPackage);
    }

    private static String approximateLocation(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }

        try {
            LocationManager manager =
                    (LocationManager) context.getSystemService(
                            Context.LOCATION_SERVICE);
            if (manager == null) return "";

            Location newest = null;
            List<String> providers = manager.getProviders(true);
            if (providers != null) {
                for (String provider : providers) {
                    try {
                        Location candidate =
                                manager.getLastKnownLocation(provider);
                        if (candidate == null) continue;
                        if (newest == null
                                || candidate.getTime() > newest.getTime()) {
                            newest = candidate;
                        }
                    } catch (SecurityException ignored) {}
                }
            }

            if (newest == null) return "";
            long age = Math.max(
                    0L,
                    System.currentTimeMillis() - newest.getTime());
            if (age > MAX_LOCATION_AGE_MS) return "";

            // ~1 km granularity is enough for assistant context and avoids
            // unnecessarily exposing precise coordinates to the model.
            double lat = Math.round(newest.getLatitude() * 100.0) / 100.0;
            double lon = Math.round(newest.getLongitude() * 100.0) / 100.0;
            return String.format(
                    Locale.US,
                    "%.2f, %.2f (coarse Android location)",
                    lat,
                    lon);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String formatOffset(int offsetMillis) {
        int totalMinutes = offsetMillis / 60000;
        char sign = totalMinutes >= 0 ? '+' : '-';
        int absolute = Math.abs(totalMinutes);
        return String.format(
                Locale.US,
                "%c%02d:%02d",
                sign,
                absolute / 60,
                absolute % 60);
    }
}
