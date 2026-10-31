package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The leadership lease: which backend instance currently monitors and runs recovery, and until when. */
@Entity
@Table(name = "leader_leases")
public class LeaderLeaseEntity {
    @Id @Column(length = 60) public String name;
    @Column(nullable = false, length = 120) public String holder;
    @Column(length = 300) public String holderUrl;
    @Column(nullable = false) public Instant acquiredAt;
    @Column(nullable = false) public Instant renewedAt;
    @Column(nullable = false) public Instant expiresAt;
    public LeaderLeaseEntity() {}
}
