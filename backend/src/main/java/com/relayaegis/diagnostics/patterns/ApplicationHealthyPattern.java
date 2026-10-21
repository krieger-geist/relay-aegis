package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import java.util.Optional;

/** The application answers its health check again: observed recovery, nothing to repair. */
public class ApplicationHealthyPattern implements FailurePattern {
    public String id() { return "application-healthy"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (!context.currentlyHealthy()) return Optional.empty();
        int healthy = 0;
        for (HealthCheckEntity check : context.recentChecks()) { if (!check.healthy) break; healthy++; }
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Application is responding normally", "observed_recovery")
                .cause("The application answered its latest health check successfully.")
                .explanation("The most recent observation" + (healthy > 1 ? "s (" + healthy + " consecutive)" : "") + " succeeded. Earlier failures are no longer being observed.")
                .confidence(healthy >= 2 ? 0.95 : 0.85)
                .action(RecoveryActionType.NONE, null, "No recovery action. Keep monitoring; the incident closes after the configured number of consecutive healthy checks.");
        context.recentChecks().stream().limit(Math.min(healthy, 2)).forEach(check -> finding.support(DiagnosticContext.checkRef(check)));
        if (context.runtime() != null && context.runtime().exists() && !context.runtime().healthy()) {
            finding.contradict("Docker reports the container as " + context.runtime().describe());
        }
        return Optional.of(finding.build());
    }
}
