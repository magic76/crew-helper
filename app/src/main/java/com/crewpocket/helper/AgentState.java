package com.crewpocket.helper;

/**
 * High-level runtime state for the phone agent.
 *
 * v1 is shadow-only: this state is observational and MUST NOT gate existing
 * NativeGeminiLiveClient behaviour yet.
 */
final class AgentState {
    enum Phase {
        IDLE,
        READY,
        EXECUTING,
        WAITING_FOR_UI,
        VERIFYING,
        WAITING_FOR_MODEL,
        WAITING_FOR_USER,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    final Phase phase;
    final long generation;
    final String goalId;
    final String taskId;
    final String activeActionId;
    final String lastScreenFingerprint;
    final String lastFailureCode;
    final long updatedAtMs;

    AgentState(Phase phase,
               long generation,
               String goalId,
               String taskId,
               String activeActionId,
               String lastScreenFingerprint,
               String lastFailureCode,
               long updatedAtMs) {
        this.phase = phase == null ? Phase.IDLE : phase;
        this.generation = generation;
        this.goalId = safe(goalId);
        this.taskId = safe(taskId);
        this.activeActionId = safe(activeActionId);
        this.lastScreenFingerprint = safe(lastScreenFingerprint);
        this.lastFailureCode = safe(lastFailureCode);
        this.updatedAtMs = updatedAtMs;
    }

    static AgentState idle() {
        return new AgentState(Phase.IDLE, 0L, "", "", "", "", "", System.currentTimeMillis());
    }

    AgentState with(Phase nextPhase,
                    long nextGeneration,
                    String nextGoalId,
                    String nextTaskId,
                    String nextActionId,
                    String nextFingerprint,
                    String nextFailureCode,
                    long nowMs) {
        return new AgentState(
                nextPhase,
                nextGeneration,
                nextGoalId,
                nextTaskId,
                nextActionId,
                nextFingerprint,
                nextFailureCode,
                nowMs);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @Override public String toString() {
        return "AgentState{" +
                "phase=" + phase +
                ", generation=" + generation +
                ", goalId='" + goalId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", activeActionId='" + activeActionId + '\'' +
                ", lastScreenFingerprint='" + lastScreenFingerprint + '\'' +
                ", lastFailureCode='" + lastFailureCode + '\'' +
                '}';
    }
}

