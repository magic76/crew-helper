package com.crewpocket.helper;

/**
 * Guard only the external-effect boundary.
 *
 * Preparation such as navigation, focusing and TYPE stays reversible and is
 * intentionally outside this policy. Once a commit has been dispatched, the
 * same authorization must never dispatch a second time merely because the
 * result is uncertain.
 */
final class CommitGuard {
    enum Decision {
        ALLOW,
        BLOCK_MISSING_INTENT,
        REQUIRE_TARGET_VERIFICATION,
        SUPPRESS_DUPLICATE,
        HANDOFF_SENSITIVE
    }

    static final class Result {
        final Decision decision;
        final String code;

        Result(Decision decision, String code) {
            this.decision = decision;
            this.code = code == null ? "" : code;
        }

        boolean allowed() {
            return decision == Decision.ALLOW;
        }
    }

    private CommitGuard() {}

    static Result evaluate(
            boolean authorized,
            boolean dispatched,
            boolean targetVerificationRequired,
            boolean targetVerified,
            boolean sensitive) {
        if (sensitive) {
            return new Result(
                    Decision.HANDOFF_SENSITIVE,
                    "COMMIT_SENSITIVE_HANDOFF");
        }
        if (dispatched) {
            return new Result(
                    Decision.SUPPRESS_DUPLICATE,
                    "COMMIT_ALREADY_DISPATCHED");
        }
        if (!authorized) {
            return new Result(
                    Decision.BLOCK_MISSING_INTENT,
                    "COMMIT_NOT_AUTHORIZED");
        }
        if (targetVerificationRequired && !targetVerified) {
            return new Result(
                    Decision.REQUIRE_TARGET_VERIFICATION,
                    "COMMIT_TARGET_NOT_VERIFIED");
        }
        return new Result(Decision.ALLOW, "ALLOW");
    }
}
