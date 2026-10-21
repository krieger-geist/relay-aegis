package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import java.util.Optional;

/**
 * Connections are refused and there is no runtime view of the process (URL-only application). Observable fact:
 * nothing accepts connections. The internal reason (process down vs. wrong port) is a hypothesis, so confidence
 * stays moderate and AI escalation may be used to reason further.
 */
public class ConnectionRefusedPattern implements FailurePattern {
    public String id() { return "connection-refused"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (context.hasRuntime() || !context.failing() || !context.streakAll(DiagnosticContext::isRefused)) return Optional.empty();
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Connection refused", "availability")
                .cause("Nothing is accepting connections at " + PatternSupport.hostPort(context) + ": the process is down or not listening on the monitored port.")
                .explanation("The host answered but refused the TCP connection, which happens when no process listens on that port. "
                        + "Whether the process stopped or listens elsewhere cannot be told from outside.")
                .confidence(0.6)
                .action(RecoveryActionType.INVESTIGATE, null, "Confirm the application process is running and listening on the configured host and port.");
        PatternSupport.citeFailures(finding, context);
        if (context.network() != null && context.network().tcpRefused()) {
            finding.support(context.networkRef());
            finding.confidence(0.64);
        }
        PatternSupport.requireRuntimeOrSayMissing(finding, context);
        finding.missing("Application logs");
        return Optional.of(finding.build());
    }
}
