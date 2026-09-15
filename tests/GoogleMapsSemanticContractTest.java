package com.crewpocket.helper;

public final class GoogleMapsSemanticContractTest {
    public static void main(String[] args) {
        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_DRIVING,
                GoogleMapsSemanticContract.canonicalTarget("汽車"));
        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_DRIVING,
                GoogleMapsSemanticContract.canonicalTarget("開車"));
        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_DRIVING,
                GoogleMapsSemanticContract.canonicalTarget("car"));
        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_DRIVING,
                GoogleMapsSemanticContract.canonicalTarget("route_mode:DRIVING"));

        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_TRANSIT,
                GoogleMapsSemanticContract.canonicalTarget("大眾運輸"));
        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_WALKING,
                GoogleMapsSemanticContract.canonicalTarget("walking"));
        assertEquals(GoogleMapsSemanticContract.ROUTE_MODE_CYCLING,
                GoogleMapsSemanticContract.canonicalTarget("自行車"));
        assertEquals(GoogleMapsSemanticContract.START_NAVIGATION,
                GoogleMapsSemanticContract.canonicalTarget("開始導航"));

        assertTrue(GoogleMapsSemanticContract.isCanonicalId("route_mode:DRIVING"));
        assertFalse(GoogleMapsSemanticContract.isCanonicalId("汽車"));
        assertEquals("Driving",
                GoogleMapsSemanticContract.runtimeLabel(
                        GoogleMapsSemanticContract.ROUTE_MODE_DRIVING));

        String guidance = GoogleMapsSemanticContract.modelGuidance();
        assertTrue(guidance.contains("route_mode:DRIVING"));
        assertTrue(guidance.contains("navigation:START"));
        assertTrue(guidance.contains("Runtime owns"));

        if (GoogleMapsSemanticContract.concepts().size() < 6) {
            throw new AssertionError("Expected Maps semantic concepts");
        }
        System.out.println("GoogleMapsSemanticContractTest passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) throw new AssertionError("Expected false");
    }
}
