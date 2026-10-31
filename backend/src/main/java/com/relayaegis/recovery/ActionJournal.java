package com.relayaegis.recovery;

import com.relayaegis.entity.RecoveryActionEntity;
import com.relayaegis.repository.RecoveryActionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The durable record of every recovery attempt. The intent is committed before the Docker operation runs, then the
 * entry moves to {@code executed} (or {@code execution_failed}) and finally {@code verified} / {@code not_verified}.
 * An entry still {@code intended} or {@code executed} after a restart belongs to a recovery the previous process did
 * not finish: an {@code intended} one may or may not have reached Docker, so it is marked {@code interrupted} and
 * never repeated blindly; the resumed recovery verifies first.
 */
@Component
public class ActionJournal {
    public static final String INTENDED = "intended";
    public static final String EXECUTED = "executed";
    public static final String EXECUTION_FAILED = "execution_failed";
    public static final String VERIFIED = "verified";
    public static final String NOT_VERIFIED = "not_verified";
    public static final String INTERRUPTED = "interrupted";

    private final RecoveryActionRepository repository;

    public ActionJournal(RecoveryActionRepository repository) { this.repository = repository; }

    public static String newRunId() { return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    /**
     * Commits the intent for the incident's next attempt and returns it. Throws when it cannot be recorded, including
     * when another worker has already recorded that attempt number; the caller must then not execute anything.
     */
    public RecoveryActionEntity intend(String runId, String incidentId, RecoveryDecision decision, String appContainer, boolean automatic) {
        RecoveryActionEntity entry = new RecoveryActionEntity();
        entry.id = "act-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        entry.runId = runId;
        entry.incidentId = incidentId;
        entry.attempt = repository.findTopByIncidentIdOrderByAttemptDesc(incidentId).map(e -> e.attempt).orElse(0) + 1;
        entry.action = String.valueOf(decision.action());
        entry.target = decision.target();
        entry.appContainer = appContainer;
        entry.policyId = decision.policyId();
        entry.policyMode = decision.policyMode();
        entry.automatic = automatic;
        entry.phase = INTENDED;
        entry.intendedAt = Instant.now();
        return repository.saveAndFlush(entry);
    }

    public RecoveryActionEntity executed(RecoveryActionEntity entry, RecoveryExecutor.ExecutionResult result) {
        entry.phase = result.commandSucceeded() ? EXECUTED : EXECUTION_FAILED;
        entry.executionDetail = clip(result.detail());
        entry.executedAt = Instant.now();
        if (!result.commandSucceeded()) entry.finishedAt = entry.executedAt;
        return repository.saveAndFlush(entry);
    }

    /** Records the verification outcome; an interrupted entry keeps its phase so the gap stays visible. */
    public RecoveryActionEntity verified(RecoveryActionEntity entry, VerificationEngine.VerificationResult outcome) {
        if (!INTERRUPTED.equals(entry.phase)) entry.phase = outcome.passed() ? VERIFIED : NOT_VERIFIED;
        entry.verificationStatus = outcome.status();
        entry.verificationDetail = clip(outcome.detail());
        entry.finishedAt = Instant.now();
        return repository.saveAndFlush(entry);
    }

    public RecoveryActionEntity interrupted(RecoveryActionEntity entry, String detail) {
        entry.phase = INTERRUPTED;
        entry.executionDetail = clip(detail);
        return repository.saveAndFlush(entry);
    }

    public Optional<RecoveryActionEntity> latest(String incidentId) { return repository.findTopByIncidentIdOrderByAttemptDesc(incidentId); }

    /** When the run's first attempt was intended, i.e. when the recovery action started. */
    public Optional<Instant> runStartedAt(String runId) { return repository.findTopByRunIdOrderByAttemptAsc(runId).map(e -> e.intendedAt); }

    public List<RecoveryActionEntity> forIncident(String incidentId) { return repository.findByIncidentIdOrderByAttemptAsc(incidentId); }

    public long attempts(String incidentId) { return repository.countByIncidentId(incidentId); }

    public static boolean unfinished(RecoveryActionEntity entry) { return INTENDED.equals(entry.phase) || EXECUTED.equals(entry.phase); }

    /** The authorized decision an entry was recorded for, rebuilt to resume it. */
    public static RecoveryDecision decisionOf(RecoveryActionEntity entry) {
        return new RecoveryDecision(RecoveryActionType.valueOf(entry.action), entry.target, true, false, entry.policyId, entry.policyMode,
                null, null, List.of("Resumed from the action journal (attempt " + entry.attempt + ")."), null);
    }

    private static String clip(String text) { return text == null || text.length() <= 2000 ? text : text.substring(0, 1997) + "..."; }
}
