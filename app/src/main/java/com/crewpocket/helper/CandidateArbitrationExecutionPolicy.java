package com.crewpocket.helper;

/**
 * Pure Phase-1 Runtime adoption gate.
 *
 * Model confidence is intentionally absent: a decisive advisor result still
 * needs fresh deterministic evidence and current Runtime authority.
 */
final class CandidateArbitrationExecutionPolicy {
    enum Verdict {
        ALLOW,
        NOT_TREATMENT,
        ADVISOR_NOT_DECISIVE,
        STALE_GENERATION,
        PACKAGE_CHANGED,
        CANDIDATE_NOT_FOUND,
        CANDIDATE_NOT_CLICKABLE,
        SENSITIVE_TARGET,
        AUTHORITY_BLOCKED
    }

    static final class Result {
        final Verdict verdict;

        Result(Verdict verdict) {
            this.verdict = verdict == null
                    ? Verdict.AUTHORITY_BLOCKED
                    : verdict;
        }

        boolean allowed() {
            return verdict == Verdict.ALLOW;
        }

        boolean staleEvidence() {
            return verdict == Verdict.STALE_GENERATION
                    || verdict == Verdict.PACKAGE_CHANGED
                    || verdict == Verdict.CANDIDATE_NOT_FOUND
                    || verdict == Verdict.CANDIDATE_NOT_CLICKABLE;
        }

        boolean authorityBlocked() {
            return verdict == Verdict.SENSITIVE_TARGET
                    || verdict == Verdict.AUTHORITY_BLOCKED;
        }
    }

    private CandidateArbitrationExecutionPolicy() {}

    static Result evaluate(
            boolean treatment,
            boolean advisorDecisive,
            boolean generationCurrent,
            boolean packageCurrent,
            boolean candidateExists,
            boolean candidateClickable,
            boolean sensitiveBlocked,
            boolean authorityAllowed) {
        if (!treatment) return new Result(Verdict.NOT_TREATMENT);
        if (!advisorDecisive) {
            return new Result(Verdict.ADVISOR_NOT_DECISIVE);
        }
        if (!generationCurrent) {
            return new Result(Verdict.STALE_GENERATION);
        }
        if (!packageCurrent) {
            return new Result(Verdict.PACKAGE_CHANGED);
        }
        if (!candidateExists) {
            return new Result(Verdict.CANDIDATE_NOT_FOUND);
        }
        if (!candidateClickable) {
            return new Result(Verdict.CANDIDATE_NOT_CLICKABLE);
        }
        if (sensitiveBlocked) {
            return new Result(Verdict.SENSITIVE_TARGET);
        }
        if (!authorityAllowed) {
            return new Result(Verdict.AUTHORITY_BLOCKED);
        }
        return new Result(Verdict.ALLOW);
    }
}
