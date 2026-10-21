package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.GeminiClient;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.RegisterApplicationRequest;
import com.relayaegis.dto.ApiDtos.UpdatePolicyRequest;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.repository.RecoveryEventRepository;
import com.relayaegis.runtime.DockerCommandRunner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Flow: a real monitored failure → incident → deterministic diagnosis from Docker runtime evidence → policy-authorized
 * start of the allowlisted container → verification by real health checks → incident resolved. Docker is replaced at
 * the CLI boundary (DockerCommandRunner) so the test controls container state; the application is a real HTTP server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.monitoring.failure-threshold=2",
        "relay.monitoring.timeout-ms=1000",
        "relay.recovery.docker.enabled=true",
        "relay.recovery.docker.allowed-containers=relay-e2e-target",
        "relay.recovery.cooldown-seconds=0",
        "relay.recovery.retry-backoff-ms=0",
        "relay.recovery.verification.timeout-ms=6000",
        "relay.recovery.verification.poll-ms=100",
        "relay.ai.diagnosis-retry.enabled=false"})
class SelfHealingEndToEndTest {
    private static final String CONTAINER = "relay-e2e-target";
    @Autowired ApplicationService applications;
    @Autowired MonitoringService monitoring;
    @Autowired IncidentRepository incidents;
    @Autowired RecoveryEventRepository recoveryEvents;
    @Autowired RecoveryPolicyService policies;
    @Autowired ObjectMapper mapper;
    @MockitoBean GeminiClient gemini;
    @MockitoBean DockerCommandRunner docker;

    private int port;
    private HttpServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** When true, a started container exits again immediately (verification must fail). */
    private final AtomicBoolean crashesAfterStart = new AtomicBoolean(false);
    private volatile Instant startedAt = Instant.parse("2026-10-16T04:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("test-model");
        when(docker.inspectContainer(CONTAINER)).thenAnswer(inv -> new DockerCommandRunner.Result(0, inspectJson(), null));
        when(docker.stats(CONTAINER)).thenReturn(new DockerCommandRunner.Result(0,
                "{\"CPUPerc\":\"1.20%\",\"MemPerc\":\"10.00%\",\"MemUsage\":\"25MiB / 256MiB\"}", null));
        when(docker.logs(anyString(), anyInt())).thenReturn(new DockerCommandRunner.Result(0,
                "2026-10-16T04:59:00.000000000Z Server listening on port " + port + "\n", null));
        when(docker.start(CONTAINER)).thenAnswer(inv -> {
            startedAt = Instant.now();
            if (!crashesAfterStart.get()) startServer();
            return new DockerCommandRunner.Result(0, CONTAINER + "\n", null);
        });
        policies.update("policy-restart", new UpdatePolicyRequest("allow_automatically", RecoveryPolicyService.AUTO_CONFIRMATION));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        policies.update("policy-restart", new UpdatePolicyRequest("require_approval", null));
    }

    private synchronized void startServer() throws Exception {
        if (running.get()) return;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes()); exchange.close(); });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        running.set(true);
    }

    private String inspectJson() {
        boolean up = running.get();
        String finished = up ? "0001-01-01T00:00:00Z" : Instant.now().minusMillis(50).toString();
        return """
                [{"Name":"/%s","RestartCount":0,
                  "State":{"Status":"%s","Running":%s,"Restarting":false,"OOMKilled":false,"ExitCode":%d,
                           "StartedAt":"%s","FinishedAt":"%s"},
                  "HostConfig":{"Memory":268435456,"RestartPolicy":{"Name":"no"}},
                  "Config":{"Image":"relay-e2e-target:latest","ExposedPorts":{"%d/tcp":{}},"Labels":{}},
                  "NetworkSettings":{"Ports":{},"Networks":{"relay":{}}}}]
                """.formatted(CONTAINER, up ? "running" : "exited", up, up ? 0 : 0, startedAt, finished, port);
    }

    private String register(String name) {
        return applications.register(new RegisterApplicationRequest(name, "http://127.0.0.1:" + port + "/health", null, "Development",
                null, null, CONTAINER)).id();
    }

    @Test
    void stoppedContainerIsDiagnosedRecoveredAndVerifiedWithoutAi() throws Exception {
        String appId = register("E2E stopped container");

        monitoring.check(appId);
        monitoring.check(appId);
        IncidentEntity incident = awaitIncident(appId);
        await(() -> "passed".equals(reload(incident).verificationStatus), 20_000);

        IncidentEntity done = reload(incident);
        assertEquals("recovered", done.state);
        assertEquals("recovery_verified", done.status);
        DiagnosisDto diagnosis = mapper.readValue(done.diagnosisJson, DiagnosisDto.class);
        assertEquals("deterministic", diagnosis.provider());
        assertEquals("container-stopped", diagnosis.engine().topPatternId());
        assertEquals("not_required", diagnosis.engine().aiEscalation());
        assertTrue(diagnosis.engine().evidenceSources().containsAll(List.of("health_check", "runtime", "logs")));
        assertTrue(done.evidenceJson.contains("Automatic recovery policy decision"));
        assertTrue(done.evidenceJson.contains("Authorized: START_CONTAINER on " + CONTAINER));
        assertTrue(done.evidenceJson.contains("Verification after attempt 1: passed"));
        verify(docker).start(CONTAINER);
        verify(docker, never()).restart(anyString());
        verify(gemini, never()).generate(anyString(), anyString(), anyBoolean(), anyInt());
        assertTrue(recoveryEvents.findByIncidentIdOrderByOccurredAtDesc(done.id).stream().anyMatch(e -> "recovery_verified".equals(e.outcome)));
    }

    @Test
    void containerThatCrashesAgainFailsVerificationAndIsEscalatedAfterTheRetryLimit() throws Exception {
        crashesAfterStart.set(true);
        String appId = register("E2E crashing container");

        monitoring.check(appId);
        monitoring.check(appId);
        IncidentEntity incident = awaitIncident(appId);
        await(() -> "failed".equals(reload(incident).verificationStatus), 30_000);

        IncidentEntity done = reload(incident);
        assertEquals("unresolved", done.state);
        assertEquals("recovery_failed", done.status);
        assertTrue(done.evidenceJson.contains("Recovery escalated"));
        verify(docker, atLeastOnce()).start(CONTAINER);
        assertFalse(done.evidenceJson.contains("Verification after attempt 3"), "bounded by max-attempts-per-incident=2");
        verify(gemini, never()).generate(anyString(), anyString(), anyBoolean(), anyInt());
    }

    private IncidentEntity reload(IncidentEntity incident) { return incidents.findById(incident.id).orElseThrow(); }

    private IncidentEntity awaitIncident(String appId) throws InterruptedException {
        await(() -> !incidents.findByApplicationIdOrderByDetectedAtDesc(appId).isEmpty(), 10_000);
        return incidents.findByApplicationIdOrderByDetectedAtDesc(appId).get(0);
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within " + timeoutMs + " ms");
    }
}
