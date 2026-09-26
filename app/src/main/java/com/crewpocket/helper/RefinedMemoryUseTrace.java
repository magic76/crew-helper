package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * In-memory causal receipt for Refined Memory.
 *
 * USED ids were injected into the model for this task.
 * LEARNED ids received a new independent-success vote from this task.
 * No user text, query, screen content, or selector data is retained.
 */
final class RefinedMemoryUseTrace {
    static final long CORRECTION_WINDOW_MS = 45_000L;

    static final class Snapshot {
        final String taskId;
        final long generation;
        final List<String> usedMemoryIds;
        final List<String> learnedMemoryIds;
        final int injectionCount;

        Snapshot(
                String taskId,
                long generation,
                List<String> usedMemoryIds,
                List<String> learnedMemoryIds,
                int injectionCount) {
            this.taskId = clean(taskId);
            this.generation = generation;
            this.usedMemoryIds = copy(usedMemoryIds);
            this.learnedMemoryIds = copy(learnedMemoryIds);
            this.injectionCount = Math.max(0, injectionCount);
        }

        boolean isEmpty() {
            return usedMemoryIds.isEmpty()
                    && learnedMemoryIds.isEmpty();
        }
    }

    private String taskId = "";
    private long generation = -1L;
    private long lastEvidenceAt;
    private int injectionCount;
    private final LinkedHashSet<String> usedMemoryIds =
            new LinkedHashSet<String>();
    private final LinkedHashSet<String> learnedMemoryIds =
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
                    && !usedMemoryIds.contains(cleanId)) {
                return false;
            }
        }
        return true;
    }

    // Compatibility name retained for the model-injection call site.
    synchronized void record(
            String nextTaskId,
            long nextGeneration,
            Collection<String> ids,
            long now) {
        recordUsed(
                nextTaskId,
                nextGeneration,
                ids,
                now);
    }

    synchronized void recordUsed(
            String nextTaskId,
            long nextGeneration,
            Collection<String> ids,
            long now) {
        if (!prepareTask(
                nextTaskId,
                nextGeneration,
                ids)) {
            return;
        }

        for (String id : ids) {
            String cleanId = clean(id);
            if (!cleanId.isEmpty()) {
                usedMemoryIds.add(cleanId);
            }
        }
        if (!usedMemoryIds.isEmpty()) {
            injectionCount++;
            lastEvidenceAt = Math.max(0L, now);
        }
    }

    synchronized void recordLearned(
            String nextTaskId,
            long nextGeneration,
            String memoryId,
            long now) {
        String cleanId = clean(memoryId);
        if (cleanId.isEmpty()) return;
        ArrayList<String> single = new ArrayList<String>();
        single.add(cleanId);
        if (!prepareTask(
                nextTaskId,
                nextGeneration,
                single)) {
            return;
        }
        learnedMemoryIds.add(cleanId);
        lastEvidenceAt = Math.max(0L, now);
    }

    synchronized Snapshot consumeRecentCorrection(long now) {
        if (taskId.isEmpty()
                || (usedMemoryIds.isEmpty()
                    && learnedMemoryIds.isEmpty())
                || lastEvidenceAt <= 0L
                || now - lastEvidenceAt < 0L
                || now - lastEvidenceAt
                        > CORRECTION_WINDOW_MS) {
            clear();
            return emptySnapshot();
        }

        Snapshot out = snapshotLocked();
        clear();
        return out;
    }

    synchronized Snapshot snapshot() {
        return snapshotLocked();
    }

    synchronized void clear() {
        taskId = "";
        generation = -1L;
        lastEvidenceAt = 0L;
        injectionCount = 0;
        usedMemoryIds.clear();
        learnedMemoryIds.clear();
    }

    private boolean prepareTask(
            String nextTaskId,
            long nextGeneration,
            Collection<String> ids) {
        String cleanTaskId = clean(nextTaskId);
        if (cleanTaskId.isEmpty()
                || ids == null
                || ids.isEmpty()) {
            return false;
        }

        if (!cleanTaskId.equals(taskId)) {
            taskId = cleanTaskId;
            generation = nextGeneration;
            injectionCount = 0;
            usedMemoryIds.clear();
            learnedMemoryIds.clear();
        }
        return true;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                taskId,
                generation,
                new ArrayList<String>(usedMemoryIds),
                new ArrayList<String>(learnedMemoryIds),
                injectionCount);
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(
                "",
                -1L,
                null,
                null,
                0);
    }

    private static List<String> copy(List<String> source) {
        return source == null
                ? new ArrayList<String>()
                : new ArrayList<String>(source);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
