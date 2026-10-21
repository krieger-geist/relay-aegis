package com.relayaegis.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A refresh token record. Only the SHA-256 hash of the token is stored; the raw value is never persisted. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {
    @Id @Column(length = 40) public String id;
    @Column(nullable = false, length = 40) public String userId;
    @Column(nullable = false, length = 40) public String familyId;
    @Column(nullable = false, length = 64, unique = true) public String tokenHash;
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public Instant expiresAt;
    public Instant revokedAt;
    @Column(length = 40) public String replacedById;
    public RefreshTokenEntity() {}

    public boolean isActive(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
}
