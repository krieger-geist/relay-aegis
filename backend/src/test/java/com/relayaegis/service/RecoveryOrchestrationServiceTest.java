package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RecoveryPolicyEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryExecutor;
import com.relayaegis.recovery.RecoveryGuard;
import com.relayaegis.recovery.RecoveryPolicyEngine;
import com.relayaegis.recovery.VerificationEngine;
import com.relayaegis.repository.RecoveryEventRepository;
import com.relayaegis.repository.RecoveryPolicyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RecoveryOrchestrationServiceTest {
    private static final String CONTAINER = "relay-aegis-test-target";
    private IncidentService incidents;
    private DockerControlService docker;
    private DiagnosisService diagnosis;
    private RecoveryExecutor executor;
    private VerificationEngine verification;
    private RecoveryGuard guard;
    private RecoveryEventService events;
    private RecoveryPolicyEntity policy;
    private String incidentState;
    private IncidentEntity current;
    private final List<EvidenceItemDto> evidence = new ArrayList<>();

    @BeforeEach
    void setUp() {
        incidents = mock(IncidentService.class);
        incidentState = "unresolved";
        // Every read returns a fresh copy (like separate database reads) carrying the last saved state.
        when(incidents.findEntity("INC-1")).thenAnswer(inv -> copy(current != null ? current : incident(incidentState)));
        when(incidents.save(any())).thenAnswer(inv -> { current = inv.getArgument(0); return current; });
        when(incidents.toDto(any())).thenAnswer(inv -> dto(inv.getArgument(0)));
        when(incidents.get("INC-1")).thenAnswer(inv -> dto(current != null ? current : incident(incidentState)));
        org.mockito.Mockito.doAnswer(inv -> { evidence.add(inv.getArgument(1)); return null; }).when(incidents).appendEvidence(any(), any());
        docker = mock(DockerControlService.class);
        when(docker.isEnabled()).thenReturn(true);
        when(docker.isAllowed(CONTAINER)).thenReturn(true);
        diagnosis = mock(DiagnosisService.class);
        executor = mock(RecoveryExecutor.class);
        verification = mock(VerificationEngine.class);
        RecoveryEventRepository eventRepository = mock(RecoveryEventRepository.class);
        guard = new RecoveryGuard(2, 60, 3, 30, 15, eventRepository);
        events = mock(RecoveryEventService.class);
        policy = new RecoveryPolicyEntity(); policy.id = "policy-restart"; policy.action = "Restart an unhealthy container"; policy.mode = "require_approval";
    }

    private String environment = "Development";

    private RecoveryOrchestrationService service(Executor workers) {
        ApplicationService applications = mock(ApplicationService.class);
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-1"; app.environment = environment; app.dockerContainer = CONTAINER;
        when(applications.findEntity("app-1")).thenReturn(app);
        RecoveryPolicyRepository policies = mock(RecoveryPolicyRepository.class);
        when(policies.findById("policy-restart")).thenReturn(Optional.of(policy));
        RecoveryPolicyEngine engine = new RecoveryPolicyEngine(policies, docker, guard);
        return new RecoveryOrchestrationService(incidents, applications, diagnosis, engine, executor, verification, guard, events, workers, 0);
    }

    private void diagnosed(RecoveryActionType action, double confidence) {
        DiagnosticFinding finding = DiagnosticFinding.builder("container-stopped", "Container stopped", "runtime")
                .cause("The container exited.").confidence(confidence)
                .support(new EvidenceRef("docker:" + CONTAINER, "runtime", "status=exited"))
                .action(action, CONTAINER, "Start the container.").build();
        when(diagnosis.evaluateDeterministically("INC-1")).thenReturn(new DiagnosticResult(List.of(finding), false, null,
                List.of("runtime"), List.of(), Instant.now()));
    }

    @Test
    void refusesASecondConcurrentRecoveryForTheSameIncident() {
        List<Runnable> queued = new ArrayList<>();
        RecoveryOrchestrationService recovery = service(queued::add);
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        recovery.beginRecovery("INC-1");
        current = null; // the second request reads a stale "unresolved" state, so only the in-flight guard can stop it
        var conflict = assertThrows(ResponseStatusException.class, () -> recovery.beginRecovery("INC-1"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(1, queued.size());
    }

    @Test
    void refusesRecoveryForAnIncidentResolvedByObservation() {
        incidentState = "recovered";
        RecoveryOrchestrationService recovery = service(Runnable::run);

        var conflict = assertThrows(ResponseStatusException.class, () -> recovery.beginRecovery("INC-1"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void restoresIncidentStateWhenTheWorkerQueueRejectsTheAction() {
        List<Runnable> queued = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean full = new java.util.concurrent.atomic.AtomicBoolean(true);
        RecoveryOrchestrationService recovery = service(task -> {
            if (full.get()) throw new RejectedExecutionException("full");
            queued.add(task);
        });
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        var unavailable = assertThrows(ResponseStatusException.class, () -> recovery.beginRecovery("INC-1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("unresolved", current.state);
        assertEquals("not_run", current.verificationStatus);
        full.set(false);
        recovery.beginRecovery("INC-1");
        assertEquals(1, queued.size());
    }

    @Test
    void successfulRecoveryIsVerifiedAndRecordedWithItsPolicyBasis() {
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Started container " + CONTAINER + "."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("passed", "Verified: 2 healthy checks.", List.of()));

        service(Runnable::run).beginRecovery("INC-1");

        assertEquals("recovered", current.state);
        assertEquals("passed", current.verificationStatus);
        assertEquals("recovery_verified", current.status);
        assertTrue(evidence.stream().anyMatch(e -> e.type().equals("recovery") && e.detail().contains("Authorized: START_CONTAINER")
                && e.detail().contains("'container-stopped' at 92% confidence")));
        assertTrue(evidence.stream().anyMatch(e -> e.type().equals("verification") && e.title().contains("passed")));
        verify(events).record(anyString(), any(), org.mockito.ArgumentMatchers.contains("executed and verified"), org.mockito.ArgumentMatchers.eq("recovery_verified"),
                any(), any(), anyString(), anyString());
    }

    @Test
    void executedCommandWithoutPassingVerificationIsNeverSuccessAndRetriesStopAtTheLimit() {
        diagnosed(RecoveryActionType.RESTART_CONTAINER, 0.85);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Restarted container."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("failed", "The container stopped again.", List.of()));

        service(Runnable::run).beginRecovery("INC-1");

        verify(executor, times(2)).execute(any(), any()); // max-attempts-per-incident = 2
        assertEquals("unresolved", current.state);
        assertEquals("failed", current.verificationStatus);
        assertEquals("recovery_failed", current.status);
        assertTrue(evidence.stream().anyMatch(e -> e.title().equals("Recovery escalated")));
    }

    @Test
    void verificationTimeoutNeedsReview() {
        diagnosed(RecoveryActionType.RESTART_CONTAINER, 0.85);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Restarted container."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("timed_out", "Not verified within 30 s.", List.of()));

        service(Runnable::run).beginRecovery("INC-1");

        assertEquals("timed_out", current.verificationStatus);
        assertEquals("needs_review", current.status);
    }

    @Test
    void failedExecutionIsRecordedAsFailedVerificationWithoutCheckingHealth() {
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(false, false, "Docker operation failed."));

        service(Runnable::run).beginRecovery("INC-1");

        verify(verification, never()).verify(any(), any());
        assertEquals("failed", current.verificationStatus);
    }

    @Test
    void automaticRecoveryRequiresAllowAutomaticallyAndRecordsTheBlockedDecision() {
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        assertFalse(service(Runnable::run).beginAutomaticRecovery("INC-1"));

        verify(executor, never()).execute(any(), any());
        assertTrue(evidence.stream().anyMatch(e -> e.detail().contains("requires operator approval")));
    }

    @Test
    void automaticRecoveryRunsUnderAllowAutomatically() {
        policy.mode = "allow_automatically";
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Started."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("passed", "Verified.", List.of()));

        assertTrue(service(Runnable::run).beginAutomaticRecovery("INC-1"));
        assertEquals("passed", current.verificationStatus);
    }

    @Test
    void automaticRecoveryNeverMutatesForAnEscalateFinding() {
        policy.mode = "allow_automatically";
        diagnosed(RecoveryActionType.ESCALATE, 0.9);

        assertFalse(service(Runnable::run).beginAutomaticRecovery("INC-1"));
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void cooldownBlocksAnImmediateSecondRecoveryOfTheSameContainer() {
        guard.recordAttempt(CONTAINER, "INC-OTHER", Instant.now());
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        var blocked = assertThrows(ResponseStatusException.class, () -> service(Runnable::run).beginRecovery("INC-1"));

        assertEquals(HttpStatus.FORBIDDEN, blocked.getStatusCode());
        assertTrue(blocked.getReason().startsWith("Cooldown"));
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void openCircuitBreakerBlocksRecovery() {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) guard.recordOutcome(CONTAINER, false, now);
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        var blocked = assertThrows(ResponseStatusException.class, () -> service(Runnable::run).beginRecovery("INC-1"));

        assertTrue(blocked.getReason().startsWith("Circuit breaker open"));
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void recoveryIsBlockedForAContainerOutsideTheAllowlist() {
        when(docker.isAllowed(CONTAINER)).thenReturn(false);
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        var blocked = assertThrows(ResponseStatusException.class, () -> service(Runnable::run).beginRecovery("INC-1"));

        assertTrue(blocked.getReason().contains("not in the recovery allowlist"));
    }

    /** Regression from the live run: the Production block used to leave no decision on the incident. */
    @Test
    void productionFailureIsDiagnosedAndItsBlockedDecisionIsRecordedButNothingExecutes() {
        environment = "Production";
        policy.mode = "allow_automatically"; // even a permissive policy cannot authorize Production
        diagnosed(RecoveryActionType.START_CONTAINER, 0.95);
        RecoveryOrchestrationService recovery = service(Runnable::run);

        assertFalse(recovery.beginAutomaticRecovery("INC-1"));
        var manual = assertThrows(ResponseStatusException.class, () -> recovery.beginRecovery("INC-1"));

        assertEquals(HttpStatus.FORBIDDEN, manual.getStatusCode());
        verify(executor, never()).execute(any(), any());
        verify(verification, never()).verify(any(), any());
        assertEquals(2, evidence.stream().filter(e -> e.type().equals("recovery")).count(), "automatic and operator decisions are both recorded");
        assertTrue(evidence.stream().allMatch(e -> e.detail().startsWith("Not authorized: START_CONTAINER")
                && e.detail().contains("'container-stopped' at 95% confidence") && e.detail().contains("Production")));
        assertEquals("not_run", current.verificationStatus, "verification stays pending/not run when no action executes");
        assertEquals("unresolved", current.state);
    }

    @Test
    void decisionRecordsAreNeverAuthorizedWhenBlocked() {
        RecoveryDecision blocked = RecoveryDecision.blocked(RecoveryActionType.RESTART_CONTAINER, CONTAINER, "policy-restart", "disabled",
                "oom-killed", 0.9, List.of(), "disabled", false);
        assertFalse(blocked.authorized());
        assertTrue(blocked.summary().startsWith("Not authorized"));
    }

    private static IncidentEntity incident(String state) {
        IncidentEntity incident = new IncidentEntity();
        incident.id = "INC-1"; incident.applicationId = "app-1"; incident.state = state; incident.status = "active_incident";
        incident.component = "docker:" + CONTAINER; incident.verificationStatus = "not_run";
        incident.verificationDetail = "No recovery action has been executed.";
        return incident;
    }

    private static com.relayaegis.dto.ApiDtos.IncidentDto dto(IncidentEntity e) {
        return new com.relayaegis.dto.ApiDtos.IncidentDto(e.id, null, e.applicationId, null, null, e.state, e.status, null, null, null,
                e.component, null, null, null, List.of(), List.of(), null, e.recoveryDurationMs, List.of(), "live", null);
    }

    private static IncidentEntity copy(IncidentEntity source) {
        IncidentEntity incident = incident(source.state);
        incident.status = source.status; incident.verificationStatus = source.verificationStatus;
        incident.verificationDetail = source.verificationDetail; incident.recoveryDurationMs = source.recoveryDurationMs;
        return incident;
    }
}
