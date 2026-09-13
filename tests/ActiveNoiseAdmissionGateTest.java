package com.crewpocket.helper;

public final class ActiveNoiseAdmissionGateTest {
    private static final byte[] FRAME = new byte[1280];

    public static void main(String[] args) {
        testQuietBypass();
        testOutdoorNoiseSuppressedThenSpeechAdmitted();
        testVeryNoisyNeedsFourFrames();
        System.out.println("ActiveNoiseAdmissionGateTest OK");
    }

    private static void testQuietBypass() {
        ActiveNoiseAdmissionGate gate = new ActiveNoiseAdmissionGate(FRAME.length);
        assertEquals(ActiveNoiseAdmissionGate.Action.BYPASS,
                gate.accept(FRAME, FRAME.length, 0.012, 0.02,
                        0.010, "auto", 35, true), "quiet environment must bypass");
    }

    private static void testOutdoorNoiseSuppressedThenSpeechAdmitted() {
        ActiveNoiseAdmissionGate gate = new ActiveNoiseAdmissionGate(FRAME.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(ActiveNoiseAdmissionGate.Action.SUPPRESS,
                    gate.accept(FRAME, FRAME.length, 0.052, 0.003,
                            0.050, "auto", 35, true), "wind/rumble should stay local");
        }
        assertEquals(ActiveNoiseAdmissionGate.Action.SUPPRESS,
                gate.accept(FRAME, FRAME.length, 0.090, 0.08,
                        0.050, "auto", 35, true), "speech candidate 1");
        assertEquals(ActiveNoiseAdmissionGate.Action.SUPPRESS,
                gate.accept(FRAME, FRAME.length, 0.092, 0.07,
                        0.050, "auto", 35, true), "speech candidate 2");
        assertEquals(ActiveNoiseAdmissionGate.Action.FLUSH_PREROLL,
                gate.accept(FRAME, FRAME.length, 0.095, 0.09,
                        0.050, "auto", 35, true), "speech candidate 3 opens gate");
        if (gate.bufferedFrameCount() < 3 || gate.bufferedFrameCount() > 6) {
            throw new AssertionError("pre-roll should retain the recent onset");
        }
        gate.clearBufferedFrames();

        for (int i = 0; i < 16; i++) {
            assertEquals(ActiveNoiseAdmissionGate.Action.SEND,
                    gate.accept(FRAME, FRAME.length, 0.050, 0.003,
                            0.050, "auto", 35, true), "trailing audio should reach server VAD");
        }
        assertEquals(ActiveNoiseAdmissionGate.Action.SUPPRESS,
                gate.accept(FRAME, FRAME.length, 0.050, 0.003,
                        0.050, "auto", 35, true), "gate should close after trailing window");
    }

    private static void testVeryNoisyNeedsFourFrames() {
        ActiveNoiseAdmissionGate gate = new ActiveNoiseAdmissionGate(FRAME.length);
        for (int i = 0; i < 3; i++) {
            assertEquals(ActiveNoiseAdmissionGate.Action.SUPPRESS,
                    gate.accept(FRAME, FRAME.length, 0.110, 0.08,
                            0.070, "auto", 20, true), "very noisy should require 4 frames");
        }
        assertEquals(ActiveNoiseAdmissionGate.Action.FLUSH_PREROLL,
                gate.accept(FRAME, FRAME.length, 0.115, 0.08,
                        0.070, "auto", 20, true), "fourth speech frame should open");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
