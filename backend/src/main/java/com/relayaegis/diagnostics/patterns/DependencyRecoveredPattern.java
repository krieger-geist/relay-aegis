package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A dependency came back (restarted after the failure began, or the logs show earlier failures reaching it) but the
 * application is still failing: it probably holds stale connections and needs its own restart.
 */
public class DependencyRecoveredPattern implements FailurePattern {
    public String id() { return "dependency-recovered"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (!context.failing() || context.dependencies().isEmpty()) return Optional.empty();
        if (context.dependencies().stream().anyMatch(DependencyState::observedUnhealthy)) return Optional.empty();
        List<HealthCheckEntity> streak = context.failureStreak();
        Instant failureStart = streak.isEmpty() ? context.detectedAt() : streak.get(streak.size() - 1).checkedAt;
        for (DependencyState dependency : context.dependencies()) {
            if (!dependency.observedHealthy()) continue;
            boolean restartedAfterFailure = failureStart != null && dependency.snapshot().startedAt() != null
                    && dependency.snapshot().startedAt().isAfter(failureStart);
            List<LogExcerpt.LogSignal> earlierErrors = PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.CONNECTION_REFUSED).stream()
                    .filter(signal -> DependencyUnavailablePattern.mentions(signal, dependency)).toList();
            if (!restartedAfterFailure && earlierErrors.isEmpty()) continue;
            String name = dependency.node().name();
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Dependency recovered, application did not", "dependency")
                    .cause("Dependency " + name + " is healthy again, but the application has not recovered.")
                    .explanation((restartedAfterFailure ? name + " was (re)started after the application began failing. " : "")
                            + (earlierErrors.isEmpty() ? "" : "The application logs show failed connections to " + name + ". ")
                            + "Applications that open connections at start-up often do not reconnect on their own.")
                    .confidence(restartedAfterFailure && !earlierErrors.isEmpty() ? 0.86 : 0.78)
                    .action(context.hasRuntime() ? RecoveryActionType.RESTART_CONTAINER : RecoveryActionType.INVESTIGATE, context.appContainer(),
                            "Restart the application so it reconnects to " + name + " (policy-controlled), then verify health.");
            finding.support(DiagnosticContext.dependencyRef(dependency));
            PatternSupport.citeFailures(finding, context);
            PatternSupport.citeRuntime(finding, context);
            earlierErrors.stream().limit(2).forEach(signal -> finding.support(DiagnosticContext.logRef(context.logs(), signal)));
            return Optional.of(finding.build());
        }
        return Optional.empty();
    }
}
