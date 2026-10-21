package com.relayaegis.diagnostics;

import static com.relayaegis.diagnostics.DiagnosticFixtures.check;
import static com.relayaegis.diagnostics.DiagnosticFixtures.container;
import static com.relayaegis.diagnostics.DiagnosticFixtures.ctx;
import static com.relayaegis.diagnostics.DiagnosticFixtures.logs;
import static com.relayaegis.diagnostics.DiagnosticFixtures.network;
import static com.relayaegis.diagnostics.DiagnosticFixtures.signal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DependencyGraphService.DependencyNode;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.LogExcerpt.LogSignal.Kind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Level 1 scenarios for the Smart Business AI Assistant stack (sba-frontend → sba-backend → sba-postgres), built from
 * the evidence that stack really produces: Spring Boot's 503 when a health component is DOWN, Docker health checks, the
 * Compose depends_on label and the PostgreSQL/Hikari log messages. The container is evidence, not the diagnosis.
 */
class SbaRuntimeScenariosTest {
    private static final String BACKEND = "sba-backend";
    private static final String POSTGRES = "sba-postgres";
    private static final Instant NOW = DiagnosticFixtures.NOW;
    private final DiagnosticEngine engine = DiagnosticFixtures.engine();

    private static DependencyState postgres(ContainerSnapshot snapshot) {
        return new DependencyState(new DependencyNode("postgres", POSTGRES, "compose", true, true), snapshot);
    }
    private static ContainerSnapshot backend(String status, boolean running, Integer exit, String health) {
        return container(BACKEND, status, running, false, false, exit, 0, health, NOW.minusSeconds(3600), running ? null : NOW.minusSeconds(90));
    }
    private static ContainerSnapshot healthyPostgres() { return container(POSTGRES, "running", true, false, false, 0, 0, "healthy", NOW.minusSeconds(3600), null); }
    private static ContainerSnapshot stoppedPostgres() { return container(POSTGRES, "exited", false, false, false, 0, 0, null, NOW.minusSeconds(3600), NOW.minusSeconds(80)); }
    private static List<com.relayaegis.entity.HealthCheckEntity> down503() {
        return List.of(check("CHK-2", false, "degraded", 503, "HTTP 503", 0), check("CHK-1", false, "degraded", 503, "HTTP 503", 30));
    }
    private DiagnosticFinding top(DiagnosticFixtures.Ctx context) {
        return engine.diagnose(context.url("http://host.docker.internal:8081/actuator/health").build()).top().orElseThrow();
    }

    @Test
    void postgresDownWhileTheBackendRunsIsADependencyFailureNotABackendFailure() {
        // docker stop sba-postgres: the backend keeps running, its actuator reports DOWN (503), Docker marks it unhealthy.
        LogExcerpt log = logs(BACKEND, List.of(
                signal(Kind.CONNECTION_REFUSED, 0, "org.postgresql.util.PSQLException: Connection to postgres:5432 refused. Check that the hostname and port are correct", "postgres:5432"),
                signal(Kind.TIMEOUT, 1, "HikariPool-1 - Connection is not available, request timed out after 30000ms.", null)));
        DiagnosticFinding top = top(ctx().checks(down503()).runtime(backend("running", true, 0, "unhealthy")).logs(log).dependency(postgres(stoppedPostgres()))
                .network(network(true, "connected", null, null)));
        assertEquals("dependency-unavailable", top.patternId());
        assertEquals(RecoveryActionType.RESTART_DEPENDENCY, top.action());
        assertEquals(POSTGRES, top.actionTarget(), "recovers the dependency, never the healthy-but-blocked backend");
        assertTrue(top.confidence() >= 0.9, "the logs name the dependency");
    }

    @Test
    void aStoppedPostgresThatLeftDockerDnsIsStillTheDependencyFailure() {
        // Once a Compose service stops, its name leaves Docker DNS: the JDBC driver reports an unknown host, not a refused port.
        LogExcerpt log = logs(BACKEND, List.of(signal(Kind.DNS_FAILURE, 0, "java.net.UnknownHostException: postgres", null)));
        DiagnosticFinding top = top(ctx().checks(down503()).runtime(backend("running", true, 0, "unhealthy")).logs(log).dependency(postgres(stoppedPostgres())));
        assertEquals("dependency-unavailable", top.patternId());
        assertEquals(POSTGRES, top.actionTarget());
    }

