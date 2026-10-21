package com.relayaegis.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id @Column(length = 40) public String id;
    @Column(nullable = false, length = 120) public String fullName;
    /** Normalized (trimmed, lower-case) and unique. */
    @Column(nullable = false, length = 254, unique = true) public String email;
    /** BCrypt hash. Never serialized to any API response or log. */
    @Column(nullable = false, length = 100) public String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) public Role role;
    @Column(nullable = false) public boolean enabled;
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public Instant updatedAt;
    public UserEntity() {}

    @Override public String toString() { return "UserEntity[id=" + id + ", role=" + role + ", enabled=" + enabled + "]"; }
}
