package com.relayaegis.service;

import com.relayaegis.entity.HealthCheckEntity;
import java.time.Instant;
import java.util.List;

/** Pure decision rules for monitoring; kept separate so thresholds and classification are unit-testable. */
public final class MonitoringRules {
    public static final String HEALTHY = "healthy";
    public static final String DEGRADED = "degraded";
    public static final String UNREACHABLE = "unreachable";

    private MonitoringRules() {}

    /** An HTTP response was received: 2xx is healthy, anything else (including an unfollowed redirect) is degraded. */
    public static String classifyStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300 ? HEALTHY : DEGRADED;
    }

    public static int clampThreshold(int value) {
        return Math.max(1, Math.min(10, value));
    }

    /** True when the newest {@code count} observations (newest first) are all failures. */
    public static boolean hasFailureStreak(List<HealthCheckEntity> newestFirst, int count) {
        if (newestFirst.size() < count) return false;
        return newestFirst.stream().limit(count).noneMatch(check -> check.healthy);
    }

    /**
     * True when the newest {@code count} observations are healthy and were all recorded after the
     * incident was detected, so a recovery is never inferred from observations that predate the failure.
     */
    public static boolean hasRecoveryStreak(List<HealthCheckEntity> newestFirst, int count, Instant detectedAt) {
        if (newestFirst.size() < count) return false;
        return newestFirst.stream().limit(count).allMatch(check -> check.healthy
                && check.checkedAt != null && (detectedAt == null || check.checkedAt.isAfter(detectedAt)));
    }

    /** Number of consecutive healthy observations at the head of the list. */
    public static int leadingHealthy(List<HealthCheckEntity> newestFirst) {
        int count = 0;
        for (HealthCheckEntity check : newestFirst) { if (!check.healthy) break; count++; }
        return count;
    }

    /** Number of consecutive failed observations at the head of the list. */
    public static int leadingFailures(List<HealthCheckEntity> newestFirst) {
        int count = 0;
        for (HealthCheckEntity check : newestFirst) { if (check.healthy) break; count++; }
        return count;
    }
}
