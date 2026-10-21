package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.relayaegis.event.IncidentDetectedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** Scenario 7 (unit level): AI work never delays the deterministic recovery decision. */
class IncidentPipelineServiceTest {
    private final DiagnosisService diagnosis = mock(DiagnosisService.class);
    private final AutoRecoveryService recovery = mock(AutoRecoveryService.class);

    @Test
    void recoveryPolicyIsEvaluatedBeforeAndIndependentlyOfAiDiagnosis() {
        List<Runnable> aiPool = new ArrayList<>();
        new IncidentPipelineService(diagnosis, recovery, aiPool::add).onIncidentDetected(new IncidentDetectedEvent("INC-1", "app-1"));

        verify(recovery).startIfAllowed("INC-1");
        verify(diagnosis, never()).diagnoseAutomatically(anyString());
        assertEquals(1, aiPool.size(), "diagnosis is handed to the AI pool, not run on the pipeline thread");

        aiPool.get(0).run();
        var order = inOrder(recovery, diagnosis);
        order.verify(recovery).startIfAllowed("INC-1");
        order.verify(diagnosis).diagnoseAutomatically("INC-1");
    }

    @Test
    void aBusyAiPoolStillYieldsARuleBasedDiagnosisImmediately() {
        new IncidentPipelineService(diagnosis, recovery, task -> { throw new RejectedExecutionException("full"); })
                .onIncidentDetected(new IncidentDetectedEvent("INC-1", "app-1"));

        verify(recovery).startIfAllowed("INC-1");
        verify(diagnosis).diagnoseWithoutAi(eq("INC-1"), eq("worker_busy"), anyString());
        verify(diagnosis, never()).diagnoseAutomatically(anyString());
    }

    @Test
    void anAiFailureCannotPreventTheRecoveryDecision() {
        List<Runnable> aiPool = new ArrayList<>();
        org.mockito.Mockito.when(diagnosis.diagnoseAutomatically("INC-1")).thenThrow(new IllegalStateException("AI exploded"));
        new IncidentPipelineService(diagnosis, recovery, aiPool::add).onIncidentDetected(new IncidentDetectedEvent("INC-1", "app-1"));
        aiPool.get(0).run(); // must not throw out of the worker

        verify(recovery).startIfAllowed("INC-1");
    }
}
