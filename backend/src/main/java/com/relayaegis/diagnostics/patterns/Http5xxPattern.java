package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import java.util.Optional;

/** The application is up but its health endpoint reports a server-side error. */
public class Http5xxPattern implements FailurePattern {
    public String id() { return "http-5xx"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        HealthCheckEntity latest = context.latestCheck().orElse(null);
        if (latest == null || !DiagnosticContext.isServerError(latest)) return Optional.empty();
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Server error (HTTP " + latest.statusCode + ")", "application")
                .cause("The application is running but its health endpoint returns HTTP " + latest.statusCode + ".")
                .explanation("A 5xx status means the server reached the request and failed while handling it - commonly an unhandled error or an unavailable dependency.")
                .confidence(0.7)
                .action(RecoveryActionType.INVESTIGATE, context.appContainer(),
                        "Review the application error logs at the detection time and the health of its dependencies; restart only if the logs show a stuck state.");
        PatternSupport.citeFailures(finding, context);
        PatternSupport.citeRuntime(finding, context);
        if (PatternSupport.citeLogs(finding, context, 3, LogExcerpt.LogSignal.Kind.FATAL, LogExcerpt.LogSignal.Kind.EXCEPTION,
                LogExcerpt.LogSignal.Kind.CONNECTION_REFUSED, LogExcerpt.LogSignal.Kind.TIMEOUT) > 0) {
            finding.confidence(0.78);
        }
        if (context.dependencies().isEmpty()) finding.missing("Dependency map for this application (none configured or discovered)");
        return Optional.of(finding.build());
    }
}
