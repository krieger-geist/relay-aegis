package com.relayaegis.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RelayMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RelayMetrics metrics = new RelayMetrics(registry);

    @Test
    void firstCheckCreatesTheSystemsIncidentSeriesAtZero() {
        metrics.healthCheck("app-1", "healthy", 12);

        assertEquals(0, registry.get("relay.incidents.opened").tags("application", "app-1").counter().count());
        assertEquals(0, registry.get("relay.incident.detection").tags("application", "app-1").timer().count());
        assertEquals(0, registry.get("relay.incidents.resolved").tags("application", "app-1", "resolution", "observed").counter().count());
        assertEquals(0, registry.get("relay.incidents.resolved").tags("application", "app-1", "resolution", "verified_recovery").counter().count());
    }

    @Test
    void timeToRecoverRunsFromTheFirstFailedCheck() {
        Instant failureStarted = Instant.parse("2026-10-31T10:00:00Z");
        metrics.incidentResolved("app-1", "verified_recovery", failureStarted, failureStarted.plusSeconds(42));

        var timer = registry.get("relay.incident.recovery").tags("resolution", "verified_recovery").timer();
        assertEquals(1, timer.count());
        assertEquals(42.0, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void resolutionWithoutUsableTimesIsCountedButNotTimed() {
        metrics.incidentResolved("app-1", "observed", null, Instant.now());

        assertEquals(1, registry.get("relay.incidents.resolved").tags("resolution", "observed").counter().count());
        assertTrue(registry.find("relay.incident.recovery").timers().isEmpty());
    }
}
