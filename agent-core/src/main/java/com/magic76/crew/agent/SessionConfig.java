package com.magic76.crew.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable provider-neutral session configuration. */
public final class SessionConfig {
    private final String agentId;
    private final String systemPrompt;
    private final List<ToolSpec> tools;

    public SessionConfig(String agentId, String systemPrompt, List<ToolSpec> tools) {
        this.agentId = agentId == null ? "" : agentId;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        List<ToolSpec> copy = new ArrayList<ToolSpec>();
        if (tools != null) copy.addAll(tools);
        this.tools = Collections.unmodifiableList(copy);
    }

    public static SessionConfig from(AgentSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec is null");
        return new SessionConfig(spec.id(), spec.systemPrompt(), spec.tools());
    }

    public String agentId() { return agentId; }
    public String systemPrompt() { return systemPrompt; }
    public List<ToolSpec> tools() { return tools; }
}
