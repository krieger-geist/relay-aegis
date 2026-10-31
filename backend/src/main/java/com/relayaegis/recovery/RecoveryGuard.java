package com.relayaegis.recovery;

import com.relayaegis.repository.RecoveryActionRepository;
import com.relayaegis.repository.RecoveryEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds recovery so it can never loop: a per-target cooldown between distinct recovery runs, a per-incident
 * attempt limit, and a per-target circuit breaker that opens after repeated failed verifications and stays open
 * for a fixed period. State is in memory (single backend instance); the per-incident limit also counts recorded
 * recovery events and journaled attempts, so a backend restart cannot reset it.
 */
@Component
public class RecoveryGuard {
    private final int maxAttemptsPerIncident;
    private final Duration cooldown;
    private final int failureThreshold;
    private final Duration failureWindow;
    private final Duration openDuration;
    private final RecoveryEventRepository events;
    private final RecoveryActionRepository actions;

    private final Map<String, Instant> lastRunByTarget = new ConcurrentHashMap<>();
    private final Map<String, Integer> attemptsByIncident = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failuresByTarget = new ConcurrentHashMap<>();
    private final Map<String, Instant> openUntilByTarget = new ConcurrentHashMap<>();

    public RecoveryGuard(@Value("${relay.recovery.max-attempts-per-incident:2}") int maxAttemptsPerIncident,
            @Value("${relay.recovery.cooldown-seconds:60}") long cooldownSeconds,
            @Value("${relay.recovery.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${relay.recovery.circuit.window-minutes:30}") long windowMinutes,
            @Value("${relay.recovery.circuit.open-minutes:15}") long openMinutes,
            RecoveryEventRepository events, RecoveryActionRepository actions) {
        this.maxAttemptsPerIncident = Math.max(1, Math.min(5, maxAttemptsPerIncident));
        this.cooldown = Duration.ofSeconds(Math.max(0, cooldownSeconds));
        this.failureThreshold = Math.max(1, failureThreshold);
        this.failureWindow = Duration.ofMinutes(Math.max(1, windowMinutes));
        this.openDuration = Duration.ofMinutes(Math.max(1, openMinutes));
        this.events = events;
        this.actions = actions;
    }

    public int maxAttemptsPerIncident() { return maxAttemptsPerIncident; }

    /** Why a new recovery run on this target for this incident must not start now, or null if it may. */
    public String blockReason(String target, String incidentId, Instant now) {
        String circuit = circuitReason(target, now);
        if (circuit != null) return circuit;
        Instant last = lastRunByTarget.get(target);
        if (last != null && now.isBefore(last.plus(cooldown))) {
            return "Cooldown: " + target + " was recovered at " + last + "; the next recovery is allowed after " + last.plus(cooldown) + ".";
        }
        int attempts = attempts(incidentId);
        if (attempts >= maxAttemptsPerIncident) {
            return "Retry limit: " + attempts + " recovery attempt(s) already made for this incident (limit " + maxAttemptsPerIncident + "); escalated for human review.";
        }
        return null;
    }

    /** For a retry inside one recovery run: the circuit breaker and attempt limit apply, the cooldown does not. */
    public String retryBlockReason(String target, String incidentId, Instant now) {
        String circuit = circuitReason(target, now);
        if (circuit != null) return circuit;
        int attempts = attempts(incidentId);
        return attempts >= maxAttemptsPerIncident ? "Retry limit of " + maxAttemptsPerIncident + " attempts reached for this incident." : null;
    }

    public void recordAttempt(String target, String incidentId, Instant now) {
        lastRunByTarget.put(target, now);
        attemptsByIncident.merge(incidentId, 1, Integer::sum);
    }

    public void recordOutcome(String target, boolean verified, Instant now) {
        Deque<Instant> failures = failuresByTarget.computeIfAbsent(target, key -> new ArrayDeque<>());
        synchronized (failures) {
            if (verified) { failures.clear(); openUntilByTarget.remove(target); return; }
            failures.addLast(now);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(failureWindow))) failures.removeFirst();
            if (failures.size() >= failureThreshold) openUntilByTarget.put(target, now.plus(openDuration));
        }
    }

    public boolean circuitOpen(String target, Instant now) { return circuitReason(target, now) != null; }

    private String circuitReason(String target, Instant now) {
        Instant openUntil = openUntilByTarget.get(target);
        if (openUntil == null) return null;
        if (now.isBefore(openUntil)) {
            return "Circuit breaker open for " + target + ": " + failureThreshold + " failed recoveries within " + failureWindow.toMinutes()
                    + " minutes. Automatic and manual recovery resume after " + openUntil + ".";
        }
        openUntilByTarget.remove(target);
        return null;
    }

    private int attempts(String incidentId) {
        int recorded = (int) Math.min(Integer.MAX_VALUE, Math.max(events.countByIncidentId(incidentId), actions.countByIncidentId(incidentId)));
        return Math.max(attemptsByIncident.getOrDefault(incidentId, 0), recorded);
    }
}
