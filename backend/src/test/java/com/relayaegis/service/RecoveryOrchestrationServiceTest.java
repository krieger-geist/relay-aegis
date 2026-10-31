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
import com.relayaegis.entity.RecoveryActionEntity;
import com.relayaegis.entity.RecoveryPolicyEntity;
import com.relayaegis.observability.RelayMetrics;
import com.relayaegis.recovery.ActionJournal;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryExecutor;
import com.relayaegis.recovery.RecoveryGuard;
import com.relayaegis.recovery.RecoveryPolicyEngine;
import com.relayaegis.recovery.VerificationEngine;
import com.relayaegis.repository.RecoveryActionRepository;
import com.relayaegis.repository.RecoveryEventRepository;
import com.relayaegis.repository.RecoveryPolicyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** The action journal's rows, kept in memory like the recovery_actions table. */
    private final List<RecoveryActionEntity> journalRows = new ArrayList<>();
    private RecoveryActionRepository actions;
    private com.relayaegis.cluster.LeaderElection leadership = com.relayaegis.cluster.LeaderElection.standalone();

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
        actions = inMemoryActions();
        guard = new RecoveryGuard(2, 60, 3, 30, 15, eventRepository, actions);
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
        return new RecoveryOrchestrationService(incidents, applications, diagnosis, engine, executor, verification, guard, events, workers, new RelayMetrics(meters),
                new ActionJournal(actions), leadership, 0);
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
        assertEquals(1, meters.get("relay.recovery.decisions").tags("action", "START_CONTAINER", "decision", "authorized", "mode", "operator").counter().count());
        assertEquals(1, meters.get("relay.recovery.attempts").tags("verification", "passed").counter().count());
        assertEquals(1, meters.get("relay.recovery.duration").tags("outcome", "passed").timer().count());
        assertEquals(1, meters.get("relay.incidents.resolved").tags("resolution", "verified_recovery").counter().count());
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
        assertEquals(2, meters.get("relay.recovery.attempts").tags("verification", "failed").counter().count());
        assertEquals(1, meters.get("relay.recovery.duration").tags("outcome", "failed").timer().count());
        assertTrue(meters.find("relay.incidents.resolved").counters().isEmpty(), "an unverified recovery resolves nothing");
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
        assertEquals(1, meters.get("relay.recovery.decisions").tags("decision", "requires_approval", "mode", "automatic").counter().count());
        assertTrue(meters.find("relay.recovery.attempts").counters().isEmpty());
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
    void eachAttemptIsJournaledFromIntentToVerification() {
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            assertEquals(ActionJournal.INTENDED, journalRows.get(0).phase, "the intent is committed before Docker is called");
            return new RecoveryExecutor.ExecutionResult(true, false, "Started container " + CONTAINER + ".");
        });
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("passed", "Verified.", List.of()));

        service(Runnable::run).beginRecovery("INC-1");

        assertEquals(1, journalRows.size());
        RecoveryActionEntity row = journalRows.get(0);
        assertEquals(1, row.attempt);
        assertEquals("START_CONTAINER", row.action);
        assertEquals(CONTAINER, row.target);
        assertEquals(ActionJournal.VERIFIED, row.phase);
        assertEquals("passed", row.verificationStatus);
        assertTrue(row.executedAt != null && row.finishedAt != null);
    }

    @Test
    void failedVerificationRetriesAreJournaledAsSeparateAttemptsOfOneRun() {
        diagnosed(RecoveryActionType.RESTART_CONTAINER, 0.85);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Restarted container."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("failed", "Still down.", List.of()));

        service(Runnable::run).beginRecovery("INC-1");

        assertEquals(List.of(1, 2), journalRows.stream().map(r -> r.attempt).toList());
        assertTrue(journalRows.stream().allMatch(r -> ActionJournal.NOT_VERIFIED.equals(r.phase)));
        assertEquals(1, journalRows.stream().map(r -> r.runId).distinct().count());
    }

    @Test
    void nothingExecutesWhenTheIntentCannotBeJournaled() {
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("attempt already recorded")).when(actions).saveAndFlush(any());

        service(Runnable::run).beginRecovery("INC-1");

        verify(executor, never()).execute(any(), any());
        assertEquals("unresolved", current.state);
        assertEquals("failed", current.verificationStatus);
        assertTrue(evidence.stream().anyMatch(e -> e.title().equals("Recovery stopped")));
    }

    @Test
    void restartAfterIntentRecordedVerifiesInsteadOfRepeatingTheAction() {
        crashedDuring(1, ActionJournal.INTENDED);
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("passed", "Verified.", List.of()));

        service(Runnable::run).resumeInterruptedRecoveries();

        verify(executor, never()).execute(any(), any());
        assertEquals("recovered", current.state);
        assertEquals("passed", current.verificationStatus);
        RecoveryActionEntity row = journalRows.get(0);
        assertEquals(ActionJournal.INTERRUPTED, row.phase, "an attempt whose result is unknown stays marked as interrupted");
        assertEquals("passed", row.verificationStatus);
        assertTrue(evidence.stream().anyMatch(e -> e.title().equals("Recovery resumed after restart") && e.detail().contains("not repeated")));
        assertEquals(1, meters.get("relay.recovery.resumed").tags("verification", "passed").counter().count());
        verify(events).record(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq("recovery_verified"), any(), any(), anyString(), anyString());
    }

    @Test
    void restartAfterAnExecutedAttemptThatStillFailsRetriesOnlyWithinTheAttemptLimit() {
        crashedDuring(1, ActionJournal.EXECUTED);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Restarted container."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("failed", "Still down.", List.of()));

        service(Runnable::run).resumeInterruptedRecoveries();

        verify(executor, times(1)).execute(any(), any()); // attempt 1 was already executed; limit 2
        assertEquals(List.of(1, 2), journalRows.stream().map(r -> r.attempt).toList());
        assertEquals(ActionJournal.NOT_VERIFIED, journalRows.get(0).phase);
        assertEquals(1, journalRows.stream().map(r -> r.runId).distinct().count(), "the retry continues the interrupted run");
        assertEquals("unresolved", current.state);
        assertTrue(evidence.stream().anyMatch(e -> e.title().equals("Recovery escalated")));
    }

    @Test
    void restartWithTheAttemptLimitUsedUpNeverExecutesAgain() {
        crashedDuring(1, ActionJournal.NOT_VERIFIED);
        crashedDuring(2, ActionJournal.EXECUTED);
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("failed", "Still down.", List.of()));

        service(Runnable::run).resumeInterruptedRecoveries();

        verify(executor, never()).execute(any(), any());
        assertEquals(2, journalRows.size());
        assertEquals("recovery_failed", current.status);
    }

    @Test
    void restartBeforeAnyIntentHandsTheIncidentBackWithoutActing() {
        current = incident("action_taken");
        when(incidents.findInRecovery()).thenAnswer(inv -> List.of(copy(current)));

        service(Runnable::run).resumeInterruptedRecoveries();

        verify(executor, never()).execute(any(), any());
        verify(verification, never()).verify(any(), any());
        assertEquals("unresolved", current.state);
        assertEquals("needs_review", current.status);
        assertEquals("not_run", current.verificationStatus);
    }

    @Test
    void aFollowerRefusesToRunRecovery() {
        leadership = mock(com.relayaegis.cluster.LeaderElection.class);
        when(leadership.isLeader()).thenReturn(false);
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);

        var refused = assertThrows(ResponseStatusException.class, () -> service(Runnable::run).beginRecovery("INC-1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, refused.getStatusCode());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void leadershipLostMidRecoveryStopsBeforeTheNextActionAndLeavesItToTheNewLeader() {
        leadership = mock(com.relayaegis.cluster.LeaderElection.class);
        // leader for the decision and attempt 1; lost before the retry
        when(leadership.isLeader()).thenReturn(true, true, false);
        diagnosed(RecoveryActionType.START_CONTAINER, 0.92);
        when(executor.execute(any(), any())).thenReturn(new RecoveryExecutor.ExecutionResult(true, false, "Started."));
        when(verification.verify(any(), any())).thenReturn(new VerificationEngine.VerificationResult("failed", "Still down.", List.of()));

        service(Runnable::run).beginRecovery("INC-1");

        verify(executor, times(1)).execute(any(), any());
        assertEquals("action_taken", current.state, "no outcome is recorded by an instance that is no longer the leader");
        assertEquals(1, journalRows.size());
        assertEquals(ActionJournal.NOT_VERIFIED, journalRows.get(0).phase);
        verify(events, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void decisionRecordsAreNeverAuthorizedWhenBlocked() {
        RecoveryDecision blocked = RecoveryDecision.blocked(RecoveryActionType.RESTART_CONTAINER, CONTAINER, "policy-restart", "disabled",
                "oom-killed", 0.9, List.of(), "disabled", false);
        assertFalse(blocked.authorized());
        assertTrue(blocked.summary().startsWith("Not authorized"));
    }

    /** A previous process stopped while the incident was in recovery, with this attempt journaled in this phase. */
    private void crashedDuring(int attempt, String phase) {
        current = incident("action_taken");
        when(incidents.findInRecovery()).thenAnswer(inv -> List.of(copy(current)));
        RecoveryActionEntity row = new RecoveryActionEntity();
        row.id = "act-" + attempt; row.runId = "run-1"; row.incidentId = "INC-1"; row.attempt = attempt;
        row.action = "RESTART_CONTAINER"; row.target = CONTAINER; row.appContainer = CONTAINER; row.policyId = "policy-restart";
        row.policyMode = "require_approval"; row.phase = phase; row.intendedAt = Instant.now().minusSeconds(30);
        if (!ActionJournal.INTENDED.equals(phase)) { row.executionDetail = "Restarted container."; row.executedAt = row.intendedAt.plusSeconds(1); }
        journalRows.add(row);
    }

    private RecoveryActionRepository inMemoryActions() {
        RecoveryActionRepository repository = mock(RecoveryActionRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            RecoveryActionEntity row = inv.getArgument(0);
            journalRows.removeIf(r -> r.id.equals(row.id));
            journalRows.add(row);
            journalRows.sort(java.util.Comparator.comparingInt(r -> r.attempt));
            return row;
        });
        when(repository.findTopByIncidentIdOrderByAttemptDesc(anyString())).thenAnswer(inv -> journalRows.stream()
                .filter(r -> r.incidentId.equals(inv.getArgument(0))).reduce((a, b) -> b));
        when(repository.findTopByRunIdOrderByAttemptAsc(anyString())).thenAnswer(inv -> journalRows.stream()
                .filter(r -> r.runId.equals(inv.getArgument(0))).findFirst());
        when(repository.findByIncidentIdOrderByAttemptAsc(anyString())).thenAnswer(inv -> journalRows.stream()
                .filter(r -> r.incidentId.equals(inv.getArgument(0))).toList());
        when(repository.countByIncidentId(anyString())).thenAnswer(inv -> journalRows.stream()
                .filter(r -> r.incidentId.equals(inv.getArgument(0))).count());
        return repository;
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
