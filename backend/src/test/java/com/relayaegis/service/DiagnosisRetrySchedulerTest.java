package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiagnosisRetrySchedulerTest {
    private final DiagnosisService diagnosis = mock(DiagnosisService.class);

    private static DiagnosisDto outcome(String provider, String failureCode) {
        return new DiagnosisDto("INC", "cause", "explanation", "Low", "", List.of(), List.of(), List.of(), provider, null, null,
                List.of(), Instant.now(), "estimated", "automatic", true, "policy", failureCode, 1, null, List.of(), null, null);
    }

    @Test
    void stopsTheRoundWhenAiIsStillUnavailable() {
        when(diagnosis.dueAiRetries(anyInt())).thenReturn(List.of("INC-A", "INC-B", "INC-C"));
        when(diagnosis.retryAiDiagnosis("INC-A")).thenReturn(Optional.of(outcome("deterministic-fallback", "http_503")));

        int attempted = new DiagnosisRetryScheduler(diagnosis, Runnable::run, true).runRound();

        assertEquals(1, attempted);
        verify(diagnosis, never()).retryAiDiagnosis("INC-B");
        verify(diagnosis, never()).retryAiDiagnosis("INC-C");
    }

    @Test
    void continuesWhileAiAnswersAndToleratesAFailingIncident() {
        when(diagnosis.dueAiRetries(anyInt())).thenReturn(List.of("INC-A", "INC-B", "INC-C"));
        when(diagnosis.retryAiDiagnosis("INC-A")).thenReturn(Optional.of(outcome("gemini", null)));
        when(diagnosis.retryAiDiagnosis("INC-B")).thenThrow(new IllegalStateException("database unavailable"));
        when(diagnosis.retryAiDiagnosis("INC-C")).thenReturn(Optional.of(outcome("gemini", null)));

        assertEquals(2, new DiagnosisRetryScheduler(diagnosis, Runnable::run, true).runRound());
    }

    @Test
    void asksForAtMostThreeIncidentsPerRound() {
        when(diagnosis.dueAiRetries(DiagnosisRetryScheduler.MAX_PER_ROUND)).thenReturn(List.of());
        new DiagnosisRetryScheduler(diagnosis, Runnable::run, true).runRound();
        verify(diagnosis).dueAiRetries(3);
    }

    @Test
    void dispatchesToTheIncidentExecutorAndNeverOverlapsRounds() {
        List<Runnable> submitted = new ArrayList<>();
        DiagnosisRetryScheduler scheduler = new DiagnosisRetryScheduler(diagnosis, submitted::add, true);

        scheduler.dispatchDueRetries();
        scheduler.dispatchDueRetries();
        assertEquals(1, submitted.size(), "the scheduler thread does no AI work, and a second round waits for the first");

        when(diagnosis.dueAiRetries(anyInt())).thenReturn(List.of());
        submitted.get(0).run();
        scheduler.dispatchDueRetries();
        assertEquals(2, submitted.size(), "a new round may start once the previous one finished");
    }

    @Test
    void doesNothingWhenDisabled() {
        List<Runnable> submitted = new ArrayList<>();
        new DiagnosisRetryScheduler(diagnosis, submitted::add, false).dispatchDueRetries();
        assertEquals(0, submitted.size());
    }
}
