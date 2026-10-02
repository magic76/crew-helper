package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;

/** Pure post-model validation for documentation-derived capability candidates. */
final class AppCapabilityDocumentPolicy {
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_EVIDENCE_CHARS = 220;

    private AppCapabilityDocumentPolicy() {}

    static JSONArray validateCandidates(
            JSONArray source,
            String documentText) {
        JSONArray out = new JSONArray();
        if (source == null) return out;

        String doc =
                documentText == null
                        ? ""
                        : documentText.toLowerCase(Locale.ROOT);
        java.util.HashSet<String> ids =
                new java.util.HashSet<String>();

        for (int i = 0;
                i < source.length()
                        && out.length() < MAX_CANDIDATES;
                i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;

            String id = cleanId(
                    item.optString("id", ""));
            String label = cleanLabel(
                    item.optString("label", ""));
            String template =
                    item.optString("uriTemplate", "").trim();
            String example =
                    item.optString("exampleUri", "").trim();
            String action =
                    item.optString(
                            "intentAction",
                            "android.intent.action.VIEW").trim();
            String evidence =
                    cleanEvidence(
                            item.optString("evidence", ""));
            double confidence =
                    item.optDouble("confidence", 0.0);

            if (!AppCapabilityTemplate.validCapabilityId(id)
                    || !ids.add(id)
                    || label.isEmpty()
                    || template.isEmpty()
                    || example.isEmpty()
                    || confidence < 0.55
                    || !isAllowedAction(action)) {
                continue;
            }

            try {
                AppCapabilityTemplate.validateUri(example);
                HashMap<String, String> params =
                        sampleParams(template, example);
                String expanded =
                        AppCapabilityTemplate.expand(
                                template,
                                params);
                AppCapabilityTemplate.validateUri(expanded);
            } catch (Exception ignored) {
                continue;
            }

            if (!documentSupportsCandidate(
                    doc,
                    template,
                    example)) {
                continue;
            }

            try {
                JSONObject clean = new JSONObject()
                        .put("id", id)
                        .put("label", label)
                        .put("kind", "URI")
                        .put("uriTemplate", template)
                        .put("exampleUri", example)
                        .put("intentAction", action)
                        .put("confidence",
                                Math.max(
                                        0.0,
                                        Math.min(
                                                1.0,
                                                confidence)))
                        .put("evidence", evidence);
                out.put(clean);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static HashMap<String, String> sampleParams(
            String template,
            String example) {
        HashMap<String, String> out =
                new HashMap<String, String>();
        java.util.ArrayList<String> names =
                AppCapabilityTemplate.requiredParams(
                        template);
        if (names.isEmpty()) return out;

        if (names.size() == 1) {
            String name = names.get(0);
            String token = "{" + name + "}";
            int tokenIndex = template.indexOf(token);
            if (tokenIndex >= 0) {
                String before =
                        template.substring(0, tokenIndex);
                String after =
                        template.substring(
                                tokenIndex + token.length());
                if (example.startsWith(before)
                        && example.endsWith(after)
                        && example.length()
                                >= before.length()
                                        + after.length()) {
                    String value =
                            example.substring(
                                    before.length(),
                                    example.length()
                                            - after.length());
                    if (!value.isEmpty()) {
                        try {
                            value =
                                    java.net.URLDecoder.decode(
                                            value,
                                            "UTF-8");
                        } catch (Exception ignored) {}
                        out.put(name, value);
                        return out;
                    }
                }
            }
        }

        for (String name : names) {
            out.put(name, "sample");
        }
        return out;
    }

    private static boolean documentSupportsCandidate(
            String lowerDocument,
            String template,
            String example) {
        String doc =
                lowerDocument == null
                        ? ""
                        : lowerDocument;
        String ex =
                safe(example)
                        .toLowerCase(Locale.ROOT);
        if (!ex.isEmpty() && doc.contains(ex)) {
            return true;
        }

        String lowerTemplate =
                safe(template)
                        .toLowerCase(Locale.ROOT);
        int placeholder =
                lowerTemplate.indexOf('{');
        String prefix =
                placeholder >= 0
                        ? lowerTemplate.substring(
                                0,
                                placeholder)
                        : lowerTemplate;
        prefix = prefix.trim();
        if (prefix.length() > 14
                && doc.contains(prefix)) {
            return true;
        }

        try {
            java.net.URI uri =
                    new java.net.URI(example);
            String host = uri.getHost();
            if (host != null
                    && host.length() > 4
                    && doc.contains(
                            host.toLowerCase(
                                    Locale.ROOT))) {
                String path =
                        uri.getPath();
                return path == null
                        || path.isEmpty()
                        || doc.contains(
                                path.toLowerCase(
                                        Locale.ROOT));
            }
        } catch (Exception ignored) {}

        return false;
    }

    private static boolean isAllowedAction(
            String action) {
        return "android.intent.action.VIEW".equals(action)
                || "android.intent.action.SENDTO".equals(action)
                || "android.intent.action.DIAL".equals(action);
    }

    private static String cleanId(
            String value) {
        return safe(value)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
    }

    private static String cleanLabel(
            String value) {
        String clean =
                safe(value)
                        .replaceAll("\s+", " ")
                        .trim();
        return clean.length() > 80
                ? clean.substring(0, 80)
                : clean;
    }

    private static String cleanEvidence(
            String value) {
        String clean =
                safe(value)
                        .replaceAll("\s+", " ")
                        .trim();
        return clean.length() > MAX_EVIDENCE_CHARS
                ? clean.substring(
                        0,
                        MAX_EVIDENCE_CHARS)
                : clean;
    }

    private static String safe(
            String value) {
        return value == null ? "" : value;
    }
}
