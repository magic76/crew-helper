package com.magic76.crew.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One provider-neutral tool request emitted by a model session. */
public final class ToolCall {
    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name.trim();
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (arguments != null) copy.putAll(arguments);
        this.arguments = Collections.unmodifiableMap(copy);
    }

    public String id() { return id; }
    public String name() { return name; }
    public Map<String, Object> arguments() { return arguments; }
}
