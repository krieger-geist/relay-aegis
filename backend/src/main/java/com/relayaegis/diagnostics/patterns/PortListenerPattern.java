package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import java.util.List;
import java.util.Optional;

/** The container runs, but nothing accepts connections on the monitored port (listener died, wrong port, port in use). */
public class PortListenerPattern implements FailurePattern {
    public String id() { return "port-not-listening"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        ContainerSnapshot runtime = context.runtime();
        if (runtime == null || !runtime.exists() || !runtime.running() || !context.failing()) return Optional.empty();
        boolean refused = context.streakAll(DiagnosticContext::isRefused) || (context.network() != null && context.network().tcpRefused());
        if (!refused) return Optional.empty();
        int port = PatternSupport.urlPort(context);
        List<LogExcerpt.LogSignal> listening = PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.LISTENING);
        boolean inUse = !PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.PORT_IN_USE).isEmpty();
        boolean listensElsewhere = port > 0 && !listening.isEmpty() && listening.stream().noneMatch(signal -> String.valueOf(port).equals(signal.target()));
        boolean notExposed = port > 0 && !runtime.exposedPorts().isEmpty() && runtime.exposedPorts().stream().noneMatch(p -> p.startsWith(port + "/"));

        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Process running but not listening", "runtime");
        if (inUse) {
            finding.cause("The application could not bind its port because the address is already in use.")
                    .confidence(0.86).action(RecoveryActionType.RESTART_CONTAINER, runtime.name(),
                            "Restart the container so the application can bind its port, then verify; if it recurs, find the conflicting listener.");
        } else if (listensElsewhere || notExposed) {
            finding.cause("The application is listening on a different port than the monitor expects (" + PatternSupport.hostPort(context) + ").")
                    .confidence(0.85).action(RecoveryActionType.ESCALATE, runtime.name(),
                            "Correct the port mapping or the registered health URL; a restart would not change the configured port.");
        } else {
            finding.cause("The container is running but no process accepts connections on " + PatternSupport.hostPort(context) + ".")
                    .confidence(0.7).action(RecoveryActionType.RESTART_CONTAINER, runtime.name(),
                            "Restart the container to restore the listener (policy-controlled), then verify health.");
        }
        finding.explanation("Docker reports the container as running while connections to the monitored port are refused. "
                + (listensElsewhere ? "The logs show the application listening on port " + listening.get(0).target() + ". " : "")
                + (notExposed ? "Port " + port + " is not among the container's ports " + runtime.exposedPorts() + ". " : ""));
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeFailures(finding, context);
        if (context.network() != null) finding.support(context.networkRef());
        PatternSupport.citeLogs(finding, context, 2, LogExcerpt.LogSignal.Kind.PORT_IN_USE, LogExcerpt.LogSignal.Kind.LISTENING);
        return Optional.of(finding.build());
    }
}
