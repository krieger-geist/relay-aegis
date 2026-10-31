package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.event.IncidentDetectedEvent;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Post-detection chain, run asynchronously after the incident and its evidence are committed (monitoring is never
 * blocked): (1) the deterministic diagnosis is recorded immediately - no AI involved; (2) the recovery policy engine
 * decides on the deterministic result and the configured policy - it never waits for AI; (3) only if the
 * deterministic engine escalated, AI reasoning runs on the dedicated AI pool. If that pool is full, the deterministic
 * diagnosis stands and an AI retry is scheduled.
 */
@Component
public class IncidentPipelineService {
    private static final Logger log = LoggerFactory.getLogger(IncidentPipelineService.class);
    private final DiagnosisService diagnosis;
    private final AutoRecoveryService recovery;
    private final Executor aiExecutor;

    public IncidentPipelineService(DiagnosisService diagnosis, AutoRecoveryService recovery,
            @Qualifier("aiExecutor") Executor aiExecutor) {
        this.diagnosis = diagnosis;
        this.recovery = recovery;
        this.aiExecutor = aiExecutor;
    }

    @Async("incidentExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentDetected(IncidentDetectedEvent event) {
        String id = event.incidentId();
        try (var ignored = MDC.putCloseable("incidentId", id)) { handle(id); }
    }

    private void handle(String id) {
        Optional<DiagnosisDto> deterministic = Optional.empty();
        try { deterministic = diagnosis.diagnoseDeterministically(id); }
        catch (RuntimeException ex) { log.warn("Deterministic diagnosis could not be recorded for incident {}: {}", id, ex.getMessage()); }
        // Independently checks environment, policy mode, allowlists, cooldown and circuit breaker; never waits for AI.
        recovery.startIfAllowed(id);
        boolean escalate = deterministic.map(d -> d.engine() == null || "required".equals(d.engine().aiEscalation())).orElse(true);
        if (!escalate) return;
        try {
            aiExecutor.execute(() -> {
                try { diagnosis.diagnoseAutomatically(id); }
                catch (RuntimeException ex) { log.warn("AI escalation could not be completed for incident {}: {}", id, ex.getMessage()); }
            });
        } catch (RejectedExecutionException ex) {
            log.info("AI worker pool busy; keeping the deterministic diagnosis for {} and scheduling an AI retry", id);
            try { diagnosis.diagnoseWithoutAi(id, "worker_busy", "The AI worker pool was busy, so the deterministic diagnosis was kept. AI will be retried automatically."); }
            catch (RuntimeException inner) { log.warn("Deterministic diagnosis could not be recorded for incident {}: {}", id, inner.getMessage()); }
        }
    }
}
