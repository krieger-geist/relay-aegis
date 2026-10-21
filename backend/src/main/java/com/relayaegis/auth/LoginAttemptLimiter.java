package com.relayaegis.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory brute-force brake per normalized email: after MAX_FAILURES failed sign-ins within WINDOW, further
 * attempts for that email are refused until the oldest failure leaves the window. Single backend instance only.
 */
@Component
public class LoginAttemptLimiter {
    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LoginAttemptLimiter() { this(Clock.systemUTC()); }
    public LoginAttemptLimiter(Clock clock) { this.clock = clock; }

    public boolean isBlocked(String email) {
        Deque<Instant> recent = failures.get(email);
        if (recent == null) return false;
        synchronized (recent) {
            prune(recent);
            return recent.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String email) {
        if (failures.size() > MAX_TRACKED) failures.entrySet().removeIf(entry -> pruneAndCheckEmpty(entry.getValue()));
        Deque<Instant> recent = failures.computeIfAbsent(email, ignored -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
    }

    public void recordSuccess(String email) { failures.remove(email); }

    private boolean pruneAndCheckEmpty(Deque<Instant> recent) {
        synchronized (recent) {
            prune(recent);
            return recent.isEmpty();
        }
    }

    private void prune(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) recent.removeFirst();
    }
}
