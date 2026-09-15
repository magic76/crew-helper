package com.crewpocket.helper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stable model-facing concept shared by Live and an app Runtime adapter.
 *
 * The concept id describes WHAT the user means. Android selectors, coordinates,
 * Accessibility nodes and learned UI mappings remain Runtime-only HOW details.
 */
final class AppSemanticConcept {
    final String id;
    final String action;
    final String labelZh;
    final String labelEn;
    final List<String> aliases;

    AppSemanticConcept(
            String id,
            String action,
            String labelZh,
            String labelEn,
            String... aliases) {
        this.id = clean(id);
        this.action = clean(action);
        this.labelZh = clean(labelZh);
        this.labelEn = clean(labelEn);
        this.aliases = aliases == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(Arrays.asList(aliases));
    }

    String modelLine() {
        StringBuilder out = new StringBuilder();
        out.append(id).append(" = ");
        boolean first = true;
        for (String alias : aliases) {
            String clean = clean(alias);
            if (clean.isEmpty()) continue;
            if (!first) out.append(" / ");
            out.append(clean);
            first = false;
        }
        if (first) out.append(labelEn.isEmpty() ? labelZh : labelEn);
        out.append("; action=").append(action.isEmpty() ? "TAP" : action);
        return out.toString();
    }

    String displayLine(boolean chinese) {
        String label = chinese ? labelZh : labelEn;
        if (label.isEmpty()) label = chinese ? labelEn : labelZh;
        return label + "  →  " + id;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
