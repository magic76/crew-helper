package com.crewpocket.helper;

/**
 * Pure confidence/margin gate for semantic UI targeting.
 *
 * The locator may score many candidates, but Runtime must not auto-execute when
 * the top candidates are too close. This policy is deliberately dependency-free
 * so real failures can be captured by AgentReplayRunner.
 */
final class LocatorConfidencePolicy {
    enum Outcome {
        AUTO,
        RETRY_OBSERVE,
        FALLBACK,
        AMBIGUOUS,
        NOT_FOUND
    }

    static final class Result {
        final Outcome outcome;
        final String code;
        final double margin;

        Result(Outcome outcome, String code, double margin) {
            this.outcome = outcome;
            this.code = code == null ? "" : code;
            this.margin = margin;
        }
    }

    static final double AUTO_MIN_CONFIDENCE = 0.86;
    static final double RETRY_MIN_CONFIDENCE = 0.68;
    static final double FALLBACK_MIN_CONFIDENCE = 0.50;
    static final double RUNNER_UP_RELEVANT_CONFIDENCE = 0.65;
    static final double AUTO_MIN_MARGIN = 0.08;

    private LocatorConfidencePolicy() {}

    static Result evaluate(
            double bestConfidence,
            double runnerUpConfidence,
            boolean exactElementId,
            boolean exactViewId,
            boolean runnerUpExactViewId) {
        double best = clamp(bestConfidence);
        double runner = clamp(runnerUpConfidence);
        double margin = Math.max(0.0, best - runner);

        if (best <= 0.0) {
            return new Result(Outcome.NOT_FOUND, "INSUFFICIENT_SIGNAL", margin);
        }

        // A current element id is generated from the exact current tree and is
        // stronger evidence than score proximity.
        if (exactElementId && best >= 0.98) {
            return new Result(
                    Outcome.AUTO,
                    "EXACT_CURRENT_ELEMENT_ID",
                    margin);
        }

        boolean exactViewIdWins =
                exactViewId && !runnerUpExactViewId;
        if (!exactViewIdWins
                && runner >= RUNNER_UP_RELEVANT_CONFIDENCE
                && margin < AUTO_MIN_MARGIN) {
            return new Result(
                    Outcome.AMBIGUOUS,
                    "LOCATOR_MARGIN_TOO_SMALL",
                    margin);
        }

        if (best >= AUTO_MIN_CONFIDENCE) {
            return new Result(Outcome.AUTO, "HIGH_CONFIDENCE", margin);
        }
        if (best >= RETRY_MIN_CONFIDENCE) {
            return new Result(
                    Outcome.RETRY_OBSERVE,
                    "MEDIUM_CONFIDENCE",
                    margin);
        }
        if (best >= FALLBACK_MIN_CONFIDENCE) {
            return new Result(
                    Outcome.FALLBACK,
                    "LOW_CONFIDENCE",
                    margin);
        }
        return new Result(
                Outcome.NOT_FOUND,
                "INSUFFICIENT_SIGNAL",
                margin);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
