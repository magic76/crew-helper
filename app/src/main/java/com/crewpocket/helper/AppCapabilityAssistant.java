package com.crewpocket.helper;

import java.net.URI;
import java.util.Locale;

/** Pure inference for turning one/two example links into a reusable capability. */
final class AppCapabilityAssistant {
    static final class Suggestion {
        final boolean reusable;
        final String capabilityId;
        final String label;
        final String template;
        final String paramName;
        final String sampleValue;

        Suggestion(
                boolean reusable,
                String capabilityId,
                String label,
                String template,
                String paramName,
                String sampleValue) {
            this.reusable = reusable;
            this.capabilityId = capabilityId == null ? "" : capabilityId;
            this.label = label == null ? "" : label;
            this.template = template == null ? "" : template;
            this.paramName = paramName == null ? "" : paramName;
            this.sampleValue = sampleValue == null ? "" : sampleValue;
        }
    }

    private AppCapabilityAssistant() {}

    static Suggestion fromSingle(String raw) {
        String value = clean(raw);
        AppCapabilityTemplate.validateUri(value);
        String id = inferCapabilityId(value);
        return new Suggestion(
                false,
                id,
                inferLabel(id),
                value,
                "",
                "");
    }

    static Suggestion fromExamples(
            String firstRaw,
            String secondRaw) {
        String first = clean(firstRaw);
        String second = clean(secondRaw);
        AppCapabilityTemplate.validateUri(first);
        AppCapabilityTemplate.validateUri(second);
        if (first.equals(second)) {
            throw new IllegalArgumentException("EXAMPLES_MUST_DIFFER");
        }

        ensureCompatibleSchemes(first, second);

        int prefix = commonPrefix(first, second);
        int suffix = commonSuffix(first, second, prefix);
        suffix = structuralSuffixLength(first, suffix);
        if (prefix <= 0
                || prefix + suffix >= first.length()
                || prefix + suffix >= second.length()) {
            throw new IllegalArgumentException(
                    "CANNOT_INFER_VARIABLE");
        }

        String firstVariable =
                first.substring(prefix, first.length() - suffix);
        String secondVariable =
                second.substring(prefix, second.length() - suffix);
        if (firstVariable.isEmpty()
                || secondVariable.isEmpty()
                || firstVariable.length() > 256
                || secondVariable.length() > 256) {
            throw new IllegalArgumentException(
                    "CANNOT_INFER_VARIABLE");
        }

        // Do not infer across structural URI separators. One variable should
        // represent one path/query value, not half the URI structure.
        if (containsStructure(firstVariable)
                || containsStructure(secondVariable)) {
            throw new IllegalArgumentException(
                    "EXAMPLES_DIFFER_TOO_MUCH");
        }

        String before = first.substring(0, prefix);
        String after = suffix == 0
                ? ""
                : first.substring(first.length() - suffix);
        String param = inferParamName(before);
        String template =
                before + "{" + param + "}" + after;

        String id = inferCapabilityId(template);
        return new Suggestion(
                true,
                id,
                inferLabel(id),
                template,
                param,
                firstVariable);
    }

    static String inferCapabilityId(String uri) {
        String lower = clean(uri).toLowerCase(Locale.ROOT);
        if (containsAny(lower, "product", "goods", "sku")) {
            return "OPEN_PRODUCT";
        }
        if (containsAny(lower, "album")) return "OPEN_ALBUM";
        if (containsAny(lower, "track", "song")) return "OPEN_TRACK";
        if (containsAny(lower, "artist")) return "OPEN_ARTIST";
        if (containsAny(lower, "playlist")) return "OPEN_PLAYLIST";
        if (containsAny(lower, "search", "query", "?q=", "&q=")) {
            return "SEARCH";
        }
        if (containsAny(lower, "place", "location", "maps", "geo:")) {
            return "OPEN_PLACE";
        }
        if (containsAny(lower, "profile", "user", "member")) {
            return "OPEN_PROFILE";
        }
        if (containsAny(lower, "article", "post", "story")) {
            return "OPEN_CONTENT";
        }
        return "OPEN_LINK";
    }

