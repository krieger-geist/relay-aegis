package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import java.util.Optional;

/** External-path failures that no container restart can fix: DNS, TLS, and unexpected non-5xx statuses. */
public final class NetworkFailurePatterns {
    private NetworkFailurePatterns() {}

    public static class DnsFailurePattern implements FailurePattern {
        public String id() { return "dns-failure"; }

        public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
            boolean probe = context.network() != null && !context.network().dnsResolved();
            if (!context.failing() || !(probe || context.streakAll(DiagnosticContext::isDnsFailure))) return Optional.empty();
            String host = host(context);
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Host name does not resolve", "network");
            if (context.hasRuntime() && !context.runtime().running()) {
                // Docker's embedded DNS drops the name of a stopped container: here the DNS failure is a symptom.
                finding.cause("The host name " + host + " does not resolve because the container " + context.runtime().name() + " is not running.")
                        .explanation("Docker's embedded DNS only resolves names of running containers, so the stopped container explains the DNS failure.")
                        .confidence(0.35)
                        .contradict("Container " + context.runtime().name() + " is " + context.runtime().status() + "; its DNS name disappears while it is stopped")
                        .action(RecoveryActionType.ESCALATE, null, "Recover the stopped container; the DNS name returns when it runs.");
                PatternSupport.citeRuntime(finding, context);
            } else if (isSingleLabel(host)) {
                finding.cause("The host name " + host + " does not resolve from the RELAY host.")
                        .explanation("Single-label names are usually Docker service or container names, which stop resolving when the container is stopped "
                                + "or removed. Without runtime evidence RELAY cannot tell a stopped container from a wrong name.")
                        .confidence(0.7)
                        .missing("State of the container or service named " + host + " (link it to this application or add it to RELAY_DOCKER_INSPECT_CONTAINERS)")
                        .action(RecoveryActionType.ESCALATE, null, "Check whether the container or service " + host + " is running; otherwise fix the host name or network.");
            } else {
                finding.cause("The monitored host name cannot be resolved by DNS from the RELAY host.")
                        .explanation("Without an IP address no connection can be attempted. This is a naming or network configuration problem, not an application crash.")
                        .confidence(0.95)
                        .action(RecoveryActionType.ESCALATE, null, "Fix the host name in the registered URL or the DNS/network configuration.");
            }
            PatternSupport.citeFailures(finding, context);
            if (context.network() != null) finding.support(context.networkRef());
            return Optional.of(finding.build());
        }

        private static String host(DiagnosticContext context) {
            if (context.network() != null && context.network().host() != null) return context.network().host();
            try { return java.net.URI.create(context.applicationUrl()).getHost(); } catch (Exception ex) { return "the monitored host"; }
        }

        static boolean isSingleLabel(String host) {
            return host != null && !host.contains(".") && !host.contains(":") && !"localhost".equalsIgnoreCase(host);
        }
    }

    /**
     * TCP connect timeout: the address answered nothing at all (neither accept nor refuse). Distinct from a refused
     * connection (host up, port closed) and from a response timeout (connected, HTTP never answered).
     */
    public static class TcpConnectTimeoutPattern implements FailurePattern {
        public String id() { return "tcp-connect-timeout"; }

        public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
            boolean probe = context.network() != null && context.network().dnsResolved() && context.network().tcpTimedOut();
            if (!context.failing() || !(probe || context.streakAll(DiagnosticContext::isConnectTimeout))) return Optional.empty();
            String target = PatternSupport.hostPort(context);
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "TCP connection timed out", "network");
            if (context.hasRuntime() && !context.runtime().running()) {
                finding.cause("No TCP connection to " + target + " could be established because the container " + context.runtime().name() + " is not running.")
                        .explanation("A stopped container's network address disappears; connections to it are dropped until it runs again.")
                        .confidence(0.35)
                        .contradict("Container " + context.runtime().name() + " is " + context.runtime().status() + "; its network address is gone while it is stopped")
                        .action(RecoveryActionType.ESCALATE, null, "Recover the stopped container.");
                PatternSupport.citeRuntime(finding, context);
            } else {
                finding.cause("No TCP connection to " + target + " could be established before the deadline.")
                        .explanation("Connection attempts were neither accepted nor refused: the host is down, its address no longer exists, or a firewall "
                                + "or routing problem drops the traffic. This is observed from the RELAY host only.")
                        .confidence(0.65)
                        .action(RecoveryActionType.INVESTIGATE, null, "Check that the host (or container) behind " + target + " is up and reachable from the RELAY network.");
                PatternSupport.requireRuntimeOrSayMissing(finding, context);
            }
            PatternSupport.citeFailures(finding, context);
            if (context.network() != null) finding.support(context.networkRef());
            return Optional.of(finding.build());
        }
    }

    public static class TlsFailurePattern implements FailurePattern {
        public String id() { return "tls-failure"; }

        public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
            boolean probe = context.network() != null && "failed".equals(context.network().tls());
            if (!context.failing() || !(probe || context.streakAll(DiagnosticContext::isTlsFailure))) return Optional.empty();
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "TLS handshake failure", "network")
                    .cause("The TLS handshake with the monitored endpoint fails" + (probe && context.network().tlsDetail() != null ? " (" + context.network().tlsDetail() + ")" : "") + ".")
                    .explanation("The server is reachable but the secure connection cannot be established: an expired, untrusted or mismatched certificate, or an incompatible TLS configuration.")
                    .confidence(0.9)
                    .action(RecoveryActionType.ESCALATE, null, "Renew or correct the certificate, or fix the TLS configuration; a restart does not change certificates.");
            PatternSupport.citeFailures(finding, context);
            if (context.network() != null) finding.support(context.networkRef());
            return Optional.of(finding.build());
        }
    }

    public static class UnexpectedStatusPattern implements FailurePattern {
        public String id() { return "http-unexpected-status"; }

        public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
            HealthCheckEntity latest = context.latestCheck().orElse(null);
            if (latest == null || latest.healthy || !"degraded".equals(DiagnosticContext.outcome(latest))
                    || latest.statusCode == null || latest.statusCode >= 500) return Optional.empty();
            boolean redirect = latest.statusCode >= 300 && latest.statusCode < 400;
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Unexpected HTTP " + latest.statusCode, "configuration")
                    .cause("The health endpoint answers with HTTP " + latest.statusCode + " instead of a success status.")
                    .explanation(redirect ? "The endpoint redirects; RELAY does not follow redirects, so the registered URL probably points to the wrong path or scheme."
                            : "A client-error status usually means the registered URL, method or authentication does not match what the application expects.")
                    .confidence(redirect ? 0.7 : 0.62)
                    .action(RecoveryActionType.ESCALATE, null, "Correct the registered health URL or the endpoint's access rules; a restart would not change the response.");
            PatternSupport.citeFailures(finding, context);
            return Optional.of(finding.build());
        }
    }
}
