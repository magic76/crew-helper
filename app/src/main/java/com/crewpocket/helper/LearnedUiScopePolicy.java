package com.crewpocket.helper;

/**
 * Pure policy for scoping user-taught App Action Memory.
 *
 * Exact full-screen fingerprints are too volatile because chat/message text
 * changes frequently. A stable structural screen key is therefore a soft
 * affinity signal: it separates distinct layouts without becoming a hard gate.
 */
final class LearnedUiScopePolicy {
    private static final int SAME_STRUCTURE_BONUS = 36;
    private static final int DIFFERENT_STRUCTURE_PENALTY = -18;

    private LearnedUiScopePolicy() {}

    static int affinityBonus(String learnedStructureKey, String currentStructureKey) {
        String learned = safe(learnedStructureKey);
        String current = safe(currentStructureKey);
        if (learned.isEmpty() || current.isEmpty()) return 0;
        return learned.equals(current)
                ? SAME_STRUCTURE_BONUS
                : DIFFERENT_STRUCTURE_PENALTY;
    }

    static boolean sameActionSlot(
            String packageA,
            String roleA,
            String composerStateA,
            String structureA,
            String packageB,
            String roleB,
            String composerStateB,
            String structureB) {
        if (!safe(packageA).equals(safe(packageB))) return false;
        if (!safe(roleA).equals(safe(roleB))) return false;

        String aState = safe(composerStateA);
        String bState = safe(composerStateB);
        boolean stateCompatible =
                aState.isEmpty()
                        || "UNKNOWN".equals(aState)
                        || bState.isEmpty()
                        || "UNKNOWN".equals(bState)
                        || aState.equals(bState);
        if (!stateCompatible) return false;

        String aStructure = safe(structureA);
        String bStructure = safe(structureB);

        // Legacy rules created before structure keys existed remain replaceable
        // by the next explicit teaching action.
        if (aStructure.isEmpty() || bStructure.isEmpty()) return true;

        return aStructure.equals(bStructure);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
