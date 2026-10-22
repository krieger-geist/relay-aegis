package com.relayaegis.diagnostics;

import static com.relayaegis.diagnostics.DiagnosticFixtures.check;
import static com.relayaegis.diagnostics.DiagnosticFixtures.container;
import static com.relayaegis.diagnostics.DiagnosticFixtures.ctx;
import static com.relayaegis.diagnostics.DiagnosticFixtures.logs;
import static com.relayaegis.diagnostics.DiagnosticFixtures.signal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticContext.CodeSignal;
import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.diagnostics.patterns.DependencyCredentialsRejectedPattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DependencyGraphService.DependencyNode;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.LogExcerpt.LogSignal.Kind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The orders-service demonstration: the database password was rotated while the service still uses a hard-coded one.
 * Evidence is what that stack really produces: Spring Boot's 503 with the db component DOWN, and the PostgreSQL driver's
 * "password authentication failed" line. The database itself is up.
 */
class CredentialsRejectedScenarioTest {
    private static final Instant NOW = DiagnosticFixtures.NOW;
    private final DiagnosticEngine engine = DiagnosticFixtures.engine();

    private static final String AUTH_LINE = "Caused by: org.postgresql.util.PSQLException: FATAL: password authentication failed for user \"orders\"";
    private static final String POOL_LINE = "Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 3001ms";

    private static ContainerSnapshot service() {
        return container("orders-service", "running", true, false, false, 0, 0, null, NOW.minusSeconds(600), null);
    }

    private static DependencyState postgres() {
        return new DependencyState(new DependencyNode("orders-postgres", "orders-postgres", "compose", true, true),
                container("orders-postgres", "running", true, false, false, 0, 0, "healthy", NOW.minusSeconds(3600), null));
    }

    private static List<com.relayaegis.entity.HealthCheckEntity> down503() {
        return List.of(check("CHK-2", false, "degraded", 503, "HTTP 503", 0), check("CHK-1", false, "degraded", 503, "HTTP 503", 30));
    }

    private static LogExcerpt authLogs() {
        return logs("orders-service", List.of(signal(Kind.TIMEOUT, 0, POOL_LINE, null), signal(Kind.FATAL, 1, AUTH_LINE, null),
                signal(Kind.AUTH_FAILURE, 1, AUTH_LINE, null)));
    }

    private DiagnosticResult diagnose(DiagnosticFixtures.Ctx context) {
        return engine.diagnose(context.url("http://host.docker.internal:8082/actuator/health").build());
    }

    @Test
    void rejectedCredentialsRankFirstAndOfferNoRuntimeAction() {
        DiagnosticFinding top = diagnose(ctx().checks(down503()).runtime(service()).logs(authLogs()).dependency(postgres())).top().orElseThrow();
        assertEquals(DependencyCredentialsRejectedPattern.ID, top.patternId());
        assertEquals(RecoveryActionType.INVESTIGATE, top.action(), "a restart would reuse the rejected credential");
        assertTrue(top.cause().contains("for user orders"));
        assertTrue(top.confidence() >= 0.9);
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.summary().contains("password authentication failed")));
        assertTrue(top.missing().stream().anyMatch(m -> m.contains("credential")));
    }

    @Test
    void aHardCodedCredentialInTheLinkedRepositoryIsCitedAsUnverifiedCodeEvidence() {
        CodeSignal credential = new CodeSignal("F-db", "secret-hygiene", "Possible hard-coded credential", "src/main/java/com/example/orders/config/DatabaseConfig.java", 19, "medium");
        CodeSignal unrelated = new CodeSignal("F-sql", "database-safety", "Possible SQL query concatenation", "src/Dao.java", 4, "medium");
        DiagnosticFinding top = diagnose(ctx().checks(down503()).runtime(service()).logs(authLogs()).dependency(postgres()).code(credential).code(unrelated)).top().orElseThrow();
        assertEquals(DependencyCredentialsRejectedPattern.ID, top.patternId());
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.id().equals("CODE-F-db") && ref.summary().contains("DatabaseConfig.java:19")));
        assertFalse(top.supporting().stream().anyMatch(ref -> ref.id().equals("CODE-F-sql")));
    }

    @Test
    void anAuthenticationLineWithoutAFailingApplicationIsNotADiagnosis() {
        var healthy = List.of(check("CHK-2", true, "healthy", 200, null, 0), check("CHK-1", true, "healthy", 200, null, 30));
        DiagnosticResult result = diagnose(ctx().checks(healthy).runtime(service()).logs(authLogs()).dependency(postgres()));
        assertTrue(result.ranked().stream().noneMatch(f -> f.patternId().equals(DependencyCredentialsRejectedPattern.ID)));
    }

    @Test
    void aFailingApplicationWithoutAuthenticationLinesIsDiagnosedAsBefore() {
        LogExcerpt poolOnly = logs("orders-service", List.of(signal(Kind.TIMEOUT, 0, POOL_LINE, null)));
        DiagnosticResult result = diagnose(ctx().checks(down503()).runtime(service()).logs(poolOnly).dependency(postgres()));
        assertTrue(result.ranked().stream().noneMatch(f -> f.patternId().equals(DependencyCredentialsRejectedPattern.ID)));
    }
}
