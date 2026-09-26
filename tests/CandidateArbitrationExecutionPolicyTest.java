package com.crewpocket.helper;

public final class CandidateArbitrationExecutionPolicyTest {
    public static void main(String[] args) {
        expect(
                CandidateArbitrationExecutionPolicy.Verdict.ALLOW,
                CandidateArbitrationExecutionPolicy.evaluate(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true).verdict,
                "fresh low-risk treatment may execute");

        expect(
                CandidateArbitrationExecutionPolicy.Verdict.STALE_GENERATION,
                CandidateArbitrationExecutionPolicy.evaluate(
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        false,
                        true).verdict,
                "stale generation must stop treatment");

        expect(
                CandidateArbitrationExecutionPolicy.Verdict.CANDIDATE_NOT_FOUND,
                CandidateArbitrationExecutionPolicy.evaluate(
                        true,
                        true,
                        true,
                        true,
                        false,
                        false,
                        false,
                        true).verdict,
                "missing fresh candidate must stop treatment");

        expect(
                CandidateArbitrationExecutionPolicy.Verdict.SENSITIVE_TARGET,
                CandidateArbitrationExecutionPolicy.evaluate(
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        true).verdict,
                "sensitive candidate must be blocked even when not clickable");

        expect(
                CandidateArbitrationExecutionPolicy.Verdict.AUTHORITY_BLOCKED,
                CandidateArbitrationExecutionPolicy.evaluate(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        false).verdict,
                "current Runtime authority remains mandatory");

        expect(
                CandidateArbitrationExecutionPolicy.Verdict.ADVISOR_NOT_DECISIVE,
                CandidateArbitrationExecutionPolicy.evaluate(
                        true,
                        false,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true).verdict,
                "abstain is normal and cannot execute");

        System.out.println(
                "CandidateArbitrationExecutionPolicyTest passed");
    }

    private static void expect(
            CandidateArbitrationExecutionPolicy.Verdict expected,
            CandidateArbitrationExecutionPolicy.Verdict actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(
                    message
                            + ": expected="
                            + expected
                            + " actual="
                            + actual);
        }
    }
}
