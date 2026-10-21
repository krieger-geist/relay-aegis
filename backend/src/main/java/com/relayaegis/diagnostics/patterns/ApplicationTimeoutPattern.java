package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import java.util.Optional;

/** Requests reach the application but no response arrives before the deadline: hung, overloaded, or blocked. */
public class ApplicationTimeoutPattern implements FailurePattern {
    public String id() { return "application-timeout"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (!context.failing() || !context.streakAll(DiagnosticContext::isTimeout)) return Optional.empty();
        ContainerSnapshot runtime = context.runtime();
        boolean running = runtime != null && runtime.exists() && runtime.running();
        if (runtime != null && runtime.exists() && !running) return Optional.empty(); // a stopped container is diagnosed elsewhere
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Application not responding in time", "availability")
                .cause("The application does not answer before the monitoring deadline.")
                .explanation("Consecutive checks timed out. " + (running ? "The container is running, so the process is alive but not responding (hung, saturated, or blocked on a dependency)."
                        : "Without runtime data it cannot be determined whether the process is hung, overloaded, or unreachable over the network."))
                .confidence(running ? 0.75 : 0.55);
        PatternSupport.citeFailures(finding, context);
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.requireRuntimeOrSayMissing(finding, context);
        if (running && runtime.cpuPercent() != null && runtime.cpuPercent() >= 90) {
            finding.support(new EvidenceRef("RT-" + runtime.name() + "-cpu", "runtime", "CPU usage is " + String.format("%.0f", runtime.cpuPercent()) + "%"));
            finding.confidence(0.82);
        }
        if (context.network() != null && context.network().tcpConnected()) {
            finding.support(context.networkRef());
        } else if (context.network() != null && context.network().tcpTimedOut()) {
            finding.support(context.networkRef());
            finding.contradict("The TCP connection itself timed out, which also fits a network path problem rather than a hung application");
        }
        PatternSupport.citeLogs(finding, context, 2, LogExcerpt.LogSignal.Kind.TIMEOUT, LogExcerpt.LogSignal.Kind.EXCEPTION);
        finding.action(running ? RecoveryActionType.RESTART_CONTAINER : RecoveryActionType.INVESTIGATE, running ? runtime.name() : null,
                running ? "Restart the hung container (policy-controlled) and verify; if timeouts recur, profile the application or its dependencies."
                        : "Check whether the process is alive and whether a dependency or network path is slow before choosing an action.");
        return Optional.of(finding.build());
    }
}
