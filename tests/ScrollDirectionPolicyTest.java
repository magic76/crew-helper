package com.crewpocket.helper;

public final class ScrollDirectionPolicyTest {
    public static void main(String[] args) {
        assertEquals("forward", ScrollDirectionPolicy.normalizeSemantic(""));
        assertEquals("forward", ScrollDirectionPolicy.normalizeSemantic("up"));
        assertEquals("backward", ScrollDirectionPolicy.normalizeSemantic("down"));

        assertEquals("up", ScrollDirectionPolicy.toPhysical("forward"));
        assertEquals("down", ScrollDirectionPolicy.toPhysical("backward"));
        assertEquals("left", ScrollDirectionPolicy.toPhysical("right"));
        assertEquals("right", ScrollDirectionPolicy.toPhysical("left"));

        assertEquals("forward", ScrollDirectionPolicy.fromPhysical("up"));
        assertEquals("backward", ScrollDirectionPolicy.fromPhysical("down"));
        assertEquals("right", ScrollDirectionPolicy.fromPhysical("left"));
        assertEquals("left", ScrollDirectionPolicy.fromPhysical("right"));

        assertEquals("right",
                ScrollDirectionPolicy.explicitSemanticFromUserText("往右滑"));
        assertEquals("left",
                ScrollDirectionPolicy.explicitSemanticFromUserText("幫我往左滑一下"));
        assertEquals("forward",
                ScrollDirectionPolicy.explicitSemanticFromUserText("往下滑"));
        assertEquals("backward",
                ScrollDirectionPolicy.explicitSemanticFromUserText("請往上滑一下"));
        assertEquals("right",
                ScrollDirectionPolicy.explicitSemanticFromUserText("swipe right"));
        assertEquals("",
                ScrollDirectionPolicy.explicitSemanticFromUserText("滑動螢幕"));
        assertEquals("",
                ScrollDirectionPolicy.explicitSemanticFromUserText("往右滑然後播放音樂"));

        assertTrue(ScrollDirectionPolicy.isSupported("right"));
        assertTrue(ScrollDirectionPolicy.isSupported("left"));
        assertFalse(ScrollDirectionPolicy.isSupported("diagonal"));

        System.out.println("ScrollDirectionPolicyTest passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) throw new AssertionError("expected false");
    }
}
