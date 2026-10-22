package com.relayaegis.diagnostics;

import com.relayaegis.diagnostics.patterns.ApplicationHealthyPattern;
import com.relayaegis.diagnostics.patterns.ApplicationTimeoutPattern;
import com.relayaegis.diagnostics.patterns.ConnectionRefusedPattern;
import com.relayaegis.diagnostics.patterns.ContainerExitedPattern;
import com.relayaegis.diagnostics.patterns.CrashLoopPattern;
import com.relayaegis.diagnostics.patterns.DependencyCredentialsRejectedPattern;
import com.relayaegis.diagnostics.patterns.DependencyNetworkDetachedPattern;
import com.relayaegis.diagnostics.patterns.DependencyRecoveredPattern;
import com.relayaegis.diagnostics.patterns.DependencyUnavailablePattern;
import com.relayaegis.diagnostics.patterns.Http5xxPattern;
import com.relayaegis.diagnostics.patterns.NetworkFailurePatterns;
import com.relayaegis.diagnostics.patterns.OomPattern;
import com.relayaegis.diagnostics.patterns.PortListenerPattern;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * All known failure patterns. The built-in set is the single source of truth; additional patterns can be
 * contributed as Spring beans implementing {@link FailurePattern}.
 */
@Component
public class FailurePatternRegistry {
    private final List<FailurePattern> patterns;

    public FailurePatternRegistry() { this(List.of()); }

    @Autowired
    public FailurePatternRegistry(ObjectProvider<FailurePattern> contributed) {
        this(contributed.orderedStream().toList());
    }

    FailurePatternRegistry(List<FailurePattern> additional) {
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
                new DependencyUnavailablePattern(),
                new DependencyRecoveredPattern(),
                new DependencyNetworkDetachedPattern(),
                new DependencyCredentialsRejectedPattern(),
                new NetworkFailurePatterns.DnsFailurePattern(),
                new NetworkFailurePatterns.TcpConnectTimeoutPattern(),
                new NetworkFailurePatterns.TlsFailurePattern(),
                new NetworkFailurePatterns.UnexpectedStatusPattern());
    }

    public List<FailurePattern> all() { return patterns; }

    public List<String> ids() { return patterns.stream().map(FailurePattern::id).toList(); }
}
