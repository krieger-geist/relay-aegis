package com.relayaegis.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived HS256 access tokens. Validation checks signature, algorithm, expiry, issuer,
 * audience and token type. The role claim is informational only: the request filter re-reads the role from the DB.
 */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    static final String TOKEN_USE = "access";
    private static final int MIN_SECRET_BYTES = 32;

    private final AuthProperties properties;
    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public JwtService(AuthProperties properties) { this(properties, Clock.systemUTC()); }

    public JwtService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = signingKey(properties.jwtSecret());
    }

    public record IssuedToken(String token, Instant expiresAt) {}
    public sealed interface Verification permits Valid, Expired, Invalid {}
    public record Valid(String userId) implements Verification {}
    public record Expired() implements Verification {}
    public record Invalid() implements Verification {}

    public IssuedToken issue(UserEntity user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.id)
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("token_use", TOKEN_USE)
                .claim("role", user.role.name())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    public Verification verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) return new Invalid();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .requireAudience(properties.audience())
                    .require("token_use", TOKEN_USE)
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(30)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            return subject == null || subject.isBlank() ? new Invalid() : new Valid(subject);
        } catch (ExpiredJwtException expired) {
            return new Expired();
        } catch (JwtException | IllegalArgumentException invalid) {
            return new Invalid();
        }
    }

    private static SecretKey signingKey(String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("RELAY_AUTH_JWT_SECRET is not set: using a random per-process signing key. Access tokens will not "
                    + "survive a backend restart (sessions recover through the refresh cookie). Set a secret of at least "
                    + MIN_SECRET_BYTES + " bytes for any shared or long-running deployment.");
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            return Keys.hmacShaKeyFor(random);
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES)
            throw new IllegalStateException("RELAY_AUTH_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes.");
        return Keys.hmacShaKeyFor(bytes);
    }
}
