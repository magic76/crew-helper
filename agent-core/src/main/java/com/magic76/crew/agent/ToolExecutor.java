package com.magic76.crew.agent;

/** Async-friendly tool boundary. Android actions can complete later without blocking Live callbacks. */
public interface ToolExecutor {
    interface Completion {
        void complete(ToolResult result);
    }

    void execute(ToolCall call, Completion completion);
}
