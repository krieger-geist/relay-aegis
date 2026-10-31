package com.relayaegis.service;

import com.relayaegis.cluster.LeaderElection;
import com.relayaegis.cluster.LeadershipGrantedEvent;
import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RecoveryActionEntity;
import com.relayaegis.observability.RelayMetrics;
import com.relayaegis.recovery.ActionJournal;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryExecutor;
import com.relayaegis.recovery.RecoveryGuard;
import com.relayaegis.recovery.RecoveryPolicyEngine;
import com.relayaegis.recovery.VerificationEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Policy-authorized, bounded, audited recovery. The deterministic diagnosis selects the action, the
 * {@link RecoveryPolicyEngine} authorizes it, the {@link RecoveryExecutor} performs it through allowlisted Docker
 * operations, and the {@link VerificationEngine} decides the outcome from real observations. Failed verification is
 * retried with exponential backoff up to the per-incident limit, then escalated. Every step is recorded as incident
 * evidence and a recovery event, and every attempt in the durable {@link ActionJournal}, from which a restarted
 * backend resumes unfinished recoveries.
 */
@Service
public class RecoveryOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(RecoveryOrchestrationService.class);
    private final IncidentService incidents;
    private final ApplicationService applications;
    private final DiagnosisService diagnosis;
    private final RecoveryPolicyEngine policyEngine;
    private final RecoveryExecutor executor;
    private final VerificationEngine verification;
    private final RecoveryGuard guard;
    private final RecoveryEventService recoveryEvents;
    private final Executor incidentExecutor;
    private final RelayMetrics metrics;
    private final ActionJournal journal;
    private final LeaderElection leadership;
    private final Duration backoffBase;
    /** Process-local guard: one recovery per incident at a time (single backend instance). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public RecoveryOrchestrationService(IncidentService incidents, ApplicationService applications, DiagnosisService diagnosis,
            RecoveryPolicyEngine policyEngine, RecoveryExecutor executor, VerificationEngine verification, RecoveryGuard guard,
            RecoveryEventService recoveryEvents, @Qualifier("incidentExecutor") Executor incidentExecutor, RelayMetrics metrics, ActionJournal journal,
            LeaderElection leadership,
            @Value("${relay.recovery.retry-backoff-ms:5000}") long backoffMs) {
        this.incidents = incidents;
        this.applications = applications;
        this.diagnosis = diagnosis;
        this.policyEngine = policyEngine;
        this.executor = executor;
        this.verification = verification;
        this.guard = guard;
        this.recoveryEvents = recoveryEvents;
        this.incidentExecutor = incidentExecutor;
        this.metrics = metrics;
        this.journal = journal;
        this.leadership = leadership;
        this.backoffBase = Duration.ofMillis(Math.max(0, Math.min(60_000, backoffMs)));
    }

    /** Operator-approved recovery ("Run recovery"). Throws with the policy reason when the action is not authorized. */
    public IncidentDto beginRecovery(String incidentId) {
        return begin(incidentId, false);
    }

    /** Pipeline-initiated recovery. Returns false (and records why) when the policy does not authorize it. */
    public boolean beginAutomaticRecovery(String incidentId) {
        try {
            IncidentDto result = begin(incidentId, true);
            return "action_taken".equals(result.state());
        } catch (ResponseStatusException ex) {
            log.info("Automatic recovery not started for {}: {}", incidentId, ex.getReason());
            return false;
        }
    }

    private IncidentDto begin(String incidentId, boolean automatic) {
        if (!leadership.isLeader()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This RELAY instance is not the leader; only the leader runs recovery. Retry in a few seconds.");
        }
        IncidentEntity incident = incidents.findEntity(incidentId);
        ApplicationEntity app = applications.findEntity(incident.applicationId);
        if ("Production".equalsIgnoreCase(app.environment)) {
            // Never executes anything; the blocked decision is still recorded so the incident shows why no action ran.
            recordDecision(incidentId, policyEngine.decide(incident, app, evaluate(incidentId), automatic), automatic);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Recovery actions against Production are disabled. Use an explicitly approved non-production target.");
        }
        if ("recovered".equals(incident.state) && "passed".equals(incident.verificationStatus)) return incidents.toDto(incident);
        if ("recovered".equals(incident.state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This incident was resolved after the application was observed healthy again. A new incident opens if the failure recurs.");
        }
        if ("verifying".equals(incident.state) || "action_taken".equals(incident.state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A recovery operation is already in progress for this incident.");
        }
        if (!inFlight.add(incidentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A recovery operation is already in progress for this incident.");
        }
        try {
            DiagnosticResult deterministic = evaluate(incidentId);
            RecoveryDecision decision = policyEngine.decide(incident, app, deterministic, automatic);
            recordDecision(incidentId, decision, automatic);
            if (!decision.authorized()) {
                inFlight.remove(incidentId);
                if (automatic && !decision.action().mutates()) return incidents.get(incidentId);
                throw new ResponseStatusException(decision.requiresApproval() || !decision.action().mutates() ? HttpStatus.CONFLICT : HttpStatus.FORBIDDEN,
                        decision.blockedReason());
            }
            return startRecovery(incidentId, decision, automatic);
        } catch (RuntimeException ex) {
            inFlight.remove(incidentId);
            throw ex;
        }
    }

    private DiagnosticResult evaluate(String incidentId) {
        try { return diagnosis.evaluateDeterministically(incidentId); }
        catch (RuntimeException ex) {
            log.warn("Deterministic evaluation failed for {}: {}", incidentId, ex.getClass().getSimpleName());
            return null;
        }
    }

    private void recordDecision(String incidentId, RecoveryDecision decision, boolean automatic) {
        metrics.recoveryDecision(String.valueOf(decision.action()),
                decision.authorized() ? "authorized" : decision.requiresApproval() ? "requires_approval" : "blocked", automatic);
        IncidentEntity incident = incidents.findEntity(incidentId);
        incidents.appendEvidence(incident, new EvidenceItemDto(evidenceId(), "recovery",
                (automatic ? "Automatic" : "Operator") + " recovery policy decision", decision.summary()
                        + (decision.reasons().isEmpty() ? "" : " Reasons: " + String.join(" ", decision.reasons())),
                "RELAY recovery policy engine", Instant.now(), true));
        incidents.save(incident);
    }

    private IncidentDto startRecovery(String incidentId, RecoveryDecision decision, boolean automatic) {
        IncidentEntity incident = incidents.findEntity(incidentId);
        // Re-read under the guard so a recovery that finished between the checks above is not repeated.
        if ("verifying".equals(incident.state) || "action_taken".equals(incident.state) || "recovered".equals(incident.state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A recovery operation is already in progress for this incident.");
        }
        String previousState = incident.state, previousStatus = incident.status;
        String previousVerification = incident.verificationStatus, previousDetail = incident.verificationDetail;
        Instant startedAt = Instant.now();
        incident.state = "action_taken";
        incident.status = "repairing";
        incident.verificationStatus = "pending";
        incident.verificationDetail = "The authorized recovery action is running; post-action verification has not completed.";
        incidents.updateTimelineStep(incident, 3, "Action taken", "Authorized " + decision.action() + " on " + decision.target() + " started.", "current", "system", startedAt);
        incident = incidents.save(incident);
        String runId = ActionJournal.newRunId();
        try {
            incidentExecutor.execute(() -> {
                try (var ignored = MDC.putCloseable("incidentId", incidentId)) {
                    ApplicationEntity app = applications.findEntity(incidents.findEntity(incidentId).applicationId);
                    runAttempts(incidentId, app, decision, automatic, runId, startedAt, null, "No action executed.");
                }
                finally { inFlight.remove(incidentId); }
            });
        } catch (RejectedExecutionException ex) {
            // Do not leave the incident stuck in "action_taken" when no worker will ever run the action.
            incident.state = previousState; incident.status = previousStatus;
            incident.verificationStatus = previousVerification; incident.verificationDetail = previousDetail;
            incidents.updateTimelineStep(incident, 3, "Action taken", "No recovery action executed; the incident worker queue was full.", "pending", "system", null);
            incidents.save(incident);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The incident worker queue is full. Retry the recovery shortly.");
        }
        return incidents.toDto(incident);
    }

    /**
     * Executes and verifies attempts until verification passes or the guard stops further retries, then records the
     * outcome. Each attempt's intent is journaled before Docker is called. A resumed run passes the outcome of the
     * attempt it re-verified, so the loop starts at the retry decision instead of executing again.
     */
    private void runAttempts(String incidentId, ApplicationEntity app, RecoveryDecision decision, boolean automatic, String runId,
            Instant startedAt, VerificationEngine.VerificationResult outcome, String lastExecution) {
        int attemptsThisRun = 0;
        while (true) {
            if (!leadership.isLeader()) { abandon(incidentId); return; }
            if (outcome != null) {
                if (outcome.passed()) break;
                String stop = guard.retryBlockReason(decision.target(), incidentId, Instant.now());
                if (stop != null) {
                    appendEvidence(incidentId, "recovery", "Recovery escalated", stop + " Recovery was not verified; human review is required.");
                    break;
                }
                if (attemptsThisRun > 0) sleep(Math.min(60_000, backoffBase.toMillis() * (1L << Math.min(5, attemptsThisRun - 1))));
            }
            RecoveryActionEntity entry;
            try {
                entry = journal.intend(runId, incidentId, decision, app.dockerContainer, automatic);
            } catch (RuntimeException ex) {
                log.warn("Recovery intent for {} could not be journaled: {}", incidentId, ex.getClass().getSimpleName());
                outcome = new VerificationEngine.VerificationResult("failed",
                        "No action was executed: its intent could not be recorded in the action journal.", java.util.List.of());
                appendEvidence(incidentId, "recovery", "Recovery stopped", outcome.detail());
                break;
            }
            attemptsThisRun++;
            guard.recordAttempt(decision.target(), incidentId, Instant.now());
            RecoveryExecutor.ExecutionResult execution = executor.execute(decision, app.dockerContainer);
            entry = journal.executed(entry, execution);
            lastExecution = execution.detail();
            appendEvidence(incidentId, "recovery", "Recovery attempt " + entry.attempt + ": " + decision.action(), execution.detail());
            outcome = execution.commandSucceeded() ? verification.verify(app, decision)
                    : new VerificationEngine.VerificationResult("failed", "The recovery action could not be executed: " + execution.detail(), java.util.List.of());
            if (execution.commandSucceeded()) journal.verified(entry, outcome);
            appendEvidence(incidentId, "verification", "Verification after attempt " + entry.attempt + ": " + outcome.status(), outcome.detail());
            metrics.recoveryAttempt(String.valueOf(decision.action()), outcome.status());
            guard.recordOutcome(decision.target(), outcome.passed(), Instant.now());
        }
        if (!leadership.isLeader()) { abandon(incidentId); return; }
        finish(incidentId, decision, outcome, lastExecution, startedAt);
    }

    /**
     * Leadership was lost mid-recovery: stop without executing or recording an outcome. The incident stays in
     * recovery and the journal shows how far it got, so the new leader resumes it.
     */
    private void abandon(String incidentId) {
        log.warn("Leadership lost during recovery of {}; leaving it to the new leader to resume from the journal", incidentId);
    }

    private void finish(String incidentId, RecoveryDecision decision, VerificationEngine.VerificationResult outcome, String lastExecution, Instant startedAt) {
        boolean passed = outcome.passed();
        Instant finishedAt = Instant.now();
        long duration = Math.max(1, Duration.between(startedAt, finishedAt).toMillis());
        long attempts = journal.attempts(incidentId);
        IncidentEntity incident = incidents.findEntity(incidentId);
        String verificationStatus = passed ? "passed" : outcome.status();
        incident.verificationStatus = verificationStatus;
        incident.verificationDetail = outcome.detail();
        incident.recoveryDurationMs = passed || "failed".equals(verificationStatus) ? duration : null;
        incident.state = passed ? "recovered" : "unresolved";
        if (passed) incidents.cancelPendingAiRetry(incident);
        incident.status = passed ? "recovery_verified" : ("timed_out".equals(verificationStatus) ? "needs_review" : "recovery_failed");
        incident.recoveredAt = passed ? finishedAt : null;
        incidents.updateTimelineStep(incident, 3, "Action taken", lastExecution + " (" + attempts + " attempt(s))", "completed", "system", finishedAt);
        incidents.updateTimelineStep(incident, 4, "Verification", outcome.detail(),
                passed ? "completed" : ("timed_out".equals(verificationStatus) ? "unresolved" : "failed"), "system", finishedAt);
        incidents.updateTimelineStep(incident, 5, "Recovery verified",
                passed ? "Post-action verification passed." : "Verification not established as successful; escalated for human review.",
                passed ? "completed" : "unresolved", "system", finishedAt);
        incidents.save(incident);
        metrics.recoveryCompleted(String.valueOf(decision.action()), verificationStatus, duration);
        if (passed) metrics.incidentResolved(incident.applicationId, "verified_recovery", incident.detectedAt, finishedAt);
        recoveryEvents.record(incident.id, incident.applicationName,
                (passed ? "Recovery executed and verified: " : "Recovery attempted; not verified: ") + decision.action() + " on " + decision.target(),
                passed ? "recovery_verified" : ("timed_out".equals(verificationStatus) ? "needs_review" : "recovery_failed"),
                incident.detectionDurationMs, incident.recoveryDurationMs,
                outcome.detail() + " Attempts: " + attempts + ". Policy: " + decision.policyId() + " (" + decision.policyMode() + ").", "live");
    }

    /**
     * Finishes recoveries that a previous backend process left running (incident still "action_taken" or
     * "verifying"). Nothing is executed again blindly: the journal's last attempt is re-verified first, and further
     * attempts follow only through the same guard (attempt limit, circuit breaker) as a normal run. An incident with
     * no journaled attempt never reached Docker and is handed back for review. Runs whenever this instance becomes the
     * leader (at startup for a single instance), so a recovery the previous leader left is taken over.
     */
    @EventListener(LeadershipGrantedEvent.class)
    public void resumeInterruptedRecoveries() {
        try {
            for (IncidentEntity incident : incidents.findInRecovery()) {
                try { resume(incident.id); }
                catch (RuntimeException ex) { log.warn("Could not resume recovery for {}: {}", incident.id, ex.getClass().getSimpleName()); }
            }
        } catch (RuntimeException ex) {
            log.warn("Interrupted recoveries could not be listed: {}", ex.getClass().getSimpleName());
        }
    }

    void resume(String incidentId) {
        if (!inFlight.add(incidentId)) return;
        try {
            RecoveryActionEntity last = journal.latest(incidentId).orElse(null);
            if (last == null) {
                handBackUnstarted(incidentId);
                inFlight.remove(incidentId);
                return;
            }
            incidentExecutor.execute(() -> {
                try (var ignored = MDC.putCloseable("incidentId", incidentId)) { resumeRecovery(incidentId, last); }
                finally { inFlight.remove(incidentId); }
            });
        } catch (RuntimeException ex) {
            inFlight.remove(incidentId);
            throw ex;
        }
    }

    private void resumeRecovery(String incidentId, RecoveryActionEntity last) {
        RecoveryDecision decision = ActionJournal.decisionOf(last);
        ApplicationEntity app = applications.findEntity(incidents.findEntity(incidentId).applicationId);
        String note;
        if (ActionJournal.INTENDED.equals(last.phase)) {
            note = "RELAY stopped after recording the intent for attempt " + last.attempt + " (" + last.action + " on " + last.target
                    + "); whether Docker received it is unknown, so it is not repeated. Verifying the current state instead.";
            last = journal.interrupted(last, note);
        } else {
            note = "RELAY stopped during recovery after attempt " + last.attempt + " (" + last.action + " on " + last.target + ", phase "
                    + last.phase + "). The action is not repeated; verifying the current state.";
        }
        appendEvidence(incidentId, "recovery", "Recovery resumed after restart", note);
        VerificationEngine.VerificationResult outcome = verification.verify(app, decision);
        if (ActionJournal.unfinished(last) || ActionJournal.INTERRUPTED.equals(last.phase)) journal.verified(last, outcome);
        appendEvidence(incidentId, "verification", "Verification after resume: " + outcome.status(), outcome.detail());
        metrics.recoveryResumed(String.valueOf(decision.action()), outcome.status());
        guard.recordOutcome(decision.target(), outcome.passed(), Instant.now());
        Instant startedAt = journal.runStartedAt(last.runId).orElse(last.intendedAt);
        runAttempts(incidentId, app, decision, last.automatic, last.runId, startedAt, outcome,
                last.executionDetail == null ? "Interrupted before the action result was recorded." : last.executionDetail);
    }

    /** The worker never journaled an intent, so nothing reached Docker: return the incident for review. */
    private void handBackUnstarted(String incidentId) {
        IncidentEntity incident = incidents.findEntity(incidentId);
        incident.state = "unresolved";
        incident.status = "needs_review";
        incident.verificationStatus = "not_run";
        incident.verificationDetail = "RELAY restarted before the authorized recovery action ran; nothing was executed. Run recovery again if it is still needed.";
        incidents.updateTimelineStep(incident, 3, "Action taken", "No recovery action executed; RELAY restarted before it ran.", "pending", "system", null);
        incidents.appendEvidence(incident, new EvidenceItemDto(evidenceId(), "recovery", "Recovery not started",
                incident.verificationDetail, "RELAY recovery controller", Instant.now(), true));
        incidents.save(incident);
    }

    /** The journaled attempts for an incident, oldest first. */
    public java.util.List<RecoveryActionEntity> actions(String incidentId) {
        incidents.findEntity(incidentId);
        return journal.forIncident(incidentId);
    }

    private void appendEvidence(String incidentId, String type, String title, String detail) {
        IncidentEntity incident = incidents.findEntity(incidentId);
        incidents.appendEvidence(incident, new EvidenceItemDto(evidenceId(), type, title, detail, "RELAY recovery controller", Instant.now(), true));
        incidents.save(incident);
    }

    private static String evidenceId() { return "ev-" + UUID.randomUUID().toString().substring(0, 8); }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
