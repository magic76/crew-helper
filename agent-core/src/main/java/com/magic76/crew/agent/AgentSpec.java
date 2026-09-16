package com.magic76.crew.agent;

import java.util.Collections;
import java.util.List;

/** Product-specific identity/prompt/tool exposure. Domain state stays outside agent-core. */
public interface AgentSpec {
    String id();
    String systemPrompt();
    List<ToolSpec> tools();

    default boolean allowTool(String toolName) {
        if (toolName == null) return false;
        List<ToolSpec> declared = tools();
        if (declared == null) declared = Collections.emptyList();
        for (ToolSpec spec : declared) {
            if (spec != null && toolName.equals(spec.name())) return true;
        }
        return false;
    }
}
