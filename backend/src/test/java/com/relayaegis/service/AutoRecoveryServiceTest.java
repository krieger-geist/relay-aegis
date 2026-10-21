package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Policy rules (production block, approval, allowlist, guard) are covered by RecoveryPolicyEngineTest. */
@ExtendWith(MockitoExtension.class)
class AutoRecoveryServiceTest {
    @Mock RecoveryOrchestrationService recovery;

    @Test
    void reportsWhenThePolicyEngineAuthorizedAutomaticRecovery() {
        when(recovery.beginAutomaticRecovery("INC-1")).thenReturn(true);
        assertTrue(new AutoRecoveryService(recovery).startIfAllowed("INC-1"));
        verify(recovery).beginAutomaticRecovery("INC-1");
    }

    @Test
    void reportsWhenRecoveryWasNotAuthorized() {
        when(recovery.beginAutomaticRecovery("INC-1")).thenReturn(false);
        assertFalse(new AutoRecoveryService(recovery).startIfAllowed("INC-1"));
    }

    @Test
    void neverThrowsIntoTheIncidentPipeline() {
        when(recovery.beginAutomaticRecovery("INC-1")).thenThrow(new IllegalStateException("boom"));
        assertFalse(new AutoRecoveryService(recovery).startIfAllowed("INC-1"));
    }
}
