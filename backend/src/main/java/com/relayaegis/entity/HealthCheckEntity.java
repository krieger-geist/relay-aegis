package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "health_checks", indexes = {
    @Index(name = "idx_health_checks_app_time", columnList = "application_id,checked_at")
})
public class HealthCheckEntity {
    @Id public String id;
    @Column(nullable = false, length = 60) public String applicationId;
    @Column(nullable = false) public Instant checkedAt;
    @Column(nullable = false) public boolean healthy;
    public Long responseMs;
    @Column(length = 1000) public String errorMessage;
    @Column(nullable = false, length = 24) public String provenance;
    /** healthy | degraded (HTTP response but not 2xx) | unreachable (no HTTP response). Null on rows recorded before V4. */
    @Column(length = 24) public String outcome;
    public Integer statusCode;
    public HealthCheckEntity() {}
}
