package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.NetworkPartitions;
import java.util.List;
import java.util.Optional;

/**
 * Network partition: the application fails while a dependency is running and healthy but no longer attached to the
 * application's Compose network, so the application cannot resolve or reach it. Restarting either container would not
 * help; the dependency must be reattached to its own project network under its service alias.
 */
public class DependencyNetworkDetachedPattern implements FailurePattern {
    public String id() { return "dependency-network-detached"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (!context.failing() || context.runtime() == null || !context.runtime().running()) return Optional.empty();
        for (DependencyState dependency : context.dependencies()) {
            if (!dependency.observedHealthy()) continue;
            Optional<NetworkPartitions.Reattachment> reattach = NetworkPartitions.reattachment(context.runtime(), dependency.snapshot());
            if (reattach.isEmpty()) continue;
            String name = dependency.node().name();
            String network = reattach.get().network();
            List<String> attached = dependency.snapshot().networks();
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Network partition: " + name + " is detached from " + network, "dependency")
                    .cause("The dependency " + name + " is running and healthy, but it is not attached to " + network
                            + ", the network the application uses to reach it" + (attached.isEmpty() ? " (it is attached to no network)." : " (attached only to " + String.join(", ", attached) + ")."))
                    .explanation("The application cannot resolve or reach " + name + " although the dependency itself is healthy. "
                            + "Restarting the application or the dependency would not reattach it; reconnecting " + name + " to " + network
                            + " under its service alias '" + reattach.get().alias() + "' restores the path.")
                    .confidence(0.95);
            finding.support(new EvidenceRef("NETPART-" + name, "runtime", "Dependency " + name + " healthy (" + dependency.snapshot().describe() + ") but not on "
                    + network + "; attached networks: " + (attached.isEmpty() ? "none" : String.join(", ", attached))));
            finding.support(new EvidenceRef("NETAPP-" + context.runtime().name(), "runtime", "Application container " + context.runtime().name()
                    + " is attached to " + String.join(", ", context.runtime().networks())));
            PatternSupport.citeFailures(finding, context);
            connectionSignals(context, dependency).stream().limit(2).forEach(signal -> finding.support(DiagnosticContext.logRef(context.logs(), signal)));
            finding.action(RecoveryActionType.RECONNECT_NETWORK, dependency.node().container(),
                    "Reconnect " + name + " to " + network + " with its service alias '" + reattach.get().alias()
                            + "' (requires the network-recovery policy and the container allowlist), then verify the application.");
            return Optional.of(finding.build());
        }
        return Optional.empty();
    }

    /** Log lines showing the application failing to reach the dependency, when present (supporting, not required). */
    private static List<LogExcerpt.LogSignal> connectionSignals(DiagnosticContext context, DependencyState dependency) {
        if (context.logs() == null || !context.logs().available()) return List.of();
        return context.logs().signals().stream()
                .filter(signal -> signal.kind() == LogExcerpt.LogSignal.Kind.DNS_FAILURE || signal.kind() == LogExcerpt.LogSignal.Kind.CONNECTION_REFUSED
                        || signal.kind() == LogExcerpt.LogSignal.Kind.TIMEOUT || signal.kind() == LogExcerpt.LogSignal.Kind.EXCEPTION)
                .filter(signal -> DependencyUnavailablePattern.mentions(signal, dependency) || signal.line().contains("connection attempt failed"))
                .toList();
    }
}
