package com.crewpocket.helper;

import org.json.JSONObject;

/** Lightweight condition for short waits between UI mutations. */
final class PendingCondition {
    enum Type {
        NONE,
        SCREEN_CHANGE,
        ELEMENT_APPEARS,
        ELEMENT_DISAPPEARS
    }

    final Type type;
    final String elementId;
    final String baselineFingerprint;
    final long timeoutMs;
    final long startedAt;

    PendingCondition(Type type, String elementId, String baselineFingerprint, long timeoutMs) {
        this.type = type == null ? Type.NONE : type;
        this.elementId = elementId == null ? "" : elementId;
        this.baselineFingerprint = baselineFingerprint == null ? "" : baselineFingerprint;
        this.timeoutMs = Math.max(250L, Math.min(15000L, timeoutMs));
        this.startedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
        return System.currentTimeMillis() - startedAt >= timeoutMs;
    }

    JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("type", type.name())
               .put("elementId", elementId)
               .put("baselineFingerprint", baselineFingerprint)
               .put("timeoutMs", timeoutMs)
               .put("startedAt", startedAt);
        } catch (Exception ignored) {}
        return out;
    }
}
