package com.relayaegis.diagnostics;

import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DependencyGraphService.DependencyNode;
import com.relayaegis.runtime.LogExcerpt;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything RELAY AEGIS observed about one incident, from every evidence source. Built by
 * {@link EvidenceCorrelationEngine#collect}; immutable; consumed by {@link FailurePattern}s.
 * Unavailable sources are listed in {@code unavailable} so findings can name missing evidence honestly.
 */
public record DiagnosticContext(
        String incidentId,
        String component,
        Instant detectedAt,
        String applicationId,
        String applicationUrl,
        String environment,
        String appContainer,
        List<HealthCheckEntity> recentChecks,
        List<EvidenceItemDto> incidentEvidence,
        ContainerSnapshot runtime,
        LogExcerpt logs,
        List<DependencyState> dependencies,
        NetworkObservation network,
        List<CodeSignal> code,
        History history,
        List<String> unavailable) {

    /** A dependency with its observed runtime state (null snapshot when it could not be inspected). */
    public record DependencyState(DependencyNode node, ContainerSnapshot snapshot) {
        public boolean observedUnhealthy() { return snapshot != null && (!snapshot.exists() || !snapshot.healthy()); }
        public boolean observedHealthy() { return snapshot != null && snapshot.exists() && snapshot.healthy(); }
    }

    /** DNS/TCP/TLS observation of the monitored URL, made from the RELAY host. */
    public record NetworkObservation(String host, int port, boolean dnsResolved, String dnsError, int addressCount,
                                     String tcp, Long tcpMs, String tls, String tlsDetail, Instant observedAt) {
        public boolean tcpRefused() { return "refused".equals(tcp); }
        public boolean tcpTimedOut() { return "timeout".equals(tcp); }
        public boolean tcpConnected() { return "connected".equals(tcp); }
    }

    /** A static-analysis finding for the application's linked repository (unverified, supporting only). */
    public record CodeSignal(String findingId, String category, String title, String file, Integer line, String severity) {}

    public record History(int incidentsLast24h, int failedRecoveriesLast24h, int verifiedRecoveriesLast24h) {
        public static History none() { return new History(0, 0, 0); }
    }

    // ---- Health-check helpers --------------------------------------------------------------------------------

    public Optional<HealthCheckEntity> latestCheck() { return recentChecks.stream().findFirst(); }

    public boolean currentlyHealthy() { return latestCheck().map(check -> check.healthy).orElse(false); }

    public boolean failing() { return latestCheck().map(check -> !check.healthy).orElse(false); }

    /** Consecutive failed checks at the head of the history. */
    public List<HealthCheckEntity> failureStreak() {
        List<HealthCheckEntity> streak = new ArrayList<>();
        for (HealthCheckEntity check : recentChecks) { if (check.healthy) break; streak.add(check); }
        return streak;
    }

    /** How many of the newest failures a signature must match; older ones may describe the transition into the failure. */
    public static final int SIGNATURE_WINDOW = 2;

    /**
     * True when the newest failures (up to {@link #SIGNATURE_WINDOW}) all match. A stopping container is first refused
     * and then times out, so judging the whole streak would hide the current signature behind the transition.
     */
    public boolean streakAll(java.util.function.Predicate<HealthCheckEntity> predicate) {
        List<HealthCheckEntity> recent = failureStreak().stream().limit(SIGNATURE_WINDOW).toList();
        return !recent.isEmpty() && recent.stream().allMatch(predicate);
    }

    public static String outcome(HealthCheckEntity check) {
        return check.outcome != null ? check.outcome : check.healthy ? "healthy" : "failed";
    }

    public static boolean isRefused(HealthCheckEntity check) {
        String message = check.errorMessage == null ? "" : check.errorMessage.toLowerCase(Locale.ROOT);
        return "unreachable".equals(outcome(check)) && (message.contains("refused") || message.contains("could not be established"));
    }

    /** The HTTP request was sent (or the connection was open) but no response arrived in time. Not a TCP connect timeout. */
    public static boolean isTimeout(HealthCheckEntity check) {
        String message = check.errorMessage == null ? "" : check.errorMessage.toLowerCase(Locale.ROOT);
        return "unreachable".equals(outcome(check)) && !isConnectTimeout(check)
                && (message.contains("no response within") || message.contains("timed out") || message.contains("deadline"));
    }

    /** No TCP connection could be established before the deadline (host down, address gone, or traffic dropped). */
    public static boolean isConnectTimeout(HealthCheckEntity check) {
        String message = check.errorMessage == null ? "" : check.errorMessage.toLowerCase(Locale.ROOT);
        return "unreachable".equals(outcome(check)) && message.contains("tcp connect timed out");
    }

    public static boolean isDnsFailure(HealthCheckEntity check) {
        String message = check.errorMessage == null ? "" : check.errorMessage.toLowerCase(Locale.ROOT);
        return "unreachable".equals(outcome(check)) && (message.contains("could not be resolved") || message.contains("unknownhost"));
    }

    public static boolean isTlsFailure(HealthCheckEntity check) {
        String message = check.errorMessage == null ? "" : check.errorMessage.toLowerCase(Locale.ROOT);
        return "unreachable".equals(outcome(check)) && (message.contains("ssl") || message.contains("tls") || message.contains("certificate") || message.contains("handshake"));
    }

    public static boolean isServerError(HealthCheckEntity check) {
        return "degraded".equals(outcome(check)) && check.statusCode != null && check.statusCode >= 500;
    }

    // ---- Evidence references (one id scheme shared by all patterns) -------------------------------------------

    public static EvidenceRef checkRef(HealthCheckEntity check) {
        String detail = outcome(check) + (check.statusCode == null ? "" : " (HTTP " + check.statusCode + ")")
                + (check.errorMessage == null ? "" : " - " + check.errorMessage);
        return new EvidenceRef(check.id, "health_check", "Health check " + detail);
    }

    public List<EvidenceRef> streakRefs(int max) {
        return failureStreak().stream().limit(max).map(DiagnosticContext::checkRef).toList();
    }

    public EvidenceRef runtimeRef() {
        return runtime == null ? null : new EvidenceRef("RT-" + runtime.name(), "runtime", "Container " + runtime.name() + ": " + runtime.describe());
    }

    public static EvidenceRef logRef(LogExcerpt logs, LogExcerpt.LogSignal signal) {
        return new EvidenceRef("LOG-" + logs.container() + "-" + signal.lineIndex(), "logs",
                "Log line (" + signal.kind().name().toLowerCase(Locale.ROOT) + "): " + signal.line());
    }

    public static EvidenceRef dependencyRef(DependencyState dependency) {
        String state = dependency.snapshot() == null ? "not inspectable" : dependency.snapshot().describe();
        return new EvidenceRef("DEP-" + dependency.node().name(), "dependency",
                "Dependency " + dependency.node().name() + " (" + dependency.node().source() + "): " + state);
    }

    public EvidenceRef networkRef() {
        if (network == null) return null;
        String text = !network.dnsResolved() ? "DNS resolution of " + network.host() + " failed" + (network.dnsError() == null ? "" : " (" + network.dnsError() + ")")
                : "TCP " + network.host() + ":" + network.port() + " " + network.tcp() + (network.tcpMs() == null ? "" : " in " + network.tcpMs() + " ms")
                + (network.tls() == null ? "" : "; TLS " + network.tls() + (network.tlsDetail() == null ? "" : " (" + network.tlsDetail() + ")"));
        return new EvidenceRef("NET-" + network.host(), "network", text);
    }

    public static EvidenceRef codeRef(CodeSignal signal) {
        return new EvidenceRef("CODE-" + signal.findingId(), "code", "Static analysis (unverified): " + signal.title()
                + (signal.file() == null ? "" : " at " + signal.file() + (signal.line() == null ? "" : ":" + signal.line())));
    }

    public EvidenceRef historyRef() {
        return new EvidenceRef("HIST-" + (applicationId == null ? "app" : applicationId), "history",
                history.incidentsLast24h() + " incident(s) in the last 24 h; " + history.failedRecoveriesLast24h() + " failed and "
                        + history.verifiedRecoveriesLast24h() + " verified recoveries");
    }

    public boolean hasAnyEvidence() {
        return !recentChecks.isEmpty() || !incidentEvidence.isEmpty() || runtime != null || (logs != null && logs.available())
                || network != null || dependencies.stream().anyMatch(d -> d.snapshot() != null);
    }

    public boolean hasRuntime() { return runtime != null && runtime.exists(); }
}
