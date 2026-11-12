package com.relayaegis.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relayaegis.auth.ActionRateLimiter.Action;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ActionRateLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-13T10:00:00Z"));
    private final AuthenticatedUser user = new AuthenticatedUser("u1", "u1@relay.test", "User", Role.USER);

    @Test
    void refusesOverTheLimitAndAllowsAgainOnceTheOldestUseLeavesTheHour() {
        ActionRateLimiter limiter = new ActionRateLimiter(2, 5, clock);
        limiter.acquire(user, Action.ASSESSMENT);
        clock.advance(Duration.ofMinutes(10));
        limiter.acquire(user, Action.ASSESSMENT);

        assertThatThrownBy(() -> limiter.acquire(user, Action.ASSESSMENT))
                .isInstanceOfSatisfying(ActionRateLimiter.LimitReached.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(429);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(50).toSeconds());
                    assertThat(ex.getHeaders().getFirst("Retry-After")).isEqualTo("3000");
                });
        // Other actions have their own count.
        assertThatCode(() -> limiter.acquire(user, Action.APPLICATION)).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(50));
        assertThatCode(() -> limiter.acquire(user, Action.ASSESSMENT)).doesNotThrowAnyException();
    }

    @Test
    void adminsAndAZeroLimitAreNotLimited() {
        AuthenticatedUser admin = new AuthenticatedUser("a1", "a1@relay.test", "Admin", Role.ADMIN);
        ActionRateLimiter limiter = new ActionRateLimiter(1, 0, clock);
        for (int i = 0; i < 5; i++) {
            limiter.acquire(admin, Action.ASSESSMENT);
            limiter.acquire(user, Action.APPLICATION);
        }
        limiter.acquire(user, Action.ASSESSMENT);
        assertThatThrownBy(() -> limiter.acquire(user, Action.ASSESSMENT)).isInstanceOf(ActionRateLimiter.LimitReached.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
