package com.crewpocket.helper;

import java.util.Locale;

/** Semantic description of WHAT the user/model wants to interact with. */
final class UiTargetSpec {
    enum ActionKind { TAP, TYPE, GENERIC }

    final ActionKind actionKind;
    final String elementId;
    final String label;
    final String viewId;
    final String semanticHint;
    final String role;
    final String parentRole;
    final String anchorLabel;
    final String anchorViewId;
    final String relation;
    final String region;

    private UiTargetSpec(Builder b) {
        actionKind = b.actionKind == null ? ActionKind.GENERIC : b.actionKind;
        elementId = clean(b.elementId);
        label = clean(b.label);
        viewId = clean(b.viewId);
        semanticHint = clean(b.semanticHint);
        role = clean(b.role);
        parentRole = clean(b.parentRole);
        anchorLabel = clean(b.anchorLabel);
        anchorViewId = clean(b.anchorViewId);
        relation = normalizeEnumish(b.relation);
        region = normalizeEnumish(b.region);
    }

    static Builder builder() { return new Builder(); }

    static UiTargetSpec forTap(String label, String viewId) {
        return builder().actionKind(ActionKind.TAP).label(label).viewId(viewId).build();
    }

    boolean hasAnchor() {
        return !anchorLabel.isEmpty() || !anchorViewId.isEmpty();
    }

    boolean isEmpty() {
        return elementId.isEmpty() && label.isEmpty() && viewId.isEmpty()
                && semanticHint.isEmpty() && role.isEmpty();
    }

    static final class Builder {
        private ActionKind actionKind = ActionKind.GENERIC;
        private String elementId = "";
        private String label = "";
        private String viewId = "";
        private String semanticHint = "";
        private String role = "";
        private String parentRole = "";
        private String anchorLabel = "";
        private String anchorViewId = "";
        private String relation = "";
        private String region = "";

        Builder actionKind(ActionKind value) { actionKind = value; return this; }
        Builder elementId(String value) { elementId = value; return this; }
        Builder label(String value) { label = value; return this; }
        Builder viewId(String value) { viewId = value; return this; }
        Builder semanticHint(String value) { semanticHint = value; return this; }
        Builder role(String value) { role = value; return this; }
        Builder parentRole(String value) { parentRole = value; return this; }
        Builder anchorLabel(String value) { anchorLabel = value; return this; }
        Builder anchorViewId(String value) { anchorViewId = value; return this; }
        Builder relation(String value) { relation = value; return this; }
        Builder region(String value) { region = value; return this; }

        UiTargetSpec build() { return new UiTargetSpec(this); }
    }

    static String normalizeText(String value) {
        if (value == null) return "";
        String s = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeEnumish(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}

