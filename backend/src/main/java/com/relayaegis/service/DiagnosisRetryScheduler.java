package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-attempts AI diagnosis, without operator action, for open incidents that fell back to rule-based diagnosis
 * because AI was temporarily unavailable. The scheduler thread is shared with monitoring, so it only dispatches:
 * Gemini calls run on the dedicated AI executor, never on the monitoring or recovery threads. One round at a
 * time, at most {@link #MAX_PER_ROUND} incidents, and a round stops early when AI is still unavailable so a
 * provider outage is not hammered. Never performs recovery.
 */
@Component
public class DiagnosisRetryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DiagnosisRetryScheduler.class);
    static final int MAX_PER_ROUND = 3;

    private final DiagnosisService diagnosis;
    private final Executor aiExecutor;
    private final boolean enabled;
    private final AtomicBoolean roundInProgress = new AtomicBoolean(false);

    public DiagnosisRetryScheduler(DiagnosisService diagnosis, @Qualifier("aiExecutor") Executor aiExecutor,
            @Value("${relay.ai.diagnosis-retry.enabled:true}") boolean enabled) {
        this.diagnosis = diagnosis;
        this.aiExecutor = aiExecutor;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${relay.ai.diagnosis-retry.interval-ms:60000}",
            initialDelayString = "${relay.ai.diagnosis-retry.initial-delay-ms:60000}")
    public void dispatchDueRetries() {
        if (!enabled || !roundInProgress.compareAndSet(false, true)) return;
        try {
            aiExecutor.execute(() -> {
                try { runRound(); }
                finally { roundInProgress.set(false); }
            });
        } catch (RejectedExecutionException ex) {
            roundInProgress.set(false);
            log.info("Background AI retry round skipped: AI executor is busy");
        }
    }

    /** Returns the number of incidents whose AI retry was attempted in this round. */
    int runRound() {
        int attempted = 0;
        for (String id : diagnosis.dueAiRetries(MAX_PER_ROUND)) {
            try {
                Optional<DiagnosisDto> result = diagnosis.retryAiDiagnosis(id);
                if (result.isEmpty()) continue;
                attempted++;
                DiagnosisDto outcome = result.get();
                if (DiagnosisService.PROVIDER_FALLBACK.equals(outcome.provider()) && DiagnosisService.isTransientAiFailure(outcome.aiFailureCode())) {
                    log.info("AI still unavailable ({}); remaining due retries wait for the next round", outcome.aiFailureCode());
                    break;
                }
            } catch (RuntimeException ex) {
                log.warn("Background AI retry could not complete for {}: {}", id, ex.getClass().getSimpleName());
            }
        }
        return attempted;
    }
}
