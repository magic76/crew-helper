package com.crewpocket.helper;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure placeholder expansion and URI safety for declarative app capabilities. */
final class AppCapabilityTemplate {
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,31})\\}");
    private static final Pattern URI_SCHEME =
            Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]{1,31}):.*$");

    private AppCapabilityTemplate() {}

    static ArrayList<String> requiredParams(String template) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        Matcher matcher = PLACEHOLDER.matcher(safe(template));
        while (matcher.find()) unique.add(matcher.group(1));
        return new ArrayList<String>(unique);
    }

    static String expand(
            String template,
            Map<String, String> params) throws Exception {
        String source = safe(template).trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("EMPTY_CAPABILITY_TEMPLATE");
        }

        ArrayList<String> required = requiredParams(source);
        if (source.matches("^\\{[A-Za-z][A-Za-z0-9_]{0,31}\\}$")
                && required.size() == 1) {
            String raw = param(params, required.get(0));
            validateUri(raw);
            return raw;
        }

        Matcher matcher = PLACEHOLDER.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = param(params, matcher.group(1));
            String encoded = URLEncoder
                    .encode(value, "UTF-8")
                    .replace("+", "%20");
            matcher.appendReplacement(
                    out,
                    Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(out);

        String expanded = out.toString();
        validateUri(expanded);
        return expanded;
    }

    static void validateUri(String uri) {
        String value = safe(uri).trim();
        if (value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException("BAD_CAPABILITY_URI");
        }
        Matcher matcher = URI_SCHEME.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("CAPABILITY_URI_SCHEME_REQUIRED");
        }
        String scheme = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        if ("javascript".equals(scheme)
                || "file".equals(scheme)
                || "content".equals(scheme)
                || "intent".equals(scheme)
                || "data".equals(scheme)) {
            throw new IllegalArgumentException(
                    "CAPABILITY_URI_SCHEME_BLOCKED");
        }
    }

    static boolean validCapabilityId(String id) {
        return safe(id).matches("[A-Z][A-Z0-9_]{1,47}");
    }

    private static String param(
            Map<String, String> params,
            String name) {
        String value = params == null ? null : params.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "CAPABILITY_PARAM_REQUIRED:" + name);
        }
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
