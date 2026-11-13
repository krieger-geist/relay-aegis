package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.GeminiClient;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.HealthCheckResult;
import com.relayaegis.dto.ApiDtos.RegisterApplicationRequest;
import com.relayaegis.dto.ApiDtos.UpdatePolicyRequest;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.IncidentRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Flow: /health stays green while the Orders page fails on one malformed record, so checks record journey_failed,
 * an incident opens, the deterministic diagnosis names the record, nothing runs automatically (data always needs an
 * operator), the operator approves, RELAY calls the application's own quarantine hook, and real checks verify it.
 * The application is a real HTTP server; nothing is deleted, the application moves the record aside itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.monitoring.failure-threshold=2",
        "relay.monitoring.timeout-ms=1000",
        "relay.recovery.cooldown-seconds=0",
        "relay.recovery.retry-backoff-ms=0",
        "relay.recovery.verification.timeout-ms=6000",
        "relay.recovery.verification.poll-ms=100",
        "relay.ai.diagnosis-retry.enabled=false"})
class DataQuarantineEndToEndTest {
    private static final String BAD = "order-1003";
    @Autowired ApplicationService applications;
    @Autowired MonitoringService monitoring;
    @Autowired IncidentRepository incidents;
    @Autowired RecoveryOrchestrationService recovery;
    @Autowired RecoveryPolicyService policies;
    @Autowired ObjectMapper mapper;
    @MockitoBean GeminiClient gemini;

    private HttpServer server;
    private int port;
    private final AtomicBoolean corrupt = new AtomicBoolean(true);
    private final List<String> quarantineCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/orders", ex -> {
            if (corrupt.get()) send(ex, 500, "{\"error\":\"Could not list orders: one order is malformed\",\"recordId\":\"" + BAD + "\"}");
            else send(ex, 200, "{\"orders\":[]}");
        });
        server.createContext("/relay/quarantine", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            quarantineCalls.add(ex.getRequestMethod() + " " + body);
            if (body.contains("\"recordId\":\"" + BAD + "\"")) corrupt.set(false);
            send(ex, 200, "{\"status\":\"quarantined\"}");
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private String register(String name, boolean withHook) {
        String base = "http://127.0.0.1:" + port;
        return applications.register(new RegisterApplicationRequest(name, base + "/health", null, "Development", null, null, null,
                base + "/orders", withHook ? base + "/relay/quarantine" : null)).id();
    }

    @Test
    void badRecordIsDiagnosedQuarantinedOnlyAfterApprovalAndVerified() throws Exception {
        String appId = register("Journey quarantine", true);

        HealthCheckResult first = monitoring.check(appId);
        assertEquals("journey_failed", first.outcome());
        monitoring.check(appId);
        IncidentEntity incident = awaitIncident(appId);
        await(() -> reload(incident).diagnosisJson != null, 10_000);

        DiagnosisDto diagnosis = mapper.readValue(reload(incident).diagnosisJson, DiagnosisDto.class);
        assertEquals("user-journey-failure", diagnosis.engine().topPatternId());
        assertTrue(reload(incident).title.contains("User journey failing"), reload(incident).title);
        // The pipeline must not act on data by itself.
        Thread.sleep(500);
        assertTrue(quarantineCalls.isEmpty(), "no quarantine without an operator");

        recovery.beginRecovery(incident.id);
        await(() -> "passed".equals(reload(incident).verificationStatus), 20_000);

        IncidentEntity done = reload(incident);
        assertEquals("recovered", done.state);
        assertEquals(1, quarantineCalls.size());
        assertTrue(quarantineCalls.get(0).startsWith("POST "));
        assertTrue(quarantineCalls.get(0).contains("\"incidentId\":\"" + incident.id + "\""));
        assertTrue(done.evidenceJson.contains("moved record " + BAD + " aside"));
        assertTrue(done.evidenceJson.contains("record " + BAD + " was moved aside and"));
        verify(gemini, never()).generate(anyString(), anyString(), anyBoolean(), anyInt());
    }

    @Test
    void withoutAHookTheRecordIsNamedButNothingRuns() throws Exception {
        String appId = register("Journey no hook", false);
        monitoring.check(appId);
        monitoring.check(appId);
        IncidentEntity incident = awaitIncident(appId);
        await(() -> reload(incident).diagnosisJson != null, 10_000);
        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> recovery.beginRecovery(incident.id));
        assertTrue(refused.getReason().contains("no quarantine hook"), refused.getReason());
        assertTrue(quarantineCalls.isEmpty());
    }

    @Test
    void journeyAndHookUrlsMustStayOnTheApplicationsOwnHost() {
        String base = "http://127.0.0.1:" + port;
        ResponseStatusException otherHost = assertThrows(ResponseStatusException.class, () -> applications.register(new RegisterApplicationRequest(
                "Journey other host", base + "/health", null, "Development", null, null, null, "http://localhost:" + port + "/orders", null)));
        assertEquals(400, otherHost.getStatusCode().value());
        ResponseStatusException hookOnly = assertThrows(ResponseStatusException.class, () -> applications.register(new RegisterApplicationRequest(
                "Journey hook only", base + "/health", null, "Development", null, null, null, null, base + "/relay/quarantine")));
        assertEquals(400, hookOnly.getStatusCode().value());
    }

    @Test
    void theQuarantinePolicyCannotBeMadeAutomatic() {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> policies.update("policy-data-quarantine",
                new UpdatePolicyRequest("allow_automatically", RecoveryPolicyService.AUTO_CONFIRMATION)));
        assertEquals(400, refused.getStatusCode().value());
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
