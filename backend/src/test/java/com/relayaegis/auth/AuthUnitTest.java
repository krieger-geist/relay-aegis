package com.relayaegis.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthUnitTest {
    private static final String SECRET = "unit-test-signing-secret-at-least-32-bytes";

    private static AuthProperties props(String secret) {
        return new AuthProperties(secret, null, null, null, null, null, " Admin@Relay.Test , ops@relay.test ");
    }

    private static UserEntity user() {
        UserEntity user = new UserEntity();
        user.id = "user-1";
        user.role = Role.USER;
        return user;
    }

    @Test
    void passwordPolicyMatchesTheDocumentedRules() {
        assertThat(StrongPassword.Validator.problem("Abcdefgh1")).isEqualTo("must be at least 10 characters");
        assertThat(StrongPassword.Validator.problem("abcdefghijk")).isEqualTo("must include a letter and a number or symbol");
        assertThat(StrongPassword.Validator.problem("1234567890")).isEqualTo("must include a letter and a number or symbol");
        assertThat(StrongPassword.Validator.problem(" leading-space1")).isEqualTo("must not start or end with whitespace");
        assertThat(StrongPassword.Validator.problem("a".repeat(70) + "1é")).isEqualTo("must be at most 72 bytes");
        assertThat(StrongPassword.Validator.problem("correct horse-battery")).isNull();
        assertThat(StrongPassword.Validator.problem("Passphrase42")).isNull();
    }

    @Test
    void propertiesApplyDefaultsNormalizeAdminEmailsAndRejectUnsafeLifetimes() {
        AuthProperties p = props(SECRET);
        assertThat(p.issuer()).isEqualTo("relay-aegis");
        assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(p.adminEmailSet()).containsExactlyInAnyOrder("admin@relay.test", "ops@relay.test");
        assertThatThrownBy(() -> new AuthProperties(SECRET, null, null, Duration.ofHours(5), null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthProperties(SECRET, null, null, Duration.ofMinutes(15), Duration.ofMinutes(10), null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwtSecretMustBeLongEnoughAndBlankFallsBackToRandomKey() {
        assertThatThrownBy(() -> new JwtService(props("too-short"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        JwtService ephemeralA = new JwtService(props(""));
        JwtService ephemeralB = new JwtService(props(""));
        String token = ephemeralA.issue(user()).token();
        assertThat(ephemeralA.verify(token)).isEqualTo(new JwtService.Valid("user-1"));
        assertThat(ephemeralB.verify(token)).isInstanceOf(JwtService.Invalid.class);
    }

    @Test
    void jwtVerificationHonoursExpiryWithSmallSkew() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        String token = new JwtService(props(SECRET), Clock.fixed(issuedAt, ZoneOffset.UTC)).issue(user()).token();
        assertThat(new JwtService(props(SECRET), Clock.fixed(issuedAt.plus(Duration.ofMinutes(14)), ZoneId.of("UTC"))).verify(token))
                .isInstanceOf(JwtService.Valid.class);
        assertThat(new JwtService(props(SECRET), Clock.fixed(issuedAt.plus(Duration.ofMinutes(16)), ZoneId.of("UTC"))).verify(token))
                .isInstanceOf(JwtService.Expired.class);
        assertThat(new JwtService(props(SECRET)).verify(null)).isInstanceOf(JwtService.Invalid.class);
        assertThat(new JwtService(props(SECRET)).verify("x".repeat(5000))).isInstanceOf(JwtService.Invalid.class);
    }

    @Test
    void loginLimiterBlocksAfterRepeatedFailuresUntilTheWindowPasses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            assertThat(limiter.isBlocked("a@relay.test")).isFalse();
            limiter.recordFailure("a@relay.test");
        }
        assertThat(limiter.isBlocked("a@relay.test")).isTrue();
        assertThat(limiter.isBlocked("b@relay.test")).isFalse();
        clock.now = clock.now.plus(LoginAttemptLimiter.WINDOW).plusSeconds(1);
        assertThat(limiter.isBlocked("a@relay.test")).isFalse();
        limiter.recordFailure("a@relay.test");
        limiter.recordSuccess("a@relay.test");
        assertThat(limiter.isBlocked("a@relay.test")).isFalse();
    }

    @Test
    void refreshTokenHashIsStableHexSha256() {
        assertThat(RefreshTokenService.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
