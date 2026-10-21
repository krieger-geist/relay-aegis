package com.relayaegis.diagnostics;

import java.util.List;

/**
 * Decides whether AI reasoning adds value. Gemini is consulted only when the deterministic result is weak,
 * ambiguous, contradicted, or absent - never as a prerequisite for diagnosis or recovery.
 */
public final class AiEscalationPolicy {
    public static final double CONFIDENCE_THRESHOLD = 0.75;
    public static final double AMBIGUITY_MARGIN = 0.1;

    private AiEscalationPolicy() {}

    public record Decision(boolean required, String reason) {}

    public static Decision evaluate(List<DiagnosticFinding> ranked) {
        if (ranked.isEmpty()) return new Decision(true, "No deterministic failure pattern matched the evidence.");
        DiagnosticFinding top = ranked.get(0);
        if ("application-healthy".equals(top.patternId())) {
            return new Decision(false, "The application is responding normally; no further reasoning is needed.");
        }
        if (top.confidence() < CONFIDENCE_THRESHOLD) {
            return new Decision(true, String.format("Top deterministic confidence %.2f is below the %.2f threshold.", top.confidence(), CONFIDENCE_THRESHOLD));
        }
        if (!top.contradictory().isEmpty()) {
            return new Decision(true, "The leading hypothesis has contradictory evidence: " + top.contradictory().get(0));
        }
        if (ranked.size() > 1) {
            DiagnosticFinding second = ranked.get(1);
            if (!"application-healthy".equals(second.patternId()) && second.action() != top.action()
                    && top.confidence() - second.confidence() < AMBIGUITY_MARGIN) {
                return new Decision(true, "Ambiguous: '" + top.title() + "' and '" + second.title() + "' are similarly likely but call for different actions.");
            }
        }
        return new Decision(false, String.format("Deterministic confidence %.2f for '%s' is sufficient; AI reasoning was not required.", top.confidence(), top.title()));
    }
}
