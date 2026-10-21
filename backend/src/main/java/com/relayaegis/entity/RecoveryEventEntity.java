package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "recovery_events", indexes = @Index(name = "idx_recovery_events_time", columnList = "occurred_at"))
public class RecoveryEventEntity {
    @Id public String id;
    @Column(nullable = false, length = 60) public String incidentId;
    @Column(nullable = false, length = 160) public String applicationName;
    @Column(nullable = false, length = 240) public String event;
    @Column(nullable = false, length = 48) public String outcome;
    @Column(nullable = false) public Instant occurredAt;
    public Long detectionDurationMs;
    public Long recoveryDurationMs;
    @Column(length = 2000) public String detail;
    @Column(nullable = false, length = 24) public String provenance;
    public RecoveryEventEntity() {}
}
