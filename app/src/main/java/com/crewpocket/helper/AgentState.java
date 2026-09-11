package com.crewpocket.helper;

/** High-level state projected from the append-only event ledger. */
final class AgentState {
    enum Phase {
        IDLE,
        READY,
        EXECUTING,
        WAITING_FOR_UI,
        VERIFYING,
        WAITING_FOR_VERIFICATION,
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
    final String lastStableScreenKey;
    final String lastVerificationStatus;
    final String lastFailureCode;
    final long updatedAtMs;

    AgentState(Phase phase,
               long generation,
               String goalId,
               String taskId,
               String activeActionId,
               String lastScreenFingerprint,
               String lastStableScreenKey,
               String lastVerificationStatus,
               String lastFailureCode,
               long updatedAtMs) {
        this.phase = phase == null ? Phase.IDLE : phase;
        this.generation = generation;
        this.goalId = safe(goalId);
        this.taskId = safe(taskId);
        this.activeActionId = safe(activeActionId);
        this.lastScreenFingerprint = safe(lastScreenFingerprint);
        this.lastStableScreenKey = safe(lastStableScreenKey);
        this.lastVerificationStatus = safe(lastVerificationStatus);
        this.lastFailureCode = safe(lastFailureCode);
        this.updatedAtMs = updatedAtMs;
    }

    static AgentState idle() {
        return new AgentState(Phase.IDLE, 0L, "", "", "", "", "", "", "",
                System.currentTimeMillis());
    }

    AgentState with(Phase nextPhase,
                    long nextGeneration,
                    String nextGoalId,
                    String nextTaskId,
                    String nextActionId,
                    String nextFingerprint,
                    String nextStableKey,
                    String nextVerification,
                    String nextFailureCode,
                    long nowMs) {
        return new AgentState(nextPhase, nextGeneration, nextGoalId, nextTaskId,
                nextActionId, nextFingerprint, nextStableKey, nextVerification,
                nextFailureCode, nowMs);
    }

    // Compatibility overload for the phase-1 ShadowAgentRuntime ledger.
    AgentState with(Phase nextPhase,
                    long nextGeneration,
                    String nextGoalId,
                    String nextTaskId,
                    String nextActionId,
                    String nextFingerprint,
                    String nextFailureCode,
                    long nowMs) {
        return with(nextPhase, nextGeneration, nextGoalId, nextTaskId, nextActionId,
                nextFingerprint, lastStableScreenKey, lastVerificationStatus,
                nextFailureCode, nowMs);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    @Override public String toString() {
        return "AgentState{" +
                "phase=" + phase +
                ", generation=" + generation +
                ", goalId='" + goalId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", activeActionId='" + activeActionId + '\'' +
                ", verification='" + lastVerificationStatus + '\'' +
                ", failure='" + lastFailureCode + '\'' +
                '}';
    }
}
