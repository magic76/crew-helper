package com.crewpocket.helper;

public final class LocatorConfidencePolicyTest {
    public static void main(String[] args) {
        expect(
                LocatorConfidencePolicy.Outcome.AUTO,
                LocatorConfidencePolicy.evaluate(
                        0.99, 0.98, true, false, false).outcome,
                "exact current element id should execute");

        expect(
                LocatorConfidencePolicy.Outcome.AMBIGUOUS,
                LocatorConfidencePolicy.evaluate(
                        0.90, 0.84, false, false, false).outcome,
                "very close strong candidates still require choice");

        expect(
                LocatorConfidencePolicy.Outcome.AUTO,
                LocatorConfidencePolicy.evaluate(
                        0.90, 0.80, false, false, false).outcome,
                "clear high-confidence lead should execute");

        expect(
                LocatorConfidencePolicy.Outcome.RETRY_OBSERVE,
                LocatorConfidencePolicy.evaluate(
                        0.75, 0.50, false, false, false).outcome,
                "medium confidence should reobserve");

        System.out.println("LocatorConfidencePolicyTest passed");
    }

    private static void expect(
            LocatorConfidencePolicy.Outcome expected,
            LocatorConfidencePolicy.Outcome actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(
                    message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
