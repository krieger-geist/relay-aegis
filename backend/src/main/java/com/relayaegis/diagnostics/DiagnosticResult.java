package com.relayaegis.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Ranked deterministic hypotheses plus whether AI reasoning should be requested, and why. */
public record DiagnosticResult(
        List<DiagnosticFinding> ranked,
        boolean escalationRequired,
        String escalationReason,
        List<String> evidenceSources,
        List<String> unavailableEvidence,
        Instant generatedAt) {

    public Optional<DiagnosticFinding> top() { return ranked.stream().findFirst(); }

    public double topConfidence() { return top().map(DiagnosticFinding::confidence).orElse(0.0); }
}
