package com.relayaegis.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.recovery.RecoveryActionType;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiEscalationPolicyTest {
    private static DiagnosticFinding finding(String id, double confidence, RecoveryActionType action) {
        return DiagnosticFinding.builder(id, id, "runtime").confidence(confidence).support(new EvidenceRef("e", "runtime", "x"))
                .action(action, "c", "t").build();
    }

    @Test
    void noFindingRequiresAi() {
        assertTrue(AiEscalationPolicy.evaluate(List.of()).required());
    }

    @Test
    void confidentUnambiguousFindingDoesNotRequireAi() {
        assertFalse(AiEscalationPolicy.evaluate(List.of(finding("a", 0.92, RecoveryActionType.START_CONTAINER),
                finding("b", 0.5, RecoveryActionType.INVESTIGATE))).required());
    }

    @Test
    void lowConfidenceRequiresAi() {
        var decision = AiEscalationPolicy.evaluate(List.of(finding("a", 0.6, RecoveryActionType.INVESTIGATE)));
        assertTrue(decision.required());
        assertTrue(decision.reason().contains("below"));
    }

    @Test
    void closeCompetitorsWithDifferentActionsRequireAi() {
        var decision = AiEscalationPolicy.evaluate(List.of(finding("a", 0.85, RecoveryActionType.RESTART_CONTAINER),
                finding("b", 0.8, RecoveryActionType.RESTART_DEPENDENCY)));
        assertTrue(decision.required());
        assertTrue(decision.reason().startsWith("Ambiguous"));
    }

    @Test
    void closeCompetitorsWithTheSameActionAreNotAmbiguous() {
        assertFalse(AiEscalationPolicy.evaluate(List.of(finding("a", 0.85, RecoveryActionType.RESTART_CONTAINER),
                finding("b", 0.8, RecoveryActionType.RESTART_CONTAINER))).required());
    }

    @Test
    void contradictoryEvidenceRequiresAi() {
        DiagnosticFinding contradicted = finding("a", 0.95, RecoveryActionType.START_CONTAINER).plusContradiction("later check healthy", 0.0);
        assertTrue(AiEscalationPolicy.evaluate(List.of(contradicted)).required());
    }
}
