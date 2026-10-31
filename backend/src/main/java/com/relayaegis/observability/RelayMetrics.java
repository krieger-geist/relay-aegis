package com.relayaegis.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * RELAY's own operating metrics, exported at {@code /actuator/prometheus}. Every value is recorded at the moment the
 * corresponding record is written (a check stored, an incident opened or resolved, a policy decision or recovery
 * attempt recorded), so the metrics never claim more than the incident records do.
 *
 * <p>Tags stay low-cardinality: application ids (bounded by registered systems), outcomes, actions and decisions.
 * Incident ids are never used as tags.
 */
@Component
public class RelayMetrics {
    /** Detection and recovery buckets, from seconds to an hour: the range the chaos runs and SLOs care about. */
    private static final Duration[] INCIDENT_BUCKETS = {
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(30),
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofHours(1)};

    private final MeterRegistry registry;
    private final Set<String> knownApplications = ConcurrentHashMap.newKeySet();

    public RelayMetrics(MeterRegistry registry) { this.registry = registry; }

    /** One stored health check: its outcome (healthy, degraded, unreachable, …) and how long it took. */
    public void healthCheck(String applicationId, String outcome, long elapsedMs) {
        if (knownApplications.add(applicationId)) registerAtZero(applicationId);
        Counter.builder("relay.health.checks").description("Health checks recorded, by outcome")
                .tag("application", applicationId).tag("outcome", safe(outcome)).register(registry).increment();
        Timer.builder("relay.health.check.duration").description("Time taken by one health check")
                .tag("application", applicationId).register(registry).record(Duration.ofMillis(Math.max(0, elapsedMs)));
    }

    /** An incident opened; {@code detectionMs} runs from the first failed check to the incident (time to detect). */
    public void incidentOpened(String applicationId, long detectionMs) {
        Counter.builder("relay.incidents.opened").description("Incidents opened")
                .tag("application", applicationId).register(registry).increment();
        Timer.builder("relay.incident.detection").description("Time to detect: first failed check to incident opened")
                .tag("application", applicationId).serviceLevelObjectives(INCIDENT_BUCKETS)
                .register(registry).record(Duration.ofMillis(Math.max(0, detectionMs)));
    }

    /**
     * An incident closed. {@code resolution} is {@code verified_recovery} when a RELAY action passed verification, or
     * {@code observed} when monitoring saw the system healthy without a verified RELAY action. Time to recover runs
     * from the start of the failure (the first failed check) to the recovery.
     */
    public void incidentResolved(String applicationId, String resolution, Instant failureStartedAt, Instant recoveredAt) {
        Counter.builder("relay.incidents.resolved").description("Incidents resolved, by how the recovery was established")
                .tag("application", safe(applicationId)).tag("resolution", resolution).register(registry).increment();
        if (failureStartedAt == null || recoveredAt == null || recoveredAt.isBefore(failureStartedAt)) return;
        Timer.builder("relay.incident.recovery").description("Time to recover: first failed check to recovery")
                .tag("application", safe(applicationId)).tag("resolution", resolution).serviceLevelObjectives(INCIDENT_BUCKETS)
                .register(registry).record(Duration.between(failureStartedAt, recoveredAt));
    }

    /** A recovery policy decision: {@code authorized}, {@code requires_approval} or {@code blocked}. */
    public void recoveryDecision(String action, String decision, boolean automatic) {
        Counter.builder("relay.recovery.decisions").description("Recovery policy decisions")
                .tag("action", safe(action)).tag("decision", decision).tag("mode", automatic ? "automatic" : "operator")
                .register(registry).increment();
    }

    /** One executed recovery attempt and its verification result ({@code passed}, {@code failed}, {@code timed_out}). */
    public void recoveryAttempt(String action, String verification) {
        Counter.builder("relay.recovery.attempts").description("Recovery attempts, by verification result")
                .tag("action", safe(action)).tag("verification", safe(verification)).register(registry).increment();
    }

    /** A recovery resumed from the action journal after a restart, by the result of its re-verification. */
    public void recoveryResumed(String action, String verification) {
        Counter.builder("relay.recovery.resumed").description("Recoveries resumed from the action journal after a restart")
                .tag("action", safe(action)).tag("verification", safe(verification)).register(registry).increment();
    }

    /** A finished recovery: total time from authorization to the final verification, with its outcome. */
    public void recoveryCompleted(String action, String outcome, long durationMs) {
        Timer.builder("relay.recovery.duration").description("Authorized action to final verification")
                .tag("action", safe(action)).tag("outcome", safe(outcome)).serviceLevelObjectives(INCIDENT_BUCKETS)
                .register(registry).record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    /**
     * Prometheus cannot see the first increment of a series that appears already non-zero ({@code increase()} has no
     * earlier sample), so a system's incident series are created at zero on its first check, long before an incident.
     */
    private void registerAtZero(String applicationId) {
        Counter.builder("relay.incidents.opened").description("Incidents opened")
                .tag("application", applicationId).register(registry);
        Timer.builder("relay.incident.detection").description("Time to detect: first failed check to incident opened")
                .tag("application", applicationId).serviceLevelObjectives(INCIDENT_BUCKETS).register(registry);
        for (String resolution : new String[] {"verified_recovery", "observed"}) {
            Counter.builder("relay.incidents.resolved").description("Incidents resolved, by how the recovery was established")
                    .tag("application", applicationId).tag("resolution", resolution).register(registry);
            Timer.builder("relay.incident.recovery").description("Time to recover: first failed check to recovery")
                    .tag("application", applicationId).tag("resolution", resolution).serviceLevelObjectives(INCIDENT_BUCKETS)
                    .register(registry);
        }
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
