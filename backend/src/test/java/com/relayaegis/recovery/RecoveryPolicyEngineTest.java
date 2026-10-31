package com.relayaegis.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RecoveryPolicyEntity;
import com.relayaegis.repository.RecoveryEventRepository;
import com.relayaegis.repository.RecoveryPolicyRepository;
import com.relayaegis.service.DockerControlService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryPolicyEngineTest {
    private static final String APP = "relay-aegis-test-target";
    private static final String DB = "relay-aegis-db";
    private RecoveryPolicyRepository policies;
    private DockerControlService docker;
    private RecoveryEventRepository events;
    private RecoveryGuard guard;
    private RecoveryPolicyEngine engine;
    private IncidentEntity incident;
    private ApplicationEntity app;

    @BeforeEach
    void setUp() {
        policies = mock(RecoveryPolicyRepository.class);
        docker = mock(DockerControlService.class);
        when(docker.isEnabled()).thenReturn(true);
        when(docker.isAllowed(APP)).thenReturn(true);
        events = mock(RecoveryEventRepository.class);
        guard = new RecoveryGuard(2, 60, 3, 30, 15, events, org.mockito.Mockito.mock(com.relayaegis.repository.RecoveryActionRepository.class));
        engine = new RecoveryPolicyEngine(policies, docker, guard);
        policy("policy-restart", "allow_automatically");
        policy("policy-dependency-restart", "disabled");
        incident = new IncidentEntity(); incident.id = "INC-1";
        app = new ApplicationEntity(); app.id = "app-1"; app.environment = "Development"; app.dockerContainer = APP;
    }

    private void policy(String id, String mode) {
        RecoveryPolicyEntity policy = new RecoveryPolicyEntity(); policy.id = id; policy.action = id; policy.mode = mode;
        when(policies.findById(id)).thenReturn(Optional.of(policy));
    }

    private static DiagnosticResult result(RecoveryActionType action, String target) {
        DiagnosticFinding finding = DiagnosticFinding.builder("p", "Pattern", "runtime").confidence(0.9)
                .support(new EvidenceRef("e1", "runtime", "observed")).action(action, target, "text").build();
        return new DiagnosticResult(List.of(finding), false, null, List.of("runtime"), List.of(), Instant.now());
    }

    @Test
    void authorizesAnAllowlistedStartUnderAllowAutomatically() {
        RecoveryDecision decision = engine.decide(incident, app, result(RecoveryActionType.START_CONTAINER, APP), true);
        assertTrue(decision.authorized());
        assertEquals("policy-restart", decision.policyId());
        assertEquals("p", decision.basedOnPattern());
        assertNull(decision.blockedReason());
    }

    @Test
    void neverActsOnProduction() {
        app.environment = "Production";
        RecoveryDecision decision = engine.decide(incident, app, result(RecoveryActionType.START_CONTAINER, APP), false);
        assertFalse(decision.authorized());
        assertTrue(decision.blockedReason().contains("Production"));
    }

    @Test
    void automaticRunNeedsAllowAutomaticallyButAnOperatorClickIsApproval() {
        policy("policy-restart", "require_approval");
        RecoveryDecision automatic = engine.decide(incident, app, result(RecoveryActionType.RESTART_CONTAINER, APP), true);
        assertFalse(automatic.authorized());
        assertTrue(automatic.requiresApproval());
        assertTrue(engine.decide(incident, app, result(RecoveryActionType.RESTART_CONTAINER, APP), false).authorized());
    }

    @Test
    void disabledPolicyBlocksEvenManualRuns() {
        policy("policy-restart", "disabled");
        RecoveryDecision decision = engine.decide(incident, app, result(RecoveryActionType.RESTART_CONTAINER, APP), false);
        assertFalse(decision.authorized());
        assertTrue(decision.blockedReason().contains("disabled"));
    }

    @Test
    void nonMutatingFindingsNeverRunAutomatically() {
        for (RecoveryActionType action : List.of(RecoveryActionType.ESCALATE, RecoveryActionType.INVESTIGATE, RecoveryActionType.NONE)) {
            assertFalse(engine.decide(incident, app, result(action, null), true).authorized(), action.name());
        }
    }

    @Test
    void operatorMayRequestARestartWhenTheEngineOnlyRecommendsInvestigation() {
        RecoveryDecision decision = engine.decide(incident, app, result(RecoveryActionType.INVESTIGATE, null), false);
        assertTrue(decision.authorized());
        assertEquals(RecoveryActionType.RESTART_CONTAINER, decision.action());
        assertEquals(APP, decision.target());
    }

    @Test
    void dependencyRestartUsesItsOwnOptInPolicyAndAllowlist() {
        RecoveryDecision disabled = engine.decide(incident, app, result(RecoveryActionType.RESTART_DEPENDENCY, DB), true);
        assertFalse(disabled.authorized());
        assertTrue(disabled.blockedReason().contains("not in the recovery allowlist"));
        when(docker.isAllowed(DB)).thenReturn(true);
        assertTrue(engine.decide(incident, app, result(RecoveryActionType.RESTART_DEPENDENCY, DB), true).blockedReason().contains("disabled"));
        policy("policy-dependency-restart", "allow_automatically");
        RecoveryDecision allowed = engine.decide(incident, app, result(RecoveryActionType.RESTART_DEPENDENCY, DB), true);
        assertTrue(allowed.authorized());
        assertEquals("policy-dependency-restart", allowed.policyId());
    }

    @Test
    void networkReconnectHasItsOwnOptInPolicyIndependentOfDependencyRestart() {
        when(docker.isAllowed(DB)).thenReturn(true);
        policy("policy-dependency-restart", "allow_automatically");
        policy("policy-network-reconnect", "disabled");
        RecoveryDecision disabled = engine.decide(incident, app, result(RecoveryActionType.RECONNECT_NETWORK, DB), false);
        assertFalse(disabled.authorized(), "enabling dependency restarts does not enable network reconnects");
        assertEquals("policy-network-reconnect", disabled.policyId());
        assertTrue(disabled.blockedReason().contains("disabled"));
        policy("policy-network-reconnect", "require_approval");
        assertFalse(engine.decide(incident, app, result(RecoveryActionType.RECONNECT_NETWORK, DB), true).authorized(), "automatic runs need allow_automatically");
        RecoveryDecision approved = engine.decide(incident, app, result(RecoveryActionType.RECONNECT_NETWORK, DB), false);
        assertTrue(approved.authorized(), "an operator click is the approval");
        when(docker.isAllowed(DB)).thenReturn(false);
        assertTrue(engine.decide(incident, app, result(RecoveryActionType.RECONNECT_NETWORK, DB), false).blockedReason().contains("not in the recovery allowlist"));
        app.environment = "Production";
        when(docker.isAllowed(DB)).thenReturn(true);
        assertFalse(engine.decide(incident, app, result(RecoveryActionType.RECONNECT_NETWORK, DB), false).authorized(), "never on Production");
    }

    @Test
    void dockerDisabledBlocksMutation() {
        when(docker.isEnabled()).thenReturn(false);
        assertTrue(engine.decide(incident, app, result(RecoveryActionType.START_CONTAINER, APP), false).blockedReason().contains("disabled by configuration"));
    }

    @Test
    void attemptLimitCountsRecordedEventsSoARestartCannotResetIt() {
        when(events.countByIncidentId("INC-1")).thenReturn(2L);
        RecoveryDecision decision = engine.decide(incident, app, result(RecoveryActionType.START_CONTAINER, APP), true);
        assertFalse(decision.authorized());
        assertTrue(decision.blockedReason().startsWith("Retry limit"));
    }

    @Test
    void guardCooldownCircuitBreakerAndRecovery() {
        Instant now = Instant.now();
        guard.recordAttempt(APP, "INC-0", now);
        assertTrue(guard.blockReason(APP, "INC-1", now.plusSeconds(10)).startsWith("Cooldown"));
        assertNull(guard.blockReason(APP, "INC-1", now.plusSeconds(61)));

        guard.recordOutcome(APP, false, now);
        guard.recordOutcome(APP, false, now);
        assertFalse(guard.circuitOpen(APP, now));
        guard.recordOutcome(APP, false, now);
        assertTrue(guard.circuitOpen(APP, now));
        assertTrue(guard.retryBlockReason(APP, "INC-1", now).startsWith("Circuit breaker open"));
        assertFalse(guard.circuitOpen(APP, now.plus(Duration.ofMinutes(16))));

        guard.recordOutcome(APP, false, now);
        guard.recordOutcome(APP, true, now); // a verified recovery closes the breaker and clears failures
        assertFalse(guard.circuitOpen(APP, now));
    }

    @Test
    void failuresOutsideTheWindowDoNotOpenTheCircuit() {
        Instant start = Instant.now();
        guard.recordOutcome(APP, false, start);
        guard.recordOutcome(APP, false, start.plus(Duration.ofMinutes(20)));
        guard.recordOutcome(APP, false, start.plus(Duration.ofMinutes(40)));
        assertFalse(guard.circuitOpen(APP, start.plus(Duration.ofMinutes(40))));
    }
}
