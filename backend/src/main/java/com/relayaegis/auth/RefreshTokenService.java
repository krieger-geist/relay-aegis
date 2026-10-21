package com.relayaegis.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque, rotating refresh tokens. The raw token (256 random bits) is only ever sent to the client; the database holds
 * its SHA-256 hash. Every refresh revokes the presented token and issues a successor in the same family. Presenting an
 * already-rotated token is treated as theft: the whole family is revoked, ending that login everywhere.
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_RAW_LENGTH = 200;

    private final RefreshTokenRepository tokens;
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository tokens, AuthProperties properties) { this(tokens, properties, Clock.systemUTC()); }

    public RefreshTokenService(RefreshTokenRepository tokens, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public record IssuedRefreshToken(String rawToken, String userId, Instant expiresAt) {}
    public sealed interface Rotation permits Rotated, Rejected {}
    public record Rotated(IssuedRefreshToken token) implements Rotation {}
    public record Rejected(String reason) implements Rotation {}

    /** Starts a new token family (one per sign-in). */
    @Transactional
    public IssuedRefreshToken issue(String userId) { return create(userId, UUID.randomUUID().toString()).issued(); }

    /**
     * Rotates a refresh token. A rejection is returned (not thrown) so a reuse-triggered family revocation commits.
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        if (!plausible(rawToken)) return new Rejected("missing");
        Optional<RefreshTokenEntity> found = tokens.findByHashForUpdate(hash(rawToken));
        if (found.isEmpty()) return new Rejected("unknown");
        RefreshTokenEntity current = found.get();
        Instant now = clock.instant();
        if (current.revokedAt != null) {
            int revoked = tokens.revokeFamily(current.familyId, now);
            if (current.replacedById != null)
                log.warn("Rotated refresh token presented again for user {}; revoked {} active token(s) in its family.", current.userId, revoked);
            return new Rejected("revoked");
        }
        if (!current.expiresAt.isAfter(now)) return new Rejected("expired");
        Created next = create(current.userId, current.familyId);
        current.revokedAt = now;
        current.replacedById = next.entity().id;
        tokens.save(current);
        return new Rotated(next.issued());
    }

    /** Revokes the login (token family) the presented token belongs to. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        if (!plausible(rawToken)) return;
        tokens.findByTokenHash(hash(rawToken)).ifPresent(token -> tokens.revokeFamily(token.familyId, clock.instant()));
    }

    @Transactional
    public void revokeAllForUser(String userId) { tokens.revokeAllForUser(userId, clock.instant()); }

    /** Housekeeping: drop tokens that expired more than a day ago. */
    @Scheduled(initialDelayString = "${relay.auth.cleanup-initial-delay-ms:300000}", fixedDelayString = "${relay.auth.cleanup-interval-ms:21600000}")
    @Transactional
    public void purgeExpired() {
        int deleted = tokens.deleteExpiredBefore(clock.instant().minusSeconds(86_400));
        if (deleted > 0) log.info("Removed {} expired refresh token record(s).", deleted);
    }

    private record Created(RefreshTokenEntity entity, IssuedRefreshToken issued) {}

    private Created create(String userId, String familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = UUID.randomUUID().toString();
        entity.userId = userId;
        entity.familyId = familyId;
        entity.tokenHash = hash(raw);
        entity.createdAt = now;
        entity.expiresAt = now.plus(properties.refreshTokenTtl());
        tokens.save(entity);
        return new Created(entity, new IssuedRefreshToken(raw, userId, entity.expiresAt));
    }

    private static boolean plausible(String rawToken) { return rawToken != null && !rawToken.isBlank() && rawToken.length() <= MAX_RAW_LENGTH; }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
