package com.relayaegis.diagnostics;

import com.relayaegis.diagnostics.patterns.ApplicationHealthyPattern;
import com.relayaegis.diagnostics.patterns.ApplicationTimeoutPattern;
import com.relayaegis.diagnostics.patterns.BadDeployPattern;
import com.relayaegis.diagnostics.patterns.ConnectionRefusedPattern;
import com.relayaegis.diagnostics.patterns.ContainerExitedPattern;
import com.relayaegis.diagnostics.patterns.CrashLoopPattern;
import com.relayaegis.diagnostics.patterns.DependencyCredentialsRejectedPattern;
import com.relayaegis.diagnostics.patterns.DependencyNetworkDetachedPattern;
import com.relayaegis.diagnostics.patterns.DependencyRecoveredPattern;
import com.relayaegis.diagnostics.patterns.KubernetesWorkloadPattern;
import com.relayaegis.diagnostics.patterns.DependencyUnavailablePattern;
import com.relayaegis.diagnostics.patterns.Http5xxPattern;
import com.relayaegis.diagnostics.patterns.NetworkFailurePatterns;
import com.relayaegis.diagnostics.patterns.OomPattern;
import com.relayaegis.diagnostics.patterns.PortListenerPattern;
import com.relayaegis.diagnostics.patterns.UserJourneyPattern;
import com.relayaegis.diagnostics.runbook.RunbookCatalog;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * All known failure patterns: the built-in set, patterns contributed as Spring beans implementing
 * {@link FailurePattern}, and the YAML runbooks currently loaded by {@link RunbookCatalog} (read on every call, so a
 * reload takes effect at once).
 */
@Component
public class FailurePatternRegistry {
    private final List<FailurePattern> patterns;
    private final RunbookCatalog runbooks;

    public FailurePatternRegistry() { this(List.of()); }

    @Autowired
    public FailurePatternRegistry(ObjectProvider<FailurePattern> contributed, ObjectProvider<RunbookCatalog> runbooks) {
        this(contributed.orderedStream().toList(), runbooks.getIfAvailable());
    }

    FailurePatternRegistry(List<FailurePattern> additional) { this(additional, null); }

    public FailurePatternRegistry(List<FailurePattern> additional, RunbookCatalog runbooks) {
        this.runbooks = runbooks;
        List<FailurePattern> all = new ArrayList<>(builtIns());
        additional.stream().filter(extra -> all.stream().noneMatch(existing -> existing.id().equals(extra.id()))).forEach(all::add);
        this.patterns = List.copyOf(all);
    }

    public static List<FailurePattern> builtIns() {
        return List.of(
                new ApplicationHealthyPattern(),
                new ContainerExitedPattern(),
                new CrashLoopPattern(),
                new OomPattern(),
                new PortListenerPattern(),
                new ApplicationTimeoutPattern(),
                new ConnectionRefusedPattern(),
                new Http5xxPattern(),
                new UserJourneyPattern(),
                new BadDeployPattern(),
                new DependencyUnavailablePattern(),
                new DependencyRecoveredPattern(),
                new DependencyNetworkDetachedPattern(),
                new DependencyCredentialsRejectedPattern(),
                new KubernetesWorkloadPattern(),
                new NetworkFailurePatterns.DnsFailurePattern(),
                new NetworkFailurePatterns.TcpConnectTimeoutPattern(),
                new NetworkFailurePatterns.TlsFailurePattern(),
                new NetworkFailurePatterns.UnexpectedStatusPattern());
    }

    public List<FailurePattern> all() {
        if (runbooks == null || runbooks.runbooks().isEmpty()) return patterns;
        List<FailurePattern> all = new ArrayList<>(patterns);
        runbooks.runbooks().stream().filter(r -> patterns.stream().noneMatch(p -> p.id().equals(r.id()))).forEach(all::add);
        return List.copyOf(all);
    }

    public List<String> ids() { return all().stream().map(FailurePattern::id).toList(); }
}
