package com.relayaegis.auth;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings (relay.auth.*), all supplied through environment variables.
 *
 * @param jwtSecret       HMAC-SHA256 signing secret, at least 32 bytes. Blank = a random per-process key (dev only:
 *                        access tokens then stop validating after a restart, refresh cookies keep working).
 * @param cookieSecure    "auto" (Secure when the request arrived over HTTPS), "true" or "false".
 * @param adminEmails     Comma-separated emails that receive the ADMIN role when they register. Everyone else is USER.
 */
@ConfigurationProperties(prefix = "relay.auth")
public record AuthProperties(String jwtSecret, String issuer, String audience, Duration accessTokenTtl,
                             Duration refreshTokenTtl, String cookieSecure, String adminEmails) {
    public AuthProperties {
        if (issuer == null || issuer.isBlank()) issuer = "relay-aegis";
        if (audience == null || audience.isBlank()) audience = "relay-aegis-web";
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(7);
        if (cookieSecure == null || cookieSecure.isBlank()) cookieSecure = "auto";
        if (accessTokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || accessTokenTtl.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalStateException("relay.auth.access-token-ttl must be between 1 minute and 1 hour.");
        if (refreshTokenTtl.compareTo(accessTokenTtl) <= 0 || refreshTokenTtl.compareTo(Duration.ofDays(90)) > 0)
            throw new IllegalStateException("relay.auth.refresh-token-ttl must exceed the access token TTL and be at most 90 days.");
    }

    public Set<String> adminEmailSet() {
        if (adminEmails == null || adminEmails.isBlank()) return Set.of();
        return Arrays.stream(adminEmails.split(",")).map(AuthProperties::normalizeEmail).filter(e -> !e.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public static String normalizeEmail(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
}
