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
