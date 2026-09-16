package com.magic76.crew.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Product-owned tool implementations exposed through the common harness. */
public final class ToolRegistry {
    private final Map<String, ToolExecutor> executors = new LinkedHashMap<String, ToolExecutor>();

    public synchronized ToolRegistry register(String name, ToolExecutor executor) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("tool name is empty");
        if (executor == null) throw new IllegalArgumentException("executor is null");
        executors.put(name.trim(), executor);
        return this;
    }

    public synchronized boolean contains(String name) {
        return name != null && executors.containsKey(name);
    }

    public void execute(final ToolCall call, final ToolExecutor.Completion completion) {
        if (completion == null) throw new IllegalArgumentException("completion is null");
        final ToolExecutor executor;
        synchronized (this) {
            executor = call == null ? null : executors.get(call.name());
        }
        if (call == null || executor == null) {
            completion.complete(ToolResult.failure(
                    call == null ? "" : call.id(),
                    "TOOL_NOT_REGISTERED",
                    call == null ? "Missing tool call" : "Tool is not registered: " + call.name()));
            return;
        }

        final AtomicBoolean completed = new AtomicBoolean(false);
        try {
            executor.execute(call, new ToolExecutor.Completion() {
                @Override public void complete(ToolResult result) {
                    if (!completed.compareAndSet(false, true)) return;
                    completion.complete(result == null
                            ? ToolResult.failure(call.id(), "NULL_TOOL_RESULT", "Tool returned no result")
                            : result);
                }
            });
        } catch (Throwable error) {
            if (completed.compareAndSet(false, true)) {
                completion.complete(ToolResult.failure(
                        call.id(),
                        "TOOL_EXECUTION_EXCEPTION",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }
    }
}
