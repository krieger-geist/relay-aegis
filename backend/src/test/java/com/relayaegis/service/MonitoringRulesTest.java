package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.entity.HealthCheckEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void classifiesOnly2xxAsHealthyAndEverythingElseAsDegraded() {
        assertEquals("healthy", MonitoringRules.classifyStatus(200));
        assertEquals("healthy", MonitoringRules.classifyStatus(204));
        assertEquals("degraded", MonitoringRules.classifyStatus(302));
        assertEquals("degraded", MonitoringRules.classifyStatus(404));
        assertEquals("degraded", MonitoringRules.classifyStatus(503));
    }

    @Test
    void failureStreakRequiresThresholdConsecutiveFailures() {
        assertFalse(MonitoringRules.hasFailureStreak(List.of(check(false, 0)), 2));
        assertFalse(MonitoringRules.hasFailureStreak(List.of(check(false, 0), check(true, 60)), 2));
        assertTrue(MonitoringRules.hasFailureStreak(List.of(check(false, 0), check(false, 60)), 2));
        assertTrue(MonitoringRules.hasFailureStreak(List.of(check(false, 0)), 1));
    }

    @Test
    void recoveryStreakIgnoresHealthyChecksRecordedBeforeDetection() {
        Instant detectedAt = NOW.minusSeconds(90);
        // Two healthy checks, but the older one predates the incident: not a recovery streak.
        assertFalse(MonitoringRules.hasRecoveryStreak(List.of(check(true, 0), check(true, 120)), 2, detectedAt));
        assertTrue(MonitoringRules.hasRecoveryStreak(List.of(check(true, 0), check(true, 30)), 2, detectedAt));
        assertFalse(MonitoringRules.hasRecoveryStreak(List.of(check(true, 0), check(false, 30)), 2, detectedAt));
    }

    @Test
    void thresholdsAreClampedToASafeRange() {
        assertEquals(1, MonitoringRules.clampThreshold(0));
        assertEquals(10, MonitoringRules.clampThreshold(50));
        assertEquals(3, MonitoringRules.clampThreshold(3));
    }

    private static HealthCheckEntity check(boolean healthy, long secondsAgo) {
        HealthCheckEntity check = new HealthCheckEntity();
        check.healthy = healthy;
        check.checkedAt = NOW.minusSeconds(secondsAgo);
        return check;
    }
}
