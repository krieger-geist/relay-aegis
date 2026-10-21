package com.relayaegis.service;

import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Policy-authorized, bounded, audited recovery. The deterministic diagnosis selects the action, the
 * {@link RecoveryPolicyEngine} authorizes it, the {@link RecoveryExecutor} performs it through allowlisted Docker
 * operations, and the {@link VerificationEngine} decides the outcome from real observations. Failed verification is
 * retried with exponential backoff up to the per-incident limit, then escalated. Every step is recorded as incident
 * evidence and a recovery event.
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
    private final Duration backoffBase;
    /** Process-local guard: one recovery per incident at a time (single backend instance). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public RecoveryOrchestrationService(IncidentService incidents, ApplicationService applications, DiagnosisService diagnosis,
            RecoveryPolicyEngine policyEngine, RecoveryExecutor executor, VerificationEngine verification, RecoveryGuard guard,
            RecoveryEventService recoveryEvents, @Qualifier("incidentExecutor") Executor incidentExecutor,
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
            return startRecovery(incidentId, decision);
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
        IncidentEntity incident = incidents.findEntity(incidentId);
        incidents.appendEvidence(incident, new EvidenceItemDto(evidenceId(), "recovery",
                (automatic ? "Automatic" : "Operator") + " recovery policy decision", decision.summary()
                        + (decision.reasons().isEmpty() ? "" : " Reasons: " + String.join(" ", decision.reasons())),
                "RELAY recovery policy engine", Instant.now(), true));
        incidents.save(incident);
    }

    private IncidentDto startRecovery(String incidentId, RecoveryDecision decision) {
        IncidentEntity incident = incidents.findEntity(incidentId);
        // Re-read under the guard so a recovery that finished between the checks above is not repeated.
        if ("verifying".equals(incident.state) || "action_taken".equals(incident.state) || "recovered".equals(incident.state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A recovery operation is already in progress for this incident.");
        }
        String previousState = incident.state, previousStatus = incident.status;
        String previousVerification = incident.verificationStatus, previousDetail = incident.verificationDetail;
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        incident.state = "action_taken";
        incident.status = "repairing";
        incident.verificationStatus = "pending";
        incident.verificationDetail = "The authorized recovery action is running; post-action verification has not completed.";
        incidents.updateTimelineStep(incident, 3, "Action taken", "Authorized " + decision.action() + " on " + decision.target() + " started.", "current", "system", startedAt);
        incident = incidents.save(incident);
        try {
            incidentExecutor.execute(() -> {
                try { executeRecovery(incidentId, decision, startedNanos); }
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

    private void executeRecovery(String incidentId, RecoveryDecision decision, long startedNanos) {
        ApplicationEntity app = applications.findEntity(incidents.findEntity(incidentId).applicationId);
        VerificationEngine.VerificationResult outcome = null;
        String lastExecution = "No action executed.";
        int attempt = 0;
        while (true) {
            attempt++;
            guard.recordAttempt(decision.target(), incidentId, Instant.now());
            RecoveryExecutor.ExecutionResult execution = executor.execute(decision, app.dockerContainer);
            lastExecution = execution.detail();
            appendEvidence(incidentId, "recovery", "Recovery attempt " + attempt + ": " + decision.action(), execution.detail());
            outcome = execution.commandSucceeded() ? verification.verify(app, decision)
                    : new VerificationEngine.VerificationResult("failed", "The recovery action could not be executed: " + execution.detail(), java.util.List.of());
            appendEvidence(incidentId, "verification", "Verification after attempt " + attempt + ": " + outcome.status(), outcome.detail());
            guard.recordOutcome(decision.target(), outcome.passed(), Instant.now());
            if (outcome.passed()) break;
            String stop = guard.retryBlockReason(decision.target(), incidentId, Instant.now());
            if (stop != null) {
                appendEvidence(incidentId, "recovery", "Recovery escalated", stop + " Recovery was not verified; human review is required.");
                break;
            }
            long backoff = backoffBase.toMillis() * (1L << Math.min(5, attempt - 1));
            sleep(Math.min(60_000, backoff));
        }

        boolean passed = outcome.passed();
        Instant finishedAt = Instant.now();
        long duration = Math.max(1, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
        IncidentEntity incident = incidents.findEntity(incidentId);
        String verificationStatus = passed ? "passed" : outcome.status();
        incident.verificationStatus = verificationStatus;
        incident.verificationDetail = outcome.detail();
        incident.recoveryDurationMs = passed || "failed".equals(verificationStatus) ? duration : null;
        incident.state = passed ? "recovered" : "unresolved";
        if (passed) incidents.cancelPendingAiRetry(incident);
        incident.status = passed ? "recovery_verified" : ("timed_out".equals(verificationStatus) ? "needs_review" : "recovery_failed");
        incident.recoveredAt = passed ? finishedAt : null;
        incidents.updateTimelineStep(incident, 3, "Action taken", lastExecution + " (" + attempt + " attempt(s))", "completed", "system", finishedAt);
        incidents.updateTimelineStep(incident, 4, "Verification", outcome.detail(),
                passed ? "completed" : ("timed_out".equals(verificationStatus) ? "unresolved" : "failed"), "system", finishedAt);
        incidents.updateTimelineStep(incident, 5, "Recovery verified",
                passed ? "Post-action verification passed." : "Verification not established as successful; escalated for human review.",
                passed ? "completed" : "unresolved", "system", finishedAt);
        incidents.save(incident);
        recoveryEvents.record(incident.id, incident.applicationName,
                (passed ? "Recovery executed and verified: " : "Recovery attempted; not verified: ") + decision.action() + " on " + decision.target(),
                passed ? "recovery_verified" : ("timed_out".equals(verificationStatus) ? "needs_review" : "recovery_failed"),
                incident.detectionDurationMs, incident.recoveryDurationMs,
                outcome.detail() + " Attempts: " + attempt + ". Policy: " + decision.policyId() + " (" + decision.policyMode() + ").", "live");
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
