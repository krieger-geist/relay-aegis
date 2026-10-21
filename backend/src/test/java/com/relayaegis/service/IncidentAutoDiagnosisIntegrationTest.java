package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.GeminiClient;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.RegisterApplicationRequest;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.IncidentRepository;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;

/**
 * Full Spring context (H2): a real monitored failure opens an incident through the real pipeline, and
 * diagnosis is queued automatically on the incident executor without blocking the health check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.monitoring.failure-threshold=2",
        "relay.monitoring.timeout-ms=1000"})
class IncidentAutoDiagnosisIntegrationTest {
    @Autowired ApplicationService applications;
    @Autowired MonitoringService monitoring;
    @Autowired IncidentRepository incidents;
    @Autowired ObjectMapper mapper;
    @MockitoBean GeminiClient gemini;
    @MockitoSpyBean AutoRecoveryService recoveryDecision;
    @Autowired DiagnosisRetryScheduler retryScheduler;
    @Autowired @Qualifier("incidentExecutor") Executor incidentExecutor;

    @Test
    void newIncidentQueuesDiagnosisAutomaticallyAndAsynchronously() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        String appId = applications.register(new RegisterApplicationRequest("Auto-diagnosis target",
                "http://127.0.0.1:" + closedPort + "/health", null, "Development", null, null, null)).id();

        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> providerThread = new AtomicReference<>();
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("test-model");
        when(gemini.generate(anyString(), anyString(), anyBoolean(), anyInt())).thenAnswer(inv -> {
            providerThread.set(Thread.currentThread().getName());
            release.await(20, TimeUnit.SECONDS);
            return new GeminiClient.Result(true, """
                    {"probableCause":"The application is not accepting connections.","explanation":"Checks were refused.",
                     "confidence":"Medium","confidenceReason":"Two refusals.","supportingEvidence":[],"missingEvidence":[],
                     "recommendedActions":[{"action":"Confirm the process is running.","rationale":"No response.","risk":"low"}],
                     "requiresHumanApproval":true,"approvalReason":"Operator decision."}
                    """, null, null);
        });

        monitoring.check(appId);
        assertTrue(incidents.findByApplicationIdOrderByDetectedAtDesc(appId).isEmpty(), "threshold of 2 is unchanged");
        String testThread = Thread.currentThread().getName();
        var second = monitoring.check(appId);
        assertTrue(second.message().contains("opened after 2 consecutive failed check(s)"));

        // The health check returned while Gemini is still blocked: monitoring is not waiting on diagnosis.
        IncidentEntity open = awaitIncident(appId);
        awaitTrue(() -> providerThread.get() != null);
        // The deterministic diagnosis is stored before AI runs; AI was requested because the evidence is ambiguous.
        DiagnosisDto deterministic = mapper.readValue(incidents.findById(open.id).orElseThrow().diagnosisJson, DiagnosisDto.class);
        assertEquals("deterministic", deterministic.provider());
        assertEquals("required", deterministic.engine().aiEscalation());
        assertFalse(deterministic.engine().hypotheses().isEmpty());
        assertFalse(deterministic.engine().hypotheses().get(0).supportingEvidence().isEmpty(), "every deterministic hypothesis cites evidence");
        assertTrue(providerThread.get().startsWith("relay-ai-"), "runs on the dedicated AI executor");
        // Scenario 7: while Gemini is still busy, the recovery decision has already been made and the
        // recovery worker pool is free.
        org.mockito.Mockito.verify(recoveryDecision).startIfAllowed(open.id);
        assertRecoveryPoolResponsive();
        assertNotEquals(testThread, providerThread.get());
        assertEquals("unresolved", incidents.findById(open.id).orElseThrow().state, "the incident is not blocked on AI");

        release.countDown();
        awaitProvider(open.id, "gemini");
        IncidentEntity diagnosed = incidents.findById(open.id).orElseThrow();
        DiagnosisDto stored = mapper.readValue(diagnosed.diagnosisJson, DiagnosisDto.class);
        assertEquals("gemini", stored.provider());
        assertEquals("automatic", stored.trigger());
        assertTrue(stored.humanApprovalRequired());
        assertTrue(diagnosed.timelineJson.contains("Automatic diagnosis"));
        assertEquals("unresolved", diagnosed.state);
        // Diagnosis never performs recovery: URL-only incidents have no runbook and nothing was executed.
        assertEquals("not_run", diagnosed.verificationStatus);

        // Further failing checks add no second incident and queue no second diagnosis job.
        monitoring.check(appId);
        Thread.sleep(500);
        List<IncidentEntity> all = incidents.findByApplicationIdOrderByDetectedAtDesc(appId);
        assertEquals(1, all.size());
        org.mockito.Mockito.verify(gemini, org.mockito.Mockito.times(1)).generate(anyString(), anyString(), anyBoolean(), anyInt());
        assertFalse(all.get(0).diagnosisJson.isBlank());
    }

    @Test
    void aBackgroundAiRetryThatHangsNeverBlocksMonitoringOrRecoveryAndThenUpgradesTheDiagnosis() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        String appId = applications.register(new RegisterApplicationRequest("Background retry target",
                "http://127.0.0.1:" + closedPort + "/health", null, "Development", null, null, null)).id();
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("test-model");
        when(gemini.generate(anyString(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(new GeminiClient.Result(false, null, "http_503", "Gemini service is unavailable (HTTP 503). Gave up after 3 attempts."));

        monitoring.check(appId);
        monitoring.check(appId);
        IncidentEntity open = awaitIncident(appId);
        awaitProvider(open.id, "deterministic-fallback");
        DiagnosisDto fallback = mapper.readValue(incidents.findById(open.id).orElseThrow().diagnosisJson, DiagnosisDto.class);
        assertEquals("deterministic-fallback", fallback.provider());
        assertTrue(fallback.nextAiRetryAt() != null, "a background AI retry was scheduled automatically");

        // Make the retry due now, then let the next Gemini call hang until released.
        IncidentEntity stored = incidents.findById(open.id).orElseThrow();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(stored.diagnosisJson);
        node.put("nextAiRetryAt", java.time.Instant.now().minusSeconds(1).toString());
        stored.diagnosisJson = mapper.writeValueAsString(node);
        incidents.save(stored);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> providerThread = new AtomicReference<>();
        when(gemini.generate(anyString(), anyString(), anyBoolean(), anyInt())).thenAnswer(inv -> {
            providerThread.set(Thread.currentThread().getName());
            release.await(20, TimeUnit.SECONDS);
            return new GeminiClient.Result(true, """
                    {"probableCause":"The application is not accepting connections.","explanation":"Checks were refused.",
                     "confidence":"Medium","confidenceReason":"Refusals.","supportingEvidence":[],"missingEvidence":[],
                     "recommendedActions":[],"requiresHumanApproval":true,"approvalReason":"Operator decision."}
                    """, null, null);
        });

        retryScheduler.dispatchDueRetries();
        awaitTrue(() -> providerThread.get() != null);
        assertTrue(providerThread.get().startsWith("relay-ai-"), "the background retry runs on the AI pool");

        // While the AI retry hangs: monitoring still records checks promptly, and the recovery pool is free.
        long started = System.nanoTime();
        var check = monitoring.check(appId);
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000, "monitoring check was not delayed by AI");
        assertFalse(check.healthy());
        assertRecoveryPoolResponsive();

        release.countDown();
        awaitTrue(() -> {
            try { return "gemini".equals(mapper.readValue(incidents.findById(open.id).orElseThrow().diagnosisJson, DiagnosisDto.class).provider()); }
            catch (Exception ex) { return false; }
        });
        DiagnosisDto upgraded = mapper.readValue(incidents.findById(open.id).orElseThrow().diagnosisJson, DiagnosisDto.class);
        assertEquals(List.of("deterministic", "rule-based", "ai"), upgraded.aiAttempts().stream().map(com.relayaegis.dto.ApiDtos.AiAttemptDto::outcome).toList());
        assertEquals("http_503", upgraded.aiAttempts().get(1).aiFailureCode(), "the original failure stays on record");
    }

    private void assertRecoveryPoolResponsive() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        incidentExecutor.execute(ran::countDown);
        assertTrue(ran.await(2, TimeUnit.SECONDS), "recovery worker pool is not waiting behind AI work");
    }

    private void awaitProvider(String incidentId, String provider) throws Exception {
        awaitTrue(() -> {
            String json = incidents.findById(incidentId).orElseThrow().diagnosisJson;
            try { return json != null && provider.equals(mapper.readValue(json, DiagnosisDto.class).provider()); }
            catch (Exception ex) { return false; }
        });
    }

    private IncidentEntity awaitIncident(String appId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<IncidentEntity> found = incidents.findByApplicationIdOrderByDetectedAtDesc(appId);
            if (!found.isEmpty()) return found.get(0);
            Thread.sleep(50);
        }
        throw new AssertionError("incident was not opened");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) { if (condition.getAsBoolean()) return; Thread.sleep(50); }
        throw new AssertionError("condition not met within 10 s");
    }
}
