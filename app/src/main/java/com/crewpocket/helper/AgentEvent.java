package com.crewpocket.helper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable append-only ledger record. Payloads must stay compact and non-sensitive. */
final class AgentEvent {
    enum Type {
        USER_INTENT_ACCEPTED,
        TOOL_QUEUED,
        ACTION_PREFLIGHT_ALLOWED,
        ACTION_STARTED,
        ACTION_EXECUTED,
        SCREEN_OBSERVED,
        LOCATOR_RESOLVED,
        LOCATOR_REJECTED,
        ACTION_VERIFICATION_PENDING,
        ACTION_VERIFIED,
        ACTION_COMMITTED,
        ACTION_FAILED,
        TOOL_RESULT_SENT,
        MODEL_WAITING,
        USER_WAITING,
        OBSERVATION_REQUIRED,
        DUPLICATE_IGNORED,
        STALE_ACTION_REJECTED,
        USER_INTERRUPTED,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_CANCELLED
    }

    final long sequence;
    final long timestampMs;
    final Type type;
    final long generation;
    final String goalId;
    final String taskId;
    final String actionId;
    final String toolCallId;
    final Map<String, String> attributes;

    private AgentEvent(long sequence,
                       long timestampMs,
                       Type type,
                       long generation,
                       String goalId,
                       String taskId,
                       String actionId,
                       String toolCallId,
                       Map<String, String> attributes) {
        this.sequence = sequence;
        this.timestampMs = timestampMs;
        this.type = type;
        this.generation = generation;
        this.goalId = safe(goalId);
        this.taskId = safe(taskId);
        this.actionId = safe(actionId);
        this.toolCallId = safe(toolCallId);
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    static Builder builder(Type type) { return new Builder(type); }

    AgentEvent withSequence(long nextSequence) {
        return new AgentEvent(nextSequence, timestampMs, type, generation, goalId, taskId,
                actionId, toolCallId, attributes);
    }

    String attr(String key) {
        String value = attributes.get(key);
        return value == null ? "" : value;
    }

    boolean attrBoolean(String key) {
        return "true".equalsIgnoreCase(attr(key));
    }

    static final class Builder {
        private final Type type;
        private long timestampMs = System.currentTimeMillis();
        private long generation = -1L;
        private String goalId = "";
        private String taskId = "";
        private String actionId = "";
        private String toolCallId = "";
        private final LinkedHashMap<String, String> attributes = new LinkedHashMap<String, String>();

        Builder(Type type) {
            if (type == null) throw new IllegalArgumentException("event type required");
            this.type = type;
        }

        Builder at(long value) { timestampMs = value; return this; }
        Builder generation(long value) { generation = value; return this; }
        Builder goalId(String value) { goalId = safe(value); return this; }
        Builder taskId(String value) { taskId = safe(value); return this; }
        Builder actionId(String value) { actionId = safe(value); return this; }
        Builder toolCallId(String value) { toolCallId = safe(value); return this; }

        Builder attr(String key, String value) {
            if (key != null && !key.trim().isEmpty() && value != null) {
                String compact = value.trim();
                if (compact.length() > 160) compact = compact.substring(0, 160);
                attributes.put(key.trim(), compact);
            }
            return this;
        }

        Builder attr(String key, boolean value) { return attr(key, String.valueOf(value)); }
        Builder attr(String key, long value) { return attr(key, String.valueOf(value)); }
        Builder attr(String key, double value) { return attr(key, String.format(java.util.Locale.ROOT, "%.3f", value)); }

        AgentEvent build() {
            return new AgentEvent(0L, timestampMs, type, generation, goalId, taskId,
                    actionId, toolCallId, attributes);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    @Override public String toString() {
        return "#" + sequence + " " + type
                + " gen=" + generation
                + (goalId.isEmpty() ? "" : " goal=" + goalId)
                + (taskId.isEmpty() ? "" : " task=" + taskId)
                + (actionId.isEmpty() ? "" : " action=" + actionId)
                + (toolCallId.isEmpty() ? "" : " tool=" + toolCallId)
                + (attributes.isEmpty() ? "" : " " + attributes);
    }
}

