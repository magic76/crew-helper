package com.crewpocket.helper;

/** Pure formatter for the minimal Live session device context. */
final class SessionContextPrompt {
    private SessionContextPrompt() {}

    static String build(
            String localDateTime,
            String timeZoneId,
            String utcOffset,
            String localeTag,
            String approximateLocation,
            String foregroundPackage) {
        StringBuilder out = new StringBuilder();
        out.append("【DEVICE CONTEXT】\n");
        out.append("Captured local datetime: ")
                .append(safe(localDateTime))
                .append("\n");
        out.append("Timezone: ")
                .append(safe(timeZoneId));
        if (!blank(utcOffset)) {
            out.append(" (UTC").append(utcOffset).append(")");
        }
        out.append("\n");
        if (!blank(localeTag)) {
            out.append("Locale: ").append(localeTag.trim()).append("\n");
        }
        if (!blank(approximateLocation)) {
            out.append("Approximate location: ")
                    .append(approximateLocation.trim())
                    .append("\n");
        }
        if (!blank(foregroundPackage)) {
            out.append("Foreground app package: ")
                    .append(foregroundPackage.trim())
                    .append("\n");
        }
        out.append(
                "Use this Android-provided local datetime/timezone as authoritative for date/time questions; do not assume UTC. "
                        + "The captured clock is a session-start snapshot, so do not pretend it is second-perfect later in a long call. "
                        + "Use location only when it is present above; never invent a missing location.");
        return out.toString();
    }

    private static String safe(String value) {
        return blank(value) ? "unknown" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
