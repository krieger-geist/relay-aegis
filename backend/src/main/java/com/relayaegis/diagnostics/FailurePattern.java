package com.relayaegis.diagnostics;

import java.util.Optional;

/**
 * A deterministic failure signature. Implementations must be side-effect free, must cite the evidence they rely on,
 * and must state what evidence is missing rather than assume it.
 */
public interface FailurePattern {
    String id();

    Optional<DiagnosticFinding> evaluate(DiagnosticContext context);
}
