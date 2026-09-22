package com.crewpocket.helper;

/**
 * Pure degradation policy after UiLocatorV2.
 *
 * Important invariant: ambiguity or medium-confidence evidence must never
 * silently degrade to coordinates. Coordinates are allowed only when the caller
 * supplied them explicitly or Runtime derived them from a deterministic
 * label/id node match.
 */
final class LocatorFallbackPolicy {
    enum Next {
        EXECUTE_SEMANTIC,
        ASK_USER,
        REOBSERVE,
        TRY_LABEL,
        USE_EXPLICIT_COORDINATE,
        STOP
    }

    private LocatorFallbackPolicy() {}

    static Next afterSemantic(
            String decision,
            boolean hasLabelOrId,
            boolean hasExplicitCoordinate) {
        String d = decision == null ? "" : decision.trim().toUpperCase();
        if ("AUTO".equals(d)) return Next.EXECUTE_SEMANTIC;
        if ("AMBIGUOUS".equals(d)) return Next.ASK_USER;
        if ("RETRY_OBSERVE".equals(d)) return Next.REOBSERVE;
        if ("BLOCKED".equals(d)) return Next.STOP;

        if (hasLabelOrId) return Next.TRY_LABEL;
        if (hasExplicitCoordinate) return Next.USE_EXPLICIT_COORDINATE;
        return Next.STOP;
    }

    static Next afterLabel(
            boolean labelMatched,
            boolean deterministicNodeBounds,
            boolean hasExplicitCoordinate) {
        if (labelMatched) return Next.EXECUTE_SEMANTIC;
        if (deterministicNodeBounds) return Next.EXECUTE_SEMANTIC;
        if (hasExplicitCoordinate) return Next.USE_EXPLICIT_COORDINATE;
        return Next.STOP;
    }
}
