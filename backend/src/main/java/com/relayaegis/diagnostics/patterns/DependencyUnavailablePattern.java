package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The application fails and one of its dependencies is down: the dependency is the likely root cause. */
public class DependencyUnavailablePattern implements FailurePattern {
    public String id() { return "dependency-unavailable"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (!context.failing()) return Optional.empty();
        DependencyState down = context.dependencies().stream().filter(DependencyState::observedUnhealthy).findFirst().orElse(null);
        if (down == null) return Optional.empty();
        String name = down.node().name();
        List<LogExcerpt.LogSignal> mentions = PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.CONNECTION_REFUSED).stream()
                .filter(signal -> mentions(signal, down)).toList();
        if (mentions.isEmpty()) {
            mentions = PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.DNS_FAILURE).stream().filter(signal -> mentions(signal, down)).toList();
        }
        if (mentions.isEmpty()) {
            mentions = PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.TIMEOUT).stream().filter(signal -> mentions(signal, down)).toList();
        }
        boolean missing = down.snapshot() != null && !down.snapshot().exists();
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Dependency unavailable: " + name, "dependency")
                .cause("The application depends on " + name + ", which is " + (missing ? "missing" : "not healthy") + " (" + down.snapshot().describe() + ").")
                .explanation("The application is failing while its dependency " + name + " is down. "
                        + (mentions.isEmpty() ? "The application logs do not name the dependency, so the link is inferred from the dependency graph."
                                : "The application logs show failed connections to " + name + "."))
                .confidence(mentions.isEmpty() ? 0.85 : 0.93);
        finding.support(DiagnosticContext.dependencyRef(down));
        PatternSupport.citeFailures(finding, context);
        mentions.stream().limit(2).forEach(signal -> finding.support(DiagnosticContext.logRef(context.logs(), signal)));
        if (context.logs() == null || !context.logs().available()) finding.missing("Application logs naming the failing dependency");
        if (missing || down.node().container() == null) {
            finding.action(RecoveryActionType.ESCALATE, down.node().container(),
                    "Recreate or reconfigure dependency " + name + "; it cannot be restarted because its container was not found.");
        } else {
            finding.action(RecoveryActionType.RESTART_DEPENDENCY, down.node().container(),
                    "Recover dependency " + name + " (requires the dependency-recovery policy and the container allowlist), then verify the application.");
        }
        return Optional.of(finding.build());
    }

    static boolean mentions(LogExcerpt.LogSignal signal, DependencyState dependency) {
        String haystack = ((signal.target() == null ? "" : signal.target()) + " " + signal.line()).toLowerCase(Locale.ROOT);
        String name = dependency.node().name().toLowerCase(Locale.ROOT);
        String container = dependency.node().container() == null ? "" : dependency.node().container().toLowerCase(Locale.ROOT);
        return haystack.contains(name) || (!container.isBlank() && haystack.contains(container));
    }
}