    static String inferLabel(String capabilityId) {
        String id = clean(capabilityId).toUpperCase(Locale.ROOT);
        if ("OPEN_PRODUCT".equals(id)) return "開啟商品";
        if ("OPEN_ALBUM".equals(id)) return "開啟專輯";
        if ("OPEN_TRACK".equals(id)) return "開啟歌曲";
        if ("OPEN_ARTIST".equals(id)) return "開啟藝人";
        if ("OPEN_PLAYLIST".equals(id)) return "開啟播放清單";
        if ("SEARCH".equals(id)) return "搜尋";
        if ("OPEN_PLACE".equals(id)) return "開啟地點";
        if ("OPEN_PROFILE".equals(id)) return "開啟個人頁";
        if ("OPEN_CONTENT".equals(id)) return "開啟內容";
        return "開啟連結";
    }

    private static void ensureCompatibleSchemes(
            String first,
            String second) {
        try {
            URI one = new URI(first);
            URI two = new URI(second);
            String a = one.getScheme();
            String b = two.getScheme();
            if (a == null
                    || b == null
                    || !a.equalsIgnoreCase(b)) {
                throw new IllegalArgumentException(
                        "EXAMPLE_SCHEME_MISMATCH");
            }
            if (one.getHost() != null
                    && two.getHost() != null
                    && !one.getHost().equalsIgnoreCase(two.getHost())) {
                throw new IllegalArgumentException(
                        "EXAMPLE_HOST_MISMATCH");
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            // Custom schemes may not expose host/path cleanly, but scheme
            // validation already happened above.
            int one = first.indexOf(':');
            int two = second.indexOf(':');
            if (one <= 0
                    || two <= 0
                    || !first.substring(0, one)
                            .equalsIgnoreCase(second.substring(0, two))) {
                throw new IllegalArgumentException(
                        "EXAMPLE_SCHEME_MISMATCH");
            }
        }
    }

    private static int commonPrefix(
            String a,
            String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static int structuralSuffixLength(
            String source,
            int suffixLength) {
        if (suffixLength <= 0
                || source == null
                || source.isEmpty()) {
            return 0;
        }
        int start = source.length() - suffixLength;
        if (start < 0 || start >= source.length()) return 0;

        // Preserve only a suffix that begins at a real URI/template boundary.
        // Incidental shared trailing characters inside an id (12345/98765)
        // are not structural and must stay part of the variable.
        char first = source.charAt(start);
        if (first == '/'
                || first == '?'
                || first == '&'
                || first == '#'
                || first == '.') {
            return suffixLength;
        }

        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/'
                    || c == '?'
                    || c == '&'
                    || c == '#'
                    || c == '.') {
                return source.length() - i;
            }
        }
        return 0;
    }

    private static int commonSuffix(
            String a,
            String b,
            int prefix) {
        int max = Math.min(
                a.length() - prefix,
                b.length() - prefix);
        int i = 0;
        while (i < max
                && a.charAt(a.length() - 1 - i)
                        == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }

    private static String inferParamName(String before) {
        String lower = before.toLowerCase(Locale.ROOT);
        int lastEquals = lower.lastIndexOf('=');
        int lastColon = lower.lastIndexOf(':');
        int lastSlash = lower.lastIndexOf('/');
        int semanticColon =
                lastColon > lastSlash
                        ? lastColon
                        : -1;
        int eq = Math.max(
                lastEquals,
                semanticColon);
        if (eq >= 0) {
            int start = eq - 1;
            while (start >= 0) {
                char c = lower.charAt(start);
                if (!(Character.isLetterOrDigit(c) || c == '_')) break;
                start--;
            }
            String key = lower.substring(start + 1, eq);
            if (!key.isEmpty()
                    && !"http".equals(key)
                    && !"https".equals(key)) {
                return sanitizeParam(key);
            }
        }

        if (containsAny(lower, "product/", "goods/", "sku/")) {
            return "productId";
        }
        if (lower.contains("album/")) return "albumId";
        if (containsAny(lower, "track/", "song/")) return "trackId";
        if (lower.contains("artist/")) return "artistId";
        if (lower.contains("playlist/")) return "playlistId";
        if (containsAny(lower, "user/", "profile/")) return "userId";
        return "id";
    }

    private static String sanitizeParam(String value) {
        String clean = value.replaceAll("[^a-zA-Z0-9_]", "");
        if (clean.isEmpty()) return "id";
        if (clean.length() > 24) clean = clean.substring(0, 24);
        if (!Character.isLetter(clean.charAt(0))) return "id";
        return clean;
    }

    private static boolean containsStructure(String value) {
        return value.indexOf('/') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('&') >= 0
                || value.indexOf('#') >= 0;
    }

    private static boolean containsAny(
            String value,
            String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
