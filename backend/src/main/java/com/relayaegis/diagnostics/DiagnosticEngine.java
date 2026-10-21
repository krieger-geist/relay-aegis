package com.relayaegis.diagnostics;

import com.relayaegis.recovery.RecoveryActionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every registered failure pattern against the collected evidence, rejects conclusions that cite no evidence
 * when evidence exists, correlates across sources, ranks all matches, and decides whether AI escalation is useful.
 * Deterministic, fast, and independent of any AI provider.
 */
@Component
public class DiagnosticEngine {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticEngine.class);
    private final FailurePatternRegistry registry;
    private final EvidenceCorrelationEngine correlation;

    public DiagnosticEngine(FailurePatternRegistry registry, EvidenceCorrelationEngine correlation) {
        this.registry = registry;
        this.correlation = correlation;
    }

    public DiagnosticResult diagnose(DiagnosticContext context) {
        List<DiagnosticFinding> findings = new ArrayList<>();
        for (FailurePattern pattern : registry.all()) {
            try {
                Optional<DiagnosticFinding> finding = pattern.evaluate(context);
                if (finding.isEmpty()) continue;
                if (context.hasAnyEvidence() && finding.get().supporting().isEmpty()) {
                    log.warn("Pattern {} produced a conclusion without evidence references; discarded", pattern.id());
                    continue;
                }
                findings.add(finding.get());
            } catch (RuntimeException ex) {
                log.warn("Pattern {} could not be evaluated: {}", pattern.id(), ex.getClass().getSimpleName());
            }
        }
        if (findings.isEmpty() && context.failing()) findings.add(unclassified(context));
        List<DiagnosticFinding> ranked = new ArrayList<>(correlation.correlate(findings, context));
        ranked.sort(Comparator.comparingDouble(DiagnosticFinding::confidence).reversed());
        AiEscalationPolicy.Decision escalation = AiEscalationPolicy.evaluate(ranked);
        return new DiagnosticResult(List.copyOf(ranked), escalation.required(), escalation.reason(), sources(context),
                List.copyOf(context.unavailable()), Instant.now());
    }

    private static DiagnosticFinding unclassified(DiagnosticContext context) {
        return DiagnosticFinding.builder("unclassified-failure", "Unclassified failure", "unknown")
                .cause("The application is failing, but no known failure pattern matches the recorded evidence.")
                .explanation("The observations do not fit a known signature. More evidence (logs, runtime state, dependency health) is needed.")
                .confidence(0.3)
                .support(context.streakRefs(2))
                .missing("Application logs and runtime state for the failure window")
                .action(RecoveryActionType.INVESTIGATE, null, "Collect logs and runtime details before choosing an action.")
                .build();
    }

    private static List<String> sources(DiagnosticContext context) {
        Set<String> sources = new LinkedHashSet<>();
        if (!context.recentChecks().isEmpty()) sources.add("health_check");
        if (!context.incidentEvidence().isEmpty()) sources.add("incident_evidence");
        if (context.runtime() != null) sources.add("runtime");
        if (context.logs() != null && context.logs().available()) sources.add("logs");
        if (context.dependencies().stream().anyMatch(d -> d.snapshot() != null)) sources.add("dependency");
        if (context.network() != null) sources.add("network");
        if (!context.code().isEmpty()) sources.add("code");
        if (context.history().incidentsLast24h() > 0) sources.add("history");
        return List.copyOf(sources);
    }
}
