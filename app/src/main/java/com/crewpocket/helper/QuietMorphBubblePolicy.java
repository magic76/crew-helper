package com.crewpocket.helper;

/**
 * Presentation policy for the compact floating Crew bubble.
 *
 * Continuous work is communicated by the animated logo. The capsule morph is
 * reserved for events or states that materially deserve text attention.
 */
final class QuietMorphBubblePolicy {
    static final long PHASE_DEBOUNCE_MS = 300L;
    static final long WAITING_MORPH_DELAY_MS = 1_500L;
    static final long DONE_MORPH_MS = 900L;
    static final long ERROR_MORPH_MS = 2_400L;

    private QuietMorphBubblePolicy() {}

    static boolean shouldShowPersistentMorph(
            BubbleLogoStatePolicy.Mode mode,
            boolean waitingDelayElapsed) {
        if (mode == null) return false;
        if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER
                || mode == BubbleLogoStatePolicy.Mode.ERROR
                || mode == BubbleLogoStatePolicy.Mode.STUCK) {
            return true;
        }
        return mode == BubbleLogoStatePolicy.Mode.WAITING
                && waitingDelayElapsed;
    }

    static String persistentLabel(
            BubbleLogoStatePolicy.Mode mode,
            boolean waitingDelayElapsed) {
        if (!shouldShowPersistentMorph(mode, waitingDelayElapsed)) {
            return "";
        }
        if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER
                || mode == BubbleLogoStatePolicy.Mode.STUCK) {
            return "需要你";
        }
        if (mode == BubbleLogoStatePolicy.Mode.ERROR) {
            return "連線異常";
        }
        if (mode == BubbleLogoStatePolicy.Mode.WAITING) {
            return "等畫面";
        }
        return "";
    }

    static int accentColor(
            BubbleLogoStatePolicy.Mode mode) {
        if (mode == BubbleLogoStatePolicy.Mode.WAITING_USER
                || mode == BubbleLogoStatePolicy.Mode.STUCK) {
            return 0xFFF59E0B;
        }
        if (mode == BubbleLogoStatePolicy.Mode.ERROR) {
            return 0xFFF43F5E;
        }
        if (mode == BubbleLogoStatePolicy.Mode.WAITING) {
            return 0xFF64748B;
        }
        return 0xFF38BDF8;
    }
}
