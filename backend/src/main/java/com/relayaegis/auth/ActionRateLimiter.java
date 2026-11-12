package com.relayaegis.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user hourly limits on actions that make the server do work for a visitor: starting an assessment (which may
 * contact a website or clone a repository) and registering an application (which adds a health check every minute).
 * On a public instance anyone can sign up, so without this one account could make the server scan a third-party site
 * hundreds of times. ADMIN accounts are not limited. A limit of 0 turns it off. In memory, per backend instance.
 */
@Component
public class ActionRateLimiter {
    static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED = 10_000;

    public enum Action {
        ASSESSMENT("assessments"),
        APPLICATION("new applications");

        private final String label;
        Action(String label) { this.label = label; }
    }

    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
    private final Map<Action, Integer> limits;
    private final Clock clock;

    @Autowired
    public ActionRateLimiter(@Value("${relay.limits.assessments-per-hour:30}") int assessmentsPerHour,
                             @Value("${relay.limits.applications-per-hour:20}") int applicationsPerHour) {
        this(assessmentsPerHour, applicationsPerHour, Clock.systemUTC());
    }

    public ActionRateLimiter(int assessmentsPerHour, int applicationsPerHour, Clock clock) {
        this.limits = Map.of(Action.ASSESSMENT, assessmentsPerHour, Action.APPLICATION, applicationsPerHour);
        this.clock = clock;
    }

    public int limit(Action action) { return limits.get(action); }

    /** Counts one use of {@code action} by {@code user}, or refuses it with 429 and Retry-After when the hour's limit is reached. */
    public void acquire(AuthenticatedUser user, Action action) {
        int limit = limits.get(action);
        if (limit <= 0 || user == null || user.role() == Role.ADMIN) return;
        if (recent.size() > MAX_TRACKED) recent.entrySet().removeIf(entry -> pruneAndCheckEmpty(entry.getValue()));
        Deque<Instant> uses = recent.computeIfAbsent(action.name() + ":" + user.id(), ignored -> new ArrayDeque<>());
        synchronized (uses) {
            prune(uses);
            if (uses.size() >= limit) {
                long seconds = Math.max(1, Duration.between(clock.instant(), uses.peekFirst().plus(WINDOW)).toSeconds());
                throw new LimitReached(action, limit, seconds);
            }
            uses.addLast(clock.instant());
        }
    }

    private boolean pruneAndCheckEmpty(Deque<Instant> uses) {
        synchronized (uses) {
            prune(uses);
            return uses.isEmpty();
        }
    }

    private void prune(Deque<Instant> uses) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!uses.isEmpty() && !uses.peekFirst().isAfter(cutoff)) uses.removeFirst();
    }

    /** 429 with a Retry-After header, so scripts back off and the dashboard can say when to try again. */
    public static final class LimitReached extends ResponseStatusException {
        private final long retryAfterSeconds;

        LimitReached(Action action, int limit, long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Limit reached: " + limit + " " + action.label + " per hour per account. Try again in "
                    + Math.max(1, (retryAfterSeconds + 59) / 60) + " min.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            return headers;
        }
    }
}
