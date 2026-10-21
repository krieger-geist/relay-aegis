package com.relayaegis.diagnostics;

import com.relayaegis.diagnostics.DiagnosticContext.CodeSignal;
import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.diagnostics.DiagnosticContext.History;
import com.relayaegis.diagnostics.DiagnosticContext.NetworkObservation;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DependencyGraphService.DependencyNode;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.LogExcerpt.LogSignal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builders for realistic diagnostic contexts (values shaped like real docker inspect / health-check records). */
public final class DiagnosticFixtures {
    public static final String APP = "relay-aegis-test-target";
    public static final Instant NOW = Instant.parse("2026-10-16T05:00:00Z");

    private DiagnosticFixtures() {}

    public static HealthCheckEntity check(String id, boolean healthy, String outcome, Integer status, String error, int secondsAgo) {
        HealthCheckEntity check = new HealthCheckEntity();
        check.id = id; check.applicationId = "app-1"; check.healthy = healthy; check.outcome = outcome; check.statusCode = status;
        check.errorMessage = error; check.responseMs = 12L; check.provenance = "live"; check.checkedAt = NOW.minusSeconds(secondsAgo);
        return check;
    }

    public static List<HealthCheckEntity> refused() {
        return List.of(check("CHK-2", false, "unreachable", null, "Connection refused or could not be established.", 0),
                check("CHK-1", false, "unreachable", null, "Connection refused or could not be established.", 60));
    }

    public static List<HealthCheckEntity> timeouts() {
        return List.of(check("CHK-2", false, "unreachable", null, "No response within 2500 ms.", 0),
                check("CHK-1", false, "unreachable", null, "No response within 2500 ms.", 60));
    }

    public static List<HealthCheckEntity> serverErrors(int status) {
        return List.of(check("CHK-2", false, "degraded", status, "HTTP " + status, 0), check("CHK-1", false, "degraded", status, "HTTP " + status, 60));
    }

    public static List<HealthCheckEntity> healthy() {
        return List.of(check("CHK-3", true, "healthy", 200, null, 0), check("CHK-2", true, "healthy", 200, null, 60));
    }

    public static ContainerSnapshot container(String name, String status, boolean running, boolean restarting, boolean oom, Integer exit,
                                              int restarts, String health, Instant startedAt, Instant finishedAt) {
        return new ContainerSnapshot(name, true, status, running, restarting, oom, exit, restarts, startedAt, finishedAt, health, null,
                268_435_456L, running ? 3.5 : null, running ? 50_000_000L : null, running ? 18.6 : null,
                List.of("8080/tcp->0.0.0.0:8080"), List.of("8080/tcp"), List.of("relay-aegis_default"), "relay-aegis-test-target:latest", "no",
                Map.of("com.docker.compose.project", "relay-aegis", "com.docker.compose.service", "test-target"), NOW);
    }

    public static ContainerSnapshot running(String name) {
        return container(name, "running", true, false, false, 0, 0, null, NOW.minusSeconds(3600), null);
    }

    public static ContainerSnapshot exited(String name, int exitCode) {
        return container(name, "exited", false, false, false, exitCode, 0, null, NOW.minusSeconds(3600), NOW.minusSeconds(120));
    }

    public static LogExcerpt logs(String container, List<LogSignal> signals) {
        List<String> lines = new ArrayList<>();
        signals.forEach(signal -> lines.add(signal.line()));
        return new LogExcerpt(container, true, null, List.copyOf(lines), List.copyOf(signals));
    }

    public static LogSignal signal(LogSignal.Kind kind, int index, String line, String target) {
        return new LogSignal(kind, index, line, target);
    }

    public static DependencyState dependency(String name, ContainerSnapshot snapshot) {
        return new DependencyState(new DependencyNode(name, name, "configured", true, false), snapshot);
    }

    public static NetworkObservation network(boolean dns, String tcp, String tls, String tlsDetail) {
        return new NetworkObservation("test-target", 8080, dns, dns ? null : "UnknownHostException", dns ? 1 : 0, dns ? tcp : "skipped",
                dns ? 2L : null, tls, tlsDetail, NOW);
    }

    /** Fluent context builder; defaults to a monitored URL app with a linked container and no other evidence. */
    public static final class Ctx {
        private List<HealthCheckEntity> checks = List.of();
        private ContainerSnapshot runtime;
        private LogExcerpt logs;
        private final List<DependencyState> deps = new ArrayList<>();
        private NetworkObservation network;
        private final List<CodeSignal> code = new ArrayList<>();
        private History history = History.none();
        private String url = "http://test-target:8080/health";
        private String container = APP;

        public Ctx checks(List<HealthCheckEntity> value) { checks = value; return this; }
        public Ctx runtime(ContainerSnapshot value) { runtime = value; return this; }
        public Ctx logs(LogExcerpt value) { logs = value; return this; }
        public Ctx dependency(DependencyState value) { deps.add(value); return this; }
        public Ctx network(NetworkObservation value) { network = value; return this; }
        public Ctx code(CodeSignal value) { code.add(value); return this; }
        public Ctx history(History value) { history = value; return this; }
        public Ctx url(String value) { url = value; return this; }
        public Ctx noContainer() { container = null; return this; }

        public DiagnosticContext build() {
            return new DiagnosticContext("INC-1", container == null ? "url-health-check" : "docker:" + container, NOW, "app-1", url, "Development",
                    container, checks, List.of(), runtime, logs, List.copyOf(deps), network, List.copyOf(code), history, List.of());
        }
    }

    public static Ctx ctx() { return new Ctx(); }

    /** The real engine with every built-in pattern; correlation needs no repositories. */
    public static DiagnosticEngine engine() {
        EvidenceCorrelationEngine correlation = new EvidenceCorrelationEngine(null, null, null, null, null, null, null, null, null, null);
        return new DiagnosticEngine(new FailurePatternRegistry(), correlation);
    }
}
