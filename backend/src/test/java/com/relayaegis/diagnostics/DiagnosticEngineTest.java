package com.relayaegis.diagnostics;

import static com.relayaegis.diagnostics.DiagnosticFixtures.APP;
import static com.relayaegis.diagnostics.DiagnosticFixtures.NOW;
import static com.relayaegis.diagnostics.DiagnosticFixtures.container;
import static com.relayaegis.diagnostics.DiagnosticFixtures.ctx;
import static com.relayaegis.diagnostics.DiagnosticFixtures.dependency;
import static com.relayaegis.diagnostics.DiagnosticFixtures.exited;
import static com.relayaegis.diagnostics.DiagnosticFixtures.logs;
import static com.relayaegis.diagnostics.DiagnosticFixtures.network;
import static com.relayaegis.diagnostics.DiagnosticFixtures.refused;
import static com.relayaegis.diagnostics.DiagnosticFixtures.running;
import static com.relayaegis.diagnostics.DiagnosticFixtures.signal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticContext.CodeSignal;
import com.relayaegis.diagnostics.DiagnosticContext.History;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.LogExcerpt.LogSignal.Kind;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticEngineTest {
    private final DiagnosticEngine engine = DiagnosticFixtures.engine();

    private DiagnosticResult diagnose(DiagnosticFixtures.Ctx context) { return engine.diagnose(context.build()); }

    private static void assertEveryFindingCitesEvidence(DiagnosticResult result) {
        result.ranked().forEach(f -> assertFalse(f.supporting().isEmpty(), f.patternId() + " must cite evidence"));
        for (int i = 1; i < result.ranked().size(); i++) {
            assertTrue(result.ranked().get(i - 1).confidence() >= result.ranked().get(i).confidence(), "ranked by confidence");
        }
    }

    @Test
    void healthyApplicationNeedsNoActionAndNoAi() {
        DiagnosticResult result = diagnose(ctx().checks(DiagnosticFixtures.healthy()).runtime(running(APP)));
        assertEquals("application-healthy", result.top().orElseThrow().patternId());
        assertEquals(RecoveryActionType.NONE, result.top().orElseThrow().action());
        assertFalse(result.escalationRequired());
    }

    @Test
    void stoppedContainerIsStartedWithoutAi() {
        DiagnosticResult result = diagnose(ctx().checks(refused()).runtime(exited(APP, 0)));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("container-stopped", top.patternId());
        assertEquals(RecoveryActionType.START_CONTAINER, top.action());
        assertEquals(APP, top.actionTarget());
        assertTrue(top.confidence() >= AiEscalationPolicy.CONFIDENCE_THRESHOLD);
        assertFalse(result.escalationRequired(), result.escalationReason());
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.source().equals("runtime")));
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.source().equals("health_check")));
        assertEveryFindingCitesEvidence(result);
    }

    @Test
    void crashedContainerCitesItsFatalLogLine() {
        LogExcerpt logs = logs(APP, List.of(signal(Kind.FATAL, 0, "FATAL: unhandled exception in request loop", null)));
        DiagnosticResult result = diagnose(ctx().checks(refused()).runtime(exited(APP, 1)).logs(logs));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("container-stopped", top.patternId().startsWith("container-") ? "container-stopped" : top.patternId());
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.source().equals("logs")));
    }

    @Test
    void crashLoopEscalatesInsteadOfRestartingAgain() {
        ContainerSnapshot loop = container(APP, "restarting", false, true, false, 1, 7, null, NOW.minusSeconds(5), NOW.minusSeconds(8));
        DiagnosticResult result = diagnose(ctx().checks(refused()).runtime(loop));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("crash-loop", top.patternId());
        assertEquals(RecoveryActionType.ESCALATE, top.action());
        assertFalse(top.action().mutates());
    }

    @Test
    void oomKilledContainerIsDiagnosedFromTheRuntimeFlagAndLogs() {
        ContainerSnapshot oom = container(APP, "exited", false, false, true, 137, 0, null, NOW.minusSeconds(600), NOW.minusSeconds(30));
        LogExcerpt logs = logs(APP, List.of(signal(Kind.OOM, 0, "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory", null)));
        DiagnosticResult result = diagnose(ctx().checks(refused()).runtime(oom).logs(logs));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("oom-killed", top.patternId());
        assertEquals(RecoveryActionType.START_CONTAINER, top.action());
        assertTrue(top.confidence() >= 0.95);
        assertTrue(top.sources().containsAll(List.of("runtime", "logs")));
        assertFalse(result.escalationRequired());
    }

    @Test
    void runningProcessThatListensOnAnotherPortIsAConfigurationProblem() {
        LogExcerpt logs = logs(APP, List.of(signal(Kind.LISTENING, 0, "Server listening on port 3000", "3000")));
        DiagnosticResult result = diagnose(ctx().checks(refused()).runtime(running(APP)).logs(logs));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("port-not-listening", top.patternId());
        assertEquals(RecoveryActionType.ESCALATE, top.action(), "a restart cannot fix a wrong port");
    }

    @Test
    void portAlreadyInUseIsRestartable() {
        LogExcerpt logs = logs(APP, List.of(signal(Kind.PORT_IN_USE, 0, "Error: listen EADDRINUSE: address already in use :::8080", null)));
        DiagnosticFinding top = diagnose(ctx().checks(refused()).runtime(running(APP)).logs(logs)).top().orElseThrow();
        assertEquals("port-not-listening", top.patternId());
        assertEquals(RecoveryActionType.RESTART_CONTAINER, top.action());
    }

    @Test
    void timeoutOfARunningContainerRecommendsARestart() {
        DiagnosticFinding top = diagnose(ctx().checks(DiagnosticFixtures.timeouts()).runtime(running(APP))
                .network(network(true, "connected", null, null))).top().orElseThrow();
        assertEquals("application-timeout", top.patternId());
        assertEquals(RecoveryActionType.RESTART_CONTAINER, top.action());
    }

    @Test
    void connectionRefusedWithoutRuntimeEvidenceIsInvestigatedAndEscalatedToAi() {
        DiagnosticResult result = diagnose(ctx().noContainer().checks(refused()).network(network(true, "refused", null, null)));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("connection-refused", top.patternId());
        assertEquals(RecoveryActionType.INVESTIGATE, top.action());
        assertFalse(top.missing().isEmpty(), "names the missing runtime evidence");
        assertTrue(top.sources().contains("network"));
    }

    @Test
    void serverErrorsAreInvestigatedNotBlindlyRestarted() {
        LogExcerpt logs = logs(APP, List.of(signal(Kind.EXCEPTION, 0, "java.lang.IllegalStateException: pool exhausted", null)));
        DiagnosticResult result = diagnose(ctx().checks(DiagnosticFixtures.serverErrors(503)).runtime(running(APP)).logs(logs));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("http-5xx", top.patternId());
        assertEquals(RecoveryActionType.INVESTIGATE, top.action());
        assertFalse(result.escalationRequired(), "health checks plus an exception log are sufficient deterministic evidence");
        DiagnosticResult withoutLogs = diagnose(ctx().checks(DiagnosticFixtures.serverErrors(503)).runtime(running(APP)));
        assertEquals("http-5xx", withoutLogs.top().orElseThrow().patternId());
        assertTrue(withoutLogs.escalationRequired(), "without logs the cause of the 5xx is uncertain, so AI reasoning is requested");
        assertTrue(withoutLogs.top().orElseThrow().missing().stream().anyMatch(m -> m.startsWith("Dependency map")));
    }

    @Test
    void unhealthyDependencyNamedInLogsIsTheRootCause() {
        ContainerSnapshot db = exited("relay-db", 1);
        LogExcerpt logs = logs(APP, List.of(signal(Kind.CONNECTION_REFUSED, 0, "Error: connect ECONNREFUSED relay-db:5432", "relay-db:5432")));
        DiagnosticResult result = diagnose(ctx().checks(DiagnosticFixtures.serverErrors(500)).runtime(running(APP)).logs(logs)
                .dependency(dependency("relay-db", db)));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("dependency-unavailable", top.patternId());
        assertEquals(RecoveryActionType.RESTART_DEPENDENCY, top.action());
        assertEquals("relay-db", top.actionTarget());
        assertTrue(top.sources().containsAll(List.of("dependency", "logs", "health_check")));
        assertFalse(result.escalationRequired(), result.escalationReason());
    }

    @Test
    void appThatDidNotReconnectAfterItsDependencyRecoveredIsRestarted() {
        ContainerSnapshot db = container("relay-db", "running", true, false, false, 0, 0, "healthy", NOW.minusSeconds(20), NOW.minusSeconds(40));
        LogExcerpt logs = logs(APP, List.of(signal(Kind.CONNECTION_REFUSED, 0, "Error: connect ECONNREFUSED relay-db:5432", "relay-db:5432")));
        DiagnosticFinding top = diagnose(ctx().checks(DiagnosticFixtures.serverErrors(500)).runtime(running(APP)).logs(logs)
                .dependency(dependency("relay-db", db))).top().orElseThrow();
        assertEquals("dependency-recovered", top.patternId());
        assertEquals(RecoveryActionType.RESTART_CONTAINER, top.action());
        assertEquals(APP, top.actionTarget());
    }

    /** Regression from the live Docker run: a stopped container's name stops resolving in Docker's embedded DNS. */
    @Test
    void dnsFailureCausedByAStoppedContainerDoesNotCompeteWithTheRealRootCause() {
        var timeouts = DiagnosticFixtures.timeouts();
        DiagnosticResult result = diagnose(ctx().checks(timeouts).runtime(exited(APP, 0)).network(network(false, null, null, null)));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("container-stopped", top.patternId());
        assertEquals(RecoveryActionType.START_CONTAINER, top.action());
        assertFalse(result.escalationRequired(), result.escalationReason());
        DiagnosticFinding dns = result.ranked().stream().filter(f -> f.patternId().equals("dns-failure")).findFirst().orElseThrow();
        assertTrue(dns.confidence() < 0.5);
        assertTrue(dns.cause().contains("is not running"));
    }

    /** Regression from the live run: the container restarted after the last failed check, so those checks are stale. */
    @Test
    void failuresThatPredateTheCurrentProcessDoNotJustifyARestart() {
        ContainerSnapshot justStarted = container(APP, "running", true, false, false, 0, 0, "healthy", NOW.plusSeconds(5), NOW.minusSeconds(200));
        DiagnosticResult result = diagnose(ctx().checks(DiagnosticFixtures.timeouts()).runtime(justStarted));
        DiagnosticFinding top = result.top().orElseThrow();
        assertFalse(top.contradictory().isEmpty(), "stale failure evidence is flagged");
        assertTrue(top.contradictory().get(0).contains("predates the current process"));
        assertTrue(result.escalationRequired(), "no confident restart recommendation from stale evidence");
        // Evidence from before the restart is still fine for findings that survive a restart.
        ContainerSnapshot loop = container(APP, "running", true, false, false, 1, 6, null, NOW.plusSeconds(5), NOW.minusSeconds(10));
        DiagnosticFinding crashLoop = diagnose(ctx().checks(refused()).runtime(loop)).ranked().stream()
                .filter(f -> f.patternId().equals("crash-loop")).findFirst().orElseThrow();
        assertTrue(crashLoop.contradictory().isEmpty());
    }

    /** Regression from the live run: refused while stopping, then timeouts once the container's address is gone. */
    @Test
    void signatureIsJudgedOnTheNewestFailuresNotTheTransition() {
        var checks = List.of(DiagnosticFixtures.check("CHK-3", false, "unreachable", null, "No response within the 2500 ms deadline.", 0),
                DiagnosticFixtures.check("CHK-2", false, "unreachable", null, "No response within the 2500 ms deadline.", 3),
                DiagnosticFixtures.check("CHK-1", false, "unreachable", null, "Connection refused or could not be established.", 6));
        DiagnosticResult result = diagnose(ctx().noContainer().checks(checks));
        assertEquals("application-timeout", result.top().orElseThrow().patternId());
        assertTrue(result.ranked().stream().noneMatch(f -> f.patternId().equals("unclassified-failure")));
        assertTrue(result.escalationRequired(), "without runtime evidence the timeout cause is still uncertain");
    }

    private static final String CONNECT_TIMEOUT = "TCP connect timed out after 2500 ms; no connection was established.";
    private static final String DNS = "Host could not be resolved (DNS lookup failed).";
    private static final String REFUSED = "Connection refused: nothing accepted the TCP connection.";

    /** Live phase 2: the JVM still has the stopped container's cached IP; connects to it time out. */
    @Test
    void tcpConnectTimeoutIsNotDiagnosedAsAHungApplication() {
        var checks = List.of(DiagnosticFixtures.check("CHK-2", false, "unreachable", null, CONNECT_TIMEOUT, 0),
                DiagnosticFixtures.check("CHK-1", false, "unreachable", null, CONNECT_TIMEOUT, 60));
        DiagnosticResult result = diagnose(ctx().noContainer().checks(checks).network(network(true, "timeout", null, null)));
        assertEquals("tcp-connect-timeout", result.top().orElseThrow().patternId());
        assertTrue(result.ranked().stream().noneMatch(f -> f.patternId().equals("application-timeout")));
        assertTrue(result.ranked().stream().noneMatch(f -> f.patternId().equals("dns-failure")), "DNS resolved, so no DNS finding");
        assertFalse(result.top().orElseThrow().action().mutates());
    }

    /** Live phase 3: Docker DNS returns NXDOMAIN; only now is a DNS failure reported. */
    @Test
    void dnsFailureIsReportedOnlyWhenResolutionActuallyFails() {
        var transition = List.of(DiagnosticFixtures.check("CHK-3", false, "unreachable", null, DNS, 0),
                DiagnosticFixtures.check("CHK-2", false, "unreachable", null, CONNECT_TIMEOUT, 30),
                DiagnosticFixtures.check("CHK-1", false, "unreachable", null, REFUSED, 60));
        DiagnosticResult resolvedNow = diagnose(ctx().noContainer().checks(transition).network(network(true, "refused", null, null)));
        assertTrue(resolvedNow.ranked().stream().noneMatch(f -> f.patternId().equals("dns-failure")),
                "one stored DNS message is not enough when the probe resolves the name now");
        DiagnosticResult failingNow = diagnose(ctx().noContainer().checks(transition).network(network(false, null, null, null)));
        DiagnosticFinding dns = failingNow.top().orElseThrow();
        assertEquals("dns-failure", dns.patternId());
        assertTrue(dns.confidence() < AiEscalationPolicy.CONFIDENCE_THRESHOLD);
        assertTrue(dns.supporting().stream().anyMatch(r -> r.source().equals("network")), "cites the actual DNS observation");
    }

    /** With runtime evidence every phase resolves to the real cause; network symptoms are marked as consequences. */
    @Test
    void stoppedContainerOutranksEveryNetworkSymptomPhase() {
        for (String message : List.of(REFUSED, CONNECT_TIMEOUT, DNS)) {
            var checks = List.of(DiagnosticFixtures.check("CHK-2", false, "unreachable", null, message, 0),
                    DiagnosticFixtures.check("CHK-1", false, "unreachable", null, message, 60));
            DiagnosticResult result = diagnose(ctx().checks(checks).runtime(exited(APP, 0))
                    .network(message.equals(DNS) ? network(false, null, null, null) : network(true, message.equals(REFUSED) ? "refused" : "timeout", null, null)));
            assertEquals("container-stopped", result.top().orElseThrow().patternId(), message);
            assertFalse(result.escalationRequired(), message + ": " + result.escalationReason());
        }
    }

    @Test
    void unresolvableSingleLabelHostWithoutRuntimeEvidenceIsNotClaimedAsCertain() {
        DiagnosticResult result = diagnose(ctx().noContainer().checks(DiagnosticFixtures.timeouts()).network(network(false, null, null, null)));
        DiagnosticFinding dns = result.top().orElseThrow();
        assertEquals("dns-failure", dns.patternId());
        assertTrue(dns.confidence() < AiEscalationPolicy.CONFIDENCE_THRESHOLD, "a Docker service name may simply be stopped");
        assertTrue(dns.missing().stream().anyMatch(m -> m.contains("test-target")));
        assertTrue(result.escalationRequired());
    }

    @Test
    void dnsAndTlsFailuresAreExternalAndEscalated() {
        DiagnosticFinding dns = diagnose(ctx().noContainer().checks(List.of(DiagnosticFixtures.check("CHK-1", false, "unreachable", null, "Host could not be resolved.", 0)))
                .network(network(false, null, null, null))).top().orElseThrow();
        assertEquals("dns-failure", dns.patternId());
        assertFalse(dns.action().mutates());
        DiagnosticFinding tls = diagnose(ctx().noContainer().url("https://test-target/health")
                .checks(List.of(DiagnosticFixtures.check("CHK-1", false, "unreachable", null, "TLS handshake failed: certificate expired.", 0)))
                .network(network(true, "connected", "failed", "certificate expired"))).top().orElseThrow();
        assertEquals("tls-failure", tls.patternId());
        assertFalse(tls.action().mutates());
    }

    @Test
    void unknownFailureSignatureIsUnclassifiedAndRequestsAi() {
        DiagnosticResult result = diagnose(ctx().noContainer().checks(List.of(
                DiagnosticFixtures.check("CHK-1", false, "unreachable", null, "Unexpected end of stream.", 0))));
        assertEquals("unclassified-failure", result.top().orElseThrow().patternId());
        assertTrue(result.escalationRequired());
        assertEveryFindingCitesEvidence(result);
    }

    @Test
    void combinedUrlRuntimeAndCodeEvidenceIsCorrelatedAcrossSources() {
        CodeSignal code = new CodeSignal("F-1", "reliability", "HTTP call without timeout", "app/client.py", 12, "medium");
        LogExcerpt logs = logs(APP, List.of(signal(Kind.TIMEOUT, 0, "requests.exceptions.ReadTimeout: timed out", null)));
        DiagnosticResult result = diagnose(ctx().checks(DiagnosticFixtures.timeouts()).runtime(running(APP)).logs(logs)
                .network(network(true, "connected", null, null)).code(code).history(new History(2, 1, 0)));
        DiagnosticFinding top = result.top().orElseThrow();
        assertEquals("application-timeout", top.patternId());
        assertTrue(top.sources().containsAll(List.of("health_check", "runtime", "network", "code", "history")), top.sources().toString());
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.id().equals("CODE-F-1")), "related static-analysis finding attached");
        assertTrue(result.evidenceSources().containsAll(List.of("health_check", "runtime", "logs", "network", "code", "history")));
    }

    @Test
    void healthyLatestCheckContradictsFailureHypotheses() {
        var checks = List.of(DiagnosticFixtures.check("CHK-3", true, "healthy", 200, null, 0),
                DiagnosticFixtures.check("CHK-2", false, "unreachable", null, "Connection refused or could not be established.", 60));
        DiagnosticResult result = diagnose(ctx().checks(checks).runtime(exited(APP, 0)));
        DiagnosticFinding stopped = result.ranked().stream().filter(f -> f.patternId().equals("container-stopped")).findFirst().orElseThrow();
        assertFalse(stopped.contradictory().isEmpty());
    }

    @Test
    void multipleMatchesAreRankedAndEachCitesEvidence() {
        ContainerSnapshot oom = container(APP, "exited", false, false, true, 137, 0, null, NOW.minusSeconds(600), NOW.minusSeconds(30));
        LogExcerpt logs = logs(APP, List.of(signal(Kind.OOM, 0, "java.lang.OutOfMemoryError: Java heap space", null),
                signal(Kind.EXCEPTION, 1, "java.lang.OutOfMemoryError: Java heap space", null)));
        DiagnosticResult result = diagnose(ctx().checks(refused()).runtime(oom).logs(logs).network(network(true, "refused", null, null)));
        assertTrue(result.ranked().size() >= 1);
        assertEveryFindingCitesEvidence(result);
        assertEquals("oom-killed", result.top().orElseThrow().patternId());
    }

    @Test
    void registryHasUniquePatternIdsCoveringEveryRequiredFailureClass() {
        FailurePatternRegistry registry = new FailurePatternRegistry();
        List<String> ids = registry.ids();
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertTrue(ids.containsAll(List.of("application-healthy", "container-stopped", "crash-loop", "oom-killed", "port-not-listening",
                "application-timeout", "connection-refused", "http-5xx", "dependency-unavailable", "dependency-recovered",
                "dns-failure", "tls-failure", "http-unexpected-status")), ids.toString());
    }

    @Test
    void aPatternThatThrowsDoesNotBreakDiagnosis() {
        FailurePattern broken = new FailurePattern() {
            @Override public String id() { return "broken"; }
            @Override public java.util.Optional<DiagnosticFinding> evaluate(DiagnosticContext context) { throw new IllegalStateException("bug"); }
        };
        DiagnosticEngine withBroken = new DiagnosticEngine(new FailurePatternRegistry(List.of(broken)),
                new EvidenceCorrelationEngine(null, null, null, null, null, null, null, null, null, null));
        assertEquals("container-stopped", withBroken.diagnose(ctx().checks(refused()).runtime(exited(APP, 0)).build()).top().orElseThrow().patternId());
    }

    @Test
    void aConclusionWithoutEvidenceIsDiscarded() {
        FailurePattern unsupported = new FailurePattern() {
            @Override public String id() { return "unsupported"; }
            @Override public java.util.Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
                return java.util.Optional.of(DiagnosticFinding.builder("unsupported", "No evidence", "x").confidence(0.99).build());
            }
        };
        DiagnosticEngine withUnsupported = new DiagnosticEngine(new FailurePatternRegistry(List.of(unsupported)),
                new EvidenceCorrelationEngine(null, null, null, null, null, null, null, null, null, null));
        DiagnosticResult result = withUnsupported.diagnose(ctx().checks(refused()).runtime(exited(APP, 0)).build());
        assertTrue(result.ranked().stream().noneMatch(f -> f.patternId().equals("unsupported")));
    }
}
