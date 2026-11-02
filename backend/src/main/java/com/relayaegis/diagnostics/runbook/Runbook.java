package com.relayaegis.diagnostics.runbook;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A failure type declared in a YAML file instead of Java: conditions on the collected evidence, the finding to report,
 * and one action from the closed {@link RecoveryActionType} set. It is evaluated like any built-in pattern, so the
 * policy engine, recovery guard, action journal and verification apply unchanged. Every condition that matches is
 * cited as evidence; a runbook whose conditions cite nothing never produces a finding.
 */
public record Runbook(
        String id,
        String title,
        String category,
        String cause,
        String explanation,
        double confidence,
        boolean requireFailing,
        ContainerMatch container,
        LogMatch logs,
        CheckMatch healthCheck,
        DependencyMatch dependency,
        RecoveryActionType action,
        Target target,
        String actionText,
        String source,
        String digest) implements FailurePattern {

    public enum Target { APP, MATCHED_DEPENDENCY }

    public record ContainerMatch(Boolean running, Set<String> statuses, Set<Integer> exitCodes, Boolean oomKilled, Integer minRestarts,
                                 Set<String> healthStatuses) {}

    public record LogMatch(List<Pattern> patterns, int minMatches) {}

    public record CheckMatch(Set<String> outcomes, Set<Integer> statusCodes, List<Pattern> errorPatterns) {}

    public record DependencyMatch(String name, boolean unhealthy) {}

    static final int MAX_LINE = 2000;

    @Override public String id() { return id; }

    @Override
    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (requireFailing && !context.failing()) return Optional.empty();
        List<EvidenceRef> cited = new ArrayList<>();

        if (container != null) {
            ContainerSnapshot runtime = context.runtime();
            if (runtime == null || !runtime.exists() || !matches(container, runtime)) return Optional.empty();
            cited.add(context.runtimeRef());
        }
        if (logs != null) {
            List<EvidenceRef> lines = matchingLines(context);
            if (lines.size() < logs.minMatches()) return Optional.empty();
            cited.addAll(lines.subList(0, Math.min(3, lines.size())));
        }
        if (healthCheck != null) {
            List<HealthCheckEntity> recent = context.failureStreak().stream().limit(DiagnosticContext.SIGNATURE_WINDOW).toList();
            if (recent.isEmpty() || !recent.stream().allMatch(this::matches)) return Optional.empty();
            recent.forEach(check -> cited.add(DiagnosticContext.checkRef(check)));
        }
        DiagnosticContext.DependencyState matchedDependency = null;
        if (dependency != null) {
            matchedDependency = context.dependencies().stream()
                    .filter(d -> dependency.name() == null || dependency.name().equals(d.node().name()))
                    .filter(d -> !dependency.unhealthy() || d.observedUnhealthy())
                    .filter(d -> d.snapshot() != null)
                    .findFirst().orElse(null);
            if (matchedDependency == null) return Optional.empty();
            cited.add(DiagnosticContext.dependencyRef(matchedDependency));
        }
        if (cited.isEmpty()) return Optional.empty();

        String actionTarget = target == Target.MATCHED_DEPENDENCY
                ? (matchedDependency == null ? null : matchedDependency.node().name())
                : (context.runtime() != null ? context.runtime().name() : context.appContainer());
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id, title, category)
                .cause(cause)
                .explanation((explanation == null ? "" : explanation + " ") + "Matched runbook " + id + " (" + source + ", sha256 " + digest + ").")
                .confidence(confidence)
                .support(cited)
                .action(action, actionTarget, actionText);
        if (logs != null && context.logs() == null) finding.missing("Application logs");
        return Optional.of(finding.build());
    }

    private static boolean matches(ContainerMatch m, ContainerSnapshot runtime) {
        if (m.running() != null && m.running() != runtime.running()) return false;
        if (m.statuses() != null && !m.statuses().contains(runtime.status())) return false;
        if (m.exitCodes() != null && (runtime.exitCode() == null || !m.exitCodes().contains(runtime.exitCode()))) return false;
        if (m.oomKilled() != null && m.oomKilled() != runtime.oomKilled()) return false;
        if (m.minRestarts() != null && runtime.restartCount() < m.minRestarts()) return false;
        return m.healthStatuses() == null || (runtime.healthStatus() != null && m.healthStatuses().contains(runtime.healthStatus()));
    }

    private boolean matches(HealthCheckEntity check) {
        if (healthCheck.outcomes() != null && !healthCheck.outcomes().contains(DiagnosticContext.outcome(check))) return false;
        if (healthCheck.statusCodes() != null && (check.statusCode == null || !healthCheck.statusCodes().contains(check.statusCode))) return false;
        if (healthCheck.errorPatterns() != null) {
            String error = check.errorMessage == null ? "" : check.errorMessage;
            return healthCheck.errorPatterns().stream().anyMatch(p -> p.matcher(clip(error)).find());
        }
        return true;
    }

    private List<EvidenceRef> matchingLines(DiagnosticContext context) {
        List<EvidenceRef> refs = new ArrayList<>();
        if (context.logs() == null || !context.logs().available()) return refs;
        List<String> lines = context.logs().lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = clip(lines.get(i));
            if (logs.patterns().stream().anyMatch(p -> p.matcher(line).find())) {
                refs.add(new EvidenceRef("LOG-" + context.logs().container() + "-" + i, "logs", "Log line (runbook " + id + "): " + line));
            }
        }
        return refs;
    }

    private static String clip(String text) { return text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text; }
}
