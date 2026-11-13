package com.relayaegis.diagnostics;

import com.relayaegis.entity.HealthCheckEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * The application's normal response time, learned from its own recent healthy checks (newest first in the input).
 * Checks in the current failure streak are excluded, so the baseline describes "before". {@code lastHealthyMs} is the
 * response time of the newest healthy check: when it is far above the median, the application was slowing down
 * before it failed. Needs at least {@link #MIN_SAMPLES} healthy samples; with fewer there is no baseline.
 */
public record LatencyBaseline(int samples, long medianMs, long p95Ms, Long lastHealthyMs) {
    public static final int MIN_SAMPLES = 5;
    /** The last healthy check is "slow" when it took at least this many times the median, and at least {@link #SLOW_MIN_MS}. */
    public static final double SLOW_FACTOR = 3.0;
    public static final long SLOW_MIN_MS = 200;

    public static LatencyBaseline from(List<HealthCheckEntity> newestFirst) {
        if (newestFirst == null) return null;
        List<Long> values = new ArrayList<>();
        Long last = null;
        boolean inStreak = true;
        for (HealthCheckEntity check : newestFirst) {
            if (inStreak && !check.healthy) continue;
            inStreak = false;
            if (!check.healthy || check.responseMs == null || check.responseMs < 0) continue;
            if (last == null) last = check.responseMs;
            values.add(check.responseMs);
        }
        if (values.size() < MIN_SAMPLES) return null;
        List<Long> sorted = values.stream().sorted().toList();
        return new LatencyBaseline(sorted.size(), percentile(sorted, 50), percentile(sorted, 95), last);
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** The newest healthy check was much slower than normal. */
    public boolean slowBeforeFailure() {
        return lastHealthyMs != null && lastHealthyMs >= SLOW_MIN_MS && lastHealthyMs >= SLOW_FACTOR * Math.max(1, medianMs);
    }

    public String describe() {
        String text = "Response time baseline: median " + medianMs + " ms, p95 " + p95Ms + " ms over " + samples + " healthy checks";
        if (lastHealthyMs == null) return text + ".";
        return text + (slowBeforeFailure()
                ? "; the last healthy check took " + lastHealthyMs + " ms, " + Math.round((double) lastHealthyMs / Math.max(1, medianMs))
                        + "x the median, so the application was slowing down before it failed."
                : "; the last healthy check took " + lastHealthyMs + " ms (normal).");
    }
}
