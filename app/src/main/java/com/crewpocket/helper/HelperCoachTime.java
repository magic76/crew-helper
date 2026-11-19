package com.crewpocket.helper;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class HelperCoachTime {
    private HelperCoachTime() {}

    static boolean withinClockSkew(long value, long now, long maxSkewMs) {
        if (maxSkewMs < 0L) return false;
        long earliest = now < Long.MIN_VALUE + maxSkewMs
                ? Long.MIN_VALUE : now - maxSkewMs;
        long latest = now > Long.MAX_VALUE - maxSkewMs
                ? Long.MAX_VALUE : now + maxSkewMs;
        return value >= earliest && value <= latest;
    }

    static String formatIsoUtc(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(Math.max(0L, epochMs)));
    }

    static long parseMillis(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return -1L;
        try {
            long numeric = Long.parseLong(clean);
            return numeric > 0L && numeric < 100_000_000_000L
                    ? numeric * 1000L : numeric;
        } catch (Exception ignored) {}

        String normalized = clean;
        if (normalized.matches(".*[+-]\\d{2}:\\d{2}$")) {
            normalized = normalized.substring(0, normalized.length() - 3)
                    + normalized.substring(normalized.length() - 2);
        }
        String[] patterns = new String[] {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                ParsePosition position = new ParsePosition(0);
                Date parsed = format.parse(normalized, position);
                if (parsed != null && position.getIndex() == normalized.length()) {
                    return parsed.getTime();
                }
            } catch (Exception ignored) {}
        }
        return -1L;
    }
}