    @Test
    void aStoppedBackendIsAnApplicationRuntimeFailureRecoveredInPlace() {
        // docker stop sba-backend (exit 143): the published port is gone, so the health check is refused.
        DiagnosticFinding top = top(ctx().checks(DiagnosticFixtures.refused()).runtime(backend("exited", false, 143, null)).dependency(postgres(healthyPostgres())));
        assertEquals("container-stopped", top.patternId(), "exit 143 is a stop (SIGTERM), distinguished from a crash");
        assertEquals(RecoveryActionType.START_CONTAINER, top.action());
        assertEquals(BACKEND, top.actionTarget());
    }

    @Test
    void anApplicationErrorInARunningBackendIsNotBlamedOnTheContainerOrTheDatabase() {
        // The process is up and its dependency healthy, but requests fail inside the application.
        LogExcerpt log = logs(BACKEND, List.of(signal(Kind.EXCEPTION, 0, "ERROR 1 --- [nio-8080-exec-3] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() threw exception java.lang.IllegalStateException", null)));
        DiagnosticFinding top = top(ctx().checks(List.of(check("CHK-2", false, "degraded", 500, "HTTP 500", 0), check("CHK-1", false, "degraded", 500, "HTTP 500", 30)))
                .runtime(backend("running", true, 0, "healthy")).logs(log).dependency(postgres(healthyPostgres())).network(network(true, "connected", null, null)));
        assertNotEquals("container-stopped", top.patternId());
        assertNotEquals("container-exited", top.patternId());
        assertNotEquals("dependency-unavailable", top.patternId());
        assertEquals("application", top.category(), "an application-level failure while the container is alive");
    }

    @Test
    void aBackendThatRecoveredOnItsOwnNeedsNoRepair() {
        // A transient failure: Docker's unless-stopped policy restarted the process and checks pass again.
        DiagnosticResult result = engine.diagnose(ctx().checks(DiagnosticFixtures.healthy()).runtime(backend("running", true, 0, "healthy"))
                .dependency(postgres(healthyPostgres())).url("http://host.docker.internal:8081/actuator/health").build());
        DiagnosticFinding top = result.top().orElseThrow();
        assertFalse(top.action().mutates(), "nothing is restarted when the system already recovered");
    }

    private static ContainerSnapshot compose(String name, String service, java.util.List<String> networks) {
        return new ContainerSnapshot(name, true, "running", true, false, false, null, 0, NOW.minusSeconds(7200), null, "healthy", null, null, 1.0, null, 5.0,
                java.util.List.of(), java.util.List.of(), networks, "img", "unless-stopped",
                java.util.Map.of("com.docker.compose.project", "smart-business-ai", "com.docker.compose.service", service), NOW);
    }

    @Test
    void aHealthyPostgresCutOffTheBackendNetworkIsANetworkPartitionNotARestart() {
        // docker network disconnect smart-business-ai_default sba-postgres: both containers healthy, health checks hang.
        LogExcerpt log = logs(BACKEND, List.of(
                signal(Kind.EXCEPTION, 0, "Caused by: org.postgresql.util.PSQLException: The connection attempt failed.", null),
                signal(Kind.TIMEOUT, 1, "HikariPool-1 - Connection is not available, request timed out after 30038ms (total=0, active=0, idle=0, waiting=2)", null)));
        DiagnosticResult result = engine.diagnose(ctx().checks(DiagnosticFixtures.timeouts())
                .runtime(compose(BACKEND, "backend", List.of("smart-business-ai_default"))).logs(log)
                .dependency(postgres(compose(POSTGRES, "postgres", List.of()))).network(network(true, "connected", null, null))
                .url("http://host.docker.internal:8081/actuator/health").build());
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("dependency-network-detached", top.patternId());
        assertEquals(RecoveryActionType.RECONNECT_NETWORK, top.action());
        assertEquals(POSTGRES, top.actionTarget());
        assertTrue(top.actionText().contains("smart-business-ai_default") && top.actionText().contains("'postgres'"));
        assertTrue(result.ranked().stream().anyMatch(f -> f.action() == RecoveryActionType.RESTART_CONTAINER && f.confidence() < top.confidence()),
                "the restart-the-backend hypothesis is still listed, but ranked below the partition");
    }

    @Test
    void aDependencyStillOnTheSharedNetworkIsNotCalledAPartition() {
        DiagnosticFinding top = top(ctx().checks(DiagnosticFixtures.timeouts()).runtime(compose(BACKEND, "backend", List.of("smart-business-ai_default")))
                .dependency(postgres(compose(POSTGRES, "postgres", List.of("smart-business-ai_default")))).network(network(true, "connected", null, null)));
        assertNotEquals("dependency-network-detached", top.patternId());
    }
}
