package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * In-memory causal trace connecting model-injected Refined Memory to the task
 * that consumed it. No user text or screen content is retained here.
 */
final class RefinedMemoryUseTrace {
    static final long CORRECTION_WINDOW_MS = 45_000L;

    static final class Snapshot {
        final String taskId;
        final long generation;
        final List<String> memoryIds;
        final int injectionCount;

        Snapshot(
                String taskId,
                long generation,
                List<String> memoryIds,
                int injectionCount) {
            this.taskId = clean(taskId);
            this.generation = generation;
            this.memoryIds = memoryIds == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(memoryIds);
            this.injectionCount = Math.max(0, injectionCount);
        }

        boolean isEmpty() {
            return memoryIds.isEmpty();
        }
    }

    private String taskId = "";
    private long generation = -1L;
    private long lastInjectedAt;
    private int injectionCount;
    private final LinkedHashSet<String> memoryIds =
            new LinkedHashSet<String>();

    synchronized boolean alreadyInjected(
            String nextTaskId,
            Collection<String> ids) {
        if (!clean(nextTaskId).equals(taskId)
                || ids == null
                || ids.isEmpty()) {
            return false;
        }
        for (String id : ids) {
            String cleanId = clean(id);
            if (!cleanId.isEmpty()
                    && !memoryIds.contains(cleanId)) {
                return false;
            }
        }
        return true;
    }

    synchronized void record(
            String nextTaskId,
            long nextGeneration,
            Collection<String> ids,
            long now) {
        String cleanTaskId = clean(nextTaskId);
        if (cleanTaskId.isEmpty() || ids == null || ids.isEmpty()) {
            return;
        }

        if (!cleanTaskId.equals(taskId)) {
            taskId = cleanTaskId;
            generation = nextGeneration;
            injectionCount = 0;
            memoryIds.clear();
        }

        for (String id : ids) {
            String cleanId = clean(id);
            if (!cleanId.isEmpty()) memoryIds.add(cleanId);
        }
        if (!memoryIds.isEmpty()) {
            injectionCount++;
            lastInjectedAt = Math.max(0L, now);
        }
    }

    synchronized Snapshot consumeRecentCorrection(long now) {
        if (taskId.isEmpty()
                || memoryIds.isEmpty()
                || lastInjectedAt <= 0L
                || now - lastInjectedAt < 0L
                || now - lastInjectedAt > CORRECTION_WINDOW_MS) {
            clear();
            return new Snapshot("", -1L, null, 0);
        }

        Snapshot out = new Snapshot(
                taskId,
                generation,
                new ArrayList<String>(memoryIds),
                injectionCount);
        clear();
        return out;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                taskId,
                generation,
                new ArrayList<String>(memoryIds),
                injectionCount);
    }

    synchronized void clear() {
        taskId = "";
        generation = -1L;
        lastInjectedAt = 0L;
        injectionCount = 0;
        memoryIds.clear();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
